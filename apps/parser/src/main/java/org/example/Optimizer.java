package org.example;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

/**
 * {@code java -jar parser.jar optimize --root DIR --input request.json [--output-dir OUT]}.
 *
 * Rule {@code nested_scan_to_hashmap}: a loop that, for every iteration of an enclosing loop, scans a
 * collection for the elements whose key equals a value computed outside the scan is replaced by a
 * {@code HashMap} index built once before the enclosing loop (ASTRewrite; no text editing).
 *
 * <pre>
 *   for (Task t : tasks) { for (Contact c : contacts) { if (c.id() == t.contactId()) { BODY; break; } } }
 * becomes
 *   Map&lt;Integer, Contact&gt; contactsById = new HashMap&lt;&gt;();
 *   for (Contact c : contacts) { contactsById.putIfAbsent(c.id(), c); }
 *   for (Task t : tasks) { Contact c = contactsById.get(t.contactId()); if (c != null) { BODY } }
 * </pre>
 *
 * Without the trailing {@code break} every match runs BODY, so the index groups elements per key in
 * iteration order ({@code Map<K, List<E>>}). Every proposal lists preconditions, each
 * {@code satisfied} or {@code violated} by static analysis; a violated one makes the proposal
 * {@code not_applicable} and nothing is rewritten. Behaviour that static analysis cannot rule out
 * (null or throwing elements, concurrent modification) is listed as an assumption the caller must
 * acknowledge before the rewrite is produced.
 *
 * Request: {@code {"source_level","files":[...],"action":"discover"|"apply","proposal_id",
 * "acknowledged_assumptions":[...],"preview":false}}. With {@code preview: true} the assumptions need not be
 * acknowledged (nothing is committed from a preview). {@code apply} writes the complete candidate project tree to
 * {@code --output-dir}, re-analyses it and rejects it if it introduced new errors.
 */
final class Optimizer {
    static final String RULE = "nested_scan_to_hashmap";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String A_NULL_TIMING = "null_and_exception_timing";
    private static final String A_THREADS = "single_threaded";
    private static final String A_OUTER_KEY = "outer_key_non_null";

    private Optimizer() {
    }

    // ---------- CLI ----------

    static int run(String[] args, PrintStream out, PrintStream err) {
        String root = null;
        String input = null;
        String outputDir = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = i + 1 < args.length ? args[++i] : null;
                case "--input" -> input = i + 1 < args.length ? args[++i] : null;
                case "--output-dir" -> outputDir = i + 1 < args.length ? args[++i] : null;
                default -> {
                    err.println("usage: optimize --root DIR --input request.json [--output-dir OUT]");
                    return 2;
                }
            }
        }
        if (root == null || input == null) {
            err.println("usage: optimize --root DIR --input request.json [--output-dir OUT]");
            return 2;
        }
        try {
            JsonNode request = JSON.readTree(Files.readAllBytes(Path.of(input)));
            String level = request.path("source_level").asText(ProjectAnalyzer.DEFAULT_SOURCE_LEVEL);
            List<String> files = new ArrayList<>();
            for (JsonNode file : request.path("files")) {
                files.add(file.asText());
            }
            String action = request.path("action").asText("discover");
            Map<String, Object> result;
            if (action.equals("discover")) {
                result = discover(Path.of(root), files, level);
            } else if (action.equals("apply")) {
                if (outputDir == null) {
                    err.println("apply requires --output-dir");
                    return 2;
                }
                Set<String> acknowledged = new LinkedHashSet<>();
                for (JsonNode a : request.path("acknowledged_assumptions")) {
                    acknowledged.add(a.asText());
                }
                result = apply(Path.of(root), files, level, request.path("proposal_id").asText(""), acknowledged,
                        request.path("preview").asBoolean(false), Path.of(outputDir));
            } else {
                return fail(out, "invalid_request", "Unknown action: " + action);
            }
            out.println(Json.pretty(result));
            return "completed".equals(result.get("status")) ? 0 : 1;
        } catch (IOException | RuntimeException | StackOverflowError e) {
            e.printStackTrace(err);
            return fail(out, "internal_error", "Optimizer failed: " + e);
        }
    }

    private static int fail(PrintStream out, String code, String message) {
        out.println(Json.pretty(failure(code, message)));
        return 1;
    }

    static Map<String, Object> failure(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "failed");
        result.put("error", error);
        return result;
    }

    // ---------- candidate model ----------

    private record Precondition(String id, boolean satisfied, String detail) {
    }

    private static final class Candidate {
        String id;
        AstJson json;
        String methodId;
        MethodDeclaration method;
        Statement outerLoop;
        EnhancedForStatement inner;
        IfStatement test;
        boolean allMatches;
        Expression innerKey;
        Expression outerKey;
        String keyType;
        String keyTypeDescription;
        String equality;
        boolean outerReceiver;
        String collectionName;
        final List<Precondition> preconditions = new ArrayList<>();

        boolean applicable() {
            return preconditions.stream().allMatch(Precondition::satisfied);
        }
    }

    // ---------- discovery ----------

    private static Map<String, Object> discover(Path root, List<String> files, String level) throws IOException {
        Discovered d = scan(root, files, level);
        if (d.failure != null) {
            return d.failure;
        }
        List<Object> proposals = new ArrayList<>();
        for (Candidate c : d.candidates) {
            proposals.add(describe(c, d.analysis));
        }
        proposals.addAll(StringBuilderRule.discover(d.loaded, d.analysis));
        proposals.addAll(ContainsToSetRule.discover(d.loaded, d.analysis));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "completed");
        result.put("rule", RULE);
        result.put("proposals", proposals);
        return result;
    }

    private static final class Discovered {
        Map<String, Object> failure;
        Map<String, Object> analysis;
        ProjectAnalyzer.Loaded loaded;
        final List<Candidate> candidates = new ArrayList<>();
    }

    private static Discovered scan(Path root, List<String> files, String level) throws IOException {
        Discovered d = new Discovered();
        d.analysis = ProjectAnalyzer.analyze(root, files, level, loaded -> d.loaded = loaded);
        if ("failed".equals(d.analysis.get("status")) || d.loaded == null) {
            d.failure = d.analysis;
            return d;
        }
        for (AstJson json : d.loaded.jsons()) {
            json.unit().accept(new ASTVisitor() {
                @Override
                public boolean visit(EnhancedForStatement inner) {
                    Candidate c = candidate(d.loaded, json, inner);
                    if (c != null) {
                        d.candidates.add(c);
                    }
                    return true;
                }
            });
        }
        return d;
    }

    private static Statement enclosingLoop(ASTNode node) {
        for (ASTNode p = node.getParent(); p != null; p = p.getParent()) {
            switch (p) {
                case ForStatement f -> {
                    return f;
                }
                case EnhancedForStatement f -> {
                    return f;
                }
                case WhileStatement w -> {
                    return w;
                }
                case DoStatement w -> {
                    return w;
                }
                case MethodDeclaration m -> {
                    return null;
                }
                case LambdaExpression l -> {
                    return null;
                }
                case AbstractTypeDeclaration t -> {
                    return null;
                }
                case AnonymousClassDeclaration a -> {
                    return null;
                }
                case Initializer i -> {
                    return null;
                }
                default -> {
                }
            }
        }
        return null;
    }

    private static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode p = node.getParent(); p != null; p = p.getParent()) {
            if (p instanceof MethodDeclaration m) {
                return m;
            }
            if (p instanceof LambdaExpression || p instanceof AnonymousClassDeclaration) {
                return null;
            }
        }
        return null;
    }

    /** Null when {@code inner} is not shaped like a keyed lookup inside another loop. */
    private static Candidate candidate(ProjectAnalyzer.Loaded loaded, AstJson json, EnhancedForStatement inner) {
        Statement outer = enclosingLoop(inner);
        MethodDeclaration method = enclosingMethod(inner);
        if (outer == null || method == null) {
            return null;
        }
        IVariableBinding element = inner.getParameter().resolveBinding();
        if (element == null) {
            return null;
        }
        List<?> statements = inner.getBody() instanceof Block b ? b.statements() : List.of(inner.getBody());
        IfStatement test = null;
        for (Object o : statements) {
            if (o instanceof IfStatement is && is.getElseStatement() == null && keyTest(is.getExpression(), element) != null) {
                test = is;
                break;
            }
        }
        if (test == null) {
            return null;
        }

        Candidate c = new Candidate();
        c.json = json;
        c.method = method;
        c.outerLoop = outer;
        c.inner = inner;
        c.test = test;
        ProjectGraph.Link link = loaded.links().get(method);
        c.methodId = link == null ? null : link.methodId();
        c.collectionName = AstJson.normalize(json.text(inner.getExpression()));

        // --- shape
        boolean onlyTest = statements.size() == 1;
        c.preconditions.add(new Precondition("lookup_shape", onlyTest,
                onlyTest ? "The inner loop body is a single 'if' comparing a key of each element with a value from outside the scan."
                        : "The inner loop does more than the keyed test, so it cannot be replaced by an index lookup."));

        KeyTest kt = keyTest(test.getExpression(), element);
        c.innerKey = kt.inner;
        c.outerKey = kt.outer;
        c.equality = kt.equals ? "equals()" : "==";
        keyChecks(loaded, c, kt);

        // --- control flow of the matched body
        Statement then = test.getThenStatement();
        List<?> thenStatements = then instanceof Block tb ? tb.statements() : List.of(then);
        boolean endsWithBreak = !thenStatements.isEmpty() && thenStatements.get(thenStatements.size() - 1) instanceof BreakStatement bs
                && bs.getLabel() == null;
        ControlFlow cf = new ControlFlow();
        for (int i = 0; i < thenStatements.size() - (endsWithBreak ? 1 : 0); i++) {
            ((Statement) thenStatements.get(i)).accept(cf);
        }
        c.allMatches = !endsWithBreak;
        boolean flowOk = !cf.labeled && !(endsWithBreak && (cf.looseBreak || cf.looseContinue))
                && !(endsWithBreak && thenStatements.size() == 1);
        c.preconditions.add(new Precondition("control_flow", flowOk,
                flowOk ? (endsWithBreak ? "Matched body ends with an unconditional 'break': first match only."
                        : "Matched body has no trailing 'break': every match is processed, in iteration order.")
                        : cf.labeled ? "The matched body uses labeled break/continue."
                        : endsWithBreak && thenStatements.size() == 1 ? "The matched body is only a 'break'."
                        : "The matched body also breaks or continues the scan loop before its final 'break'."));

        // --- collection and loop-body effects
        IVariableBinding collection = inner.getExpression() instanceof SimpleName n && n.resolveBinding() instanceof IVariableBinding vb ? vb : null;
        boolean simple = collection != null;
        c.preconditions.add(new Precondition("collection_reference", simple && declaredOutside(collection, outer, json.unit()),
                !simple ? "The scanned collection is not a plain variable."
                        : declaredOutside(collection, outer, json.unit())
                        ? "'" + c.collectionName + "' is declared outside the enclosing loop, so the index can be built before it."
                        : "'" + c.collectionName + "' is declared inside the enclosing loop."));
        Effects effects = new Effects(loaded, collection, inner, method);
        outer.accept(effects);
        c.preconditions.add(new Precondition("collection_not_modified", simple && effects.problem == null,
                !simple ? "Cannot reason about a collection that is not a plain variable."
                        : effects.problem == null
                        ? "The enclosing loop only calls methods that cannot change '" + c.collectionName + "' or the keys, and assigns only local variables."
                        : effects.problem));

        ASTNode parent = outer.getParent();
        boolean parentOk = parent instanceof Block;
        c.preconditions.add(new Precondition("insertion_point", parentOk,
                parentOk ? "The enclosing loop sits in a block where the index can be declared before it."
                        : "The enclosing loop is not directly inside a block (e.g. the unbraced body of an 'if')."));

        c.id = proposalId(json, c);
        return c;
    }

    private static boolean declaredOutside(IVariableBinding v, Statement outer, CompilationUnit cu) {
        if (v.isField()) {
            return true;
        }
        ASTNode decl = cu.findDeclaringNode(v);
        if (decl == null) {
            return false;
        }
        for (ASTNode p = decl; p != null; p = p.getParent()) {
            if (p == outer) {
                return false;
            }
        }
        return true;
    }

    private static String proposalId(AstJson json, Candidate c) {
        String basis = RULE + "|" + json.file() + "|" + c.methodId + "|" + c.outerLoop.getStartPosition() + "|"
                + c.inner.getStartPosition() + "|" + c.inner.getLength();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("opt_");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- key test ----------

    private record KeyTest(Expression inner, Expression outer, boolean equals, boolean receiverIsInner) {
    }

    /** {@code a == b} or {@code a.equals(b)} where exactly one side mentions the scan variable. */
    private static KeyTest keyTest(Expression raw, IVariableBinding element) {
        Expression cond = AstJson.strip(raw);
        Expression a;
        Expression b;
        boolean equals;
        if (cond instanceof InfixExpression ie && ie.getOperator() == InfixExpression.Operator.EQUALS && !ie.hasExtendedOperands()) {
            a = AstJson.strip(ie.getLeftOperand());
            b = AstJson.strip(ie.getRightOperand());
            equals = false;
        } else if (cond instanceof MethodInvocation mi && mi.getName().getIdentifier().equals("equals")
                && mi.arguments().size() == 1 && mi.getExpression() != null) {
            a = AstJson.strip(mi.getExpression());
            b = AstJson.strip((Expression) mi.arguments().get(0));
            equals = true;
        } else {
            return null;
        }
        boolean aInner = mentions(a, element);
        boolean bInner = mentions(b, element);
        if (aInner == bInner) {
            return null;
        }
        Expression other = aInner ? b : a;
        if (other.resolveConstantExpressionValue() != null || other instanceof NullLiteral) {
            return null; // a filter, not a keyed lookup
        }
        return new KeyTest(aInner ? a : b, other, equals, aInner);
    }

    private static boolean mentions(Expression e, IVariableBinding v) {
        boolean[] found = {false};
        e.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName n) {
                if (n.resolveBinding() instanceof IVariableBinding vb && vb.isEqualTo(v)) {
                    found[0] = true;
                }
                return true;
            }
        });
        return found[0];
    }

    private static void keyChecks(ProjectAnalyzer.Loaded loaded, Candidate c, KeyTest kt) {
        ITypeBinding ti = kt.inner().resolveTypeBinding();
        ITypeBinding to = kt.outer().resolveTypeBinding();

        // --- equality semantics and hash key type
        String semantics = null;
        String violation = null;
        if (ti == null || to == null || ti.isRecovered() || to.isRecovered()) {
            violation = "The compared types are unresolved.";
        } else if (!kt.equals()) {
            boolean pi = ti.isPrimitive();
            boolean po = to.isPrimitive();
            if (Set.of("float", "double").contains(ti.getName()) || Set.of("float", "double").contains(to.getName())) {
                violation = "Floating-point '==' differs from hash-key equality (NaN, -0.0).";
            } else if ((pi || po) && isNumericLike(ti) && isNumericLike(to)) {
                semantics = "primitive '=='";
                c.keyType = boxed(pi ? ti : to);
                c.keyTypeDescription = pi ? ti.getName() : to.getName();
                if (pi != po) {
                    semantics += " (one side is auto-unboxed)";
                }
                // the key must keep one type: mixed widths (int vs long) would need numeric promotion
                if (pi && po && !ti.getName().equals(to.getName())) {
                    violation = "The compared primitive types differ (" + ti.getName() + " vs " + to.getName()
                            + "); numeric promotion is not the same as boxed-key equality.";
                }
            } else if (ti.isEnum() && to.isEnum() && ti.isEqualTo(to)) {
                semantics = "enum identity '=='";
                c.keyType = ti.getQualifiedName();
                c.keyTypeDescription = ti.getName();
            } else {
                violation = "Reference '==' compares identity, while a HashMap compares with equals().";
            }
        } else {
            boolean outerReceiver = !kt.receiverIsInner();
            boolean symmetric = ti.getQualifiedName().equals("java.lang.String") || isBoxedType(ti) || ti.isEnum();
            if (ti.isPrimitive()) {
                violation = "equals() on a primitive does not compile.";
            } else if (outerReceiver && !(symmetric && ti.isEqualTo(to))) {
                violation = "The receiver of equals() is the outer value: a null or asymmetric equals would behave differently.";
            } else if (symmetric) {
                semantics = "equals() on " + (ti.isEnum() ? "enum " : "") + ti.getName() + " (consistent with hashCode()"
                        + (outerReceiver ? " and symmetric, so the receiver side does not matter" : "") + ")";
                c.keyType = ti.getQualifiedName();
                c.keyTypeDescription = ti.getName();
                c.outerReceiver = outerReceiver;
            } else {
                violation = "equals() of " + ti.getName() + " is not known to be consistent with hashCode().";
            }
        }
        c.preconditions.add(new Precondition("key_equality", violation == null,
                violation == null ? "Key equality is " + semantics + ", identical to HashMap key equality." : violation));

        // --- the key must be cheap, pure and unchanging
        String keyProblem = null;
        PureKey pure = new PureKey(loaded, c.json, kt.inner(), true);
        kt.inner().accept(pure);
        if (pure.problem != null) {
            keyProblem = "The element key '" + AstJson.normalize(c.json.text(kt.inner())) + "' " + pure.problem;
        } else if (!(AstJson.strip(kt.inner()) instanceof MethodInvocation) && !(AstJson.strip(kt.inner()) instanceof QualifiedName)
                && !(AstJson.strip(kt.inner()) instanceof FieldAccess) && !(AstJson.strip(kt.inner()) instanceof SimpleName)) {
            keyProblem = "The element key is not an accessor or field of the element.";
        } else {
            PureKey outerPure = new PureKey(loaded, c.json, kt.outer(), false);
            kt.outer().accept(outerPure);
            if (outerPure.problem != null) {
                keyProblem = "The lookup value '" + AstJson.normalize(c.json.text(kt.outer())) + "' " + outerPure.problem;
            }
        }
        c.preconditions.add(new Precondition("key_purity", keyProblem == null,
                keyProblem == null ? "Both key expressions are side-effect free (fields and trivial accessors only), so evaluating them once is equivalent."
                        : keyProblem));
    }

    private static boolean isNumericLike(ITypeBinding t) {
        return t.isPrimitive() && !t.getName().equals("void") || isBoxedType(t);
    }

    private static boolean isBoxedType(ITypeBinding t) {
        return Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Character",
                "java.lang.Boolean").contains(t.getQualifiedName());
    }

    private static String boxed(ITypeBinding t) {
        return switch (t.getName()) {
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "short" -> "java.lang.Short";
            case "byte" -> "java.lang.Byte";
            case "char" -> "java.lang.Character";
            case "boolean" -> "java.lang.Boolean";
            default -> t.getQualifiedName();
        };
    }

    /**
     * Accepts only literals, local variables/parameters, field reads, casts/arithmetic and calls to trivial
     * accessors (method body {@code return field;} or a record component accessor) that cannot be overridden
     * inside the project. {@code requireElementAccess} additionally demands that an element is dereferenced.
     */
    private static final class PureKey extends ASTVisitor {
        final ProjectAnalyzer.Loaded loaded;
        final AstJson json;
        String problem;

        PureKey(ProjectAnalyzer.Loaded loaded, AstJson json, Expression root, boolean element) {
            this.loaded = loaded;
            this.json = json;
        }

        @Override
        public boolean visit(MethodInvocation mi) {
            IMethodBinding b = mi.resolveMethodBinding();
            if (b == null || !isTrivialAccessor(loaded, b)) {
                problem = problem == null ? "calls '" + mi.getName().getIdentifier() + "()', which is not a trivial accessor." : problem;
            }
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation n) {
            problem = problem == null ? "creates an object." : problem;
            return false;
        }

        @Override
        public boolean visit(Assignment n) {
            problem = problem == null ? "assigns a value." : problem;
            return false;
        }

        @Override
        public boolean visit(PostfixExpression n) {
            problem = problem == null ? "modifies a variable." : problem;
            return false;
        }

        @Override
        public boolean visit(PrefixExpression n) {
            if (n.getOperator() == PrefixExpression.Operator.INCREMENT || n.getOperator() == PrefixExpression.Operator.DECREMENT) {
                problem = problem == null ? "modifies a variable." : problem;
            }
            return true;
        }

        @Override
        public boolean visit(LambdaExpression n) {
            problem = problem == null ? "contains a lambda." : problem;
            return false;
        }
    }

    /** {@code return field;} / {@code return this.field;} or an implicit record accessor, not overridable in the project. */
    private static boolean isTrivialAccessor(ProjectAnalyzer.Loaded loaded, IMethodBinding b) {
        return trivialMember(loaded, b, false);
    }

    /** {@code void setX(T v) { this.x = v; }}: stores one parameter into a field of the receiver and nothing else. */
    private static boolean isTrivialSetter(ProjectAnalyzer.Loaded loaded, IMethodBinding b) {
        return trivialMember(loaded, b, true);
    }

    private static boolean trivialMember(ProjectAnalyzer.Loaded loaded, IMethodBinding b, boolean setter) {
        IMethodBinding decl = b.getMethodDeclaration();
        MethodDeclaration md = null;
        for (AstJson peer : loaded.jsons()) {
            if (peer.unit().findDeclaringNode(decl) instanceof MethodDeclaration found) {
                md = found;
                break;
            }
        }
        if (md == null) {
            // implicit record accessor: no explicit declaration, no parameters
            return !setter && decl.getDeclaringClass().isRecord() && decl.getParameterTypes().length == 0
                    && java.util.Arrays.stream(decl.getDeclaringClass().getDeclaredFields())
                            .anyMatch(f -> f.getName().equals(decl.getName()));
        }
        if (md.getBody() == null || md.getBody().statements().size() != 1) {
            return false;
        }
        if (setter) {
            if (md.parameters().size() != 1 || !(md.getBody().statements().get(0) instanceof ExpressionStatement es)
                    || !(es.getExpression() instanceof Assignment a) || a.getOperator() != Assignment.Operator.ASSIGN
                    || !(AstJson.strip(a.getRightHandSide()) instanceof SimpleName rhs)
                    || !(rhs.resolveBinding() instanceof IVariableBinding rv) || !rv.isParameter()) {
                return false;
            }
            Expression lhs = AstJson.strip(a.getLeftHandSide());
            boolean ownField = lhs instanceof SimpleName ln && ln.resolveBinding() instanceof IVariableBinding lv && lv.isField()
                    && !Modifier.isStatic(lv.getModifiers())
                    || lhs instanceof FieldAccess lf && lf.getExpression() instanceof ThisExpression;
            if (!ownField) {
                return false;
            }
        } else {
            if (!md.parameters().isEmpty() || !(md.getBody().statements().get(0) instanceof ReturnStatement rs)
                    || rs.getExpression() == null) {
                return false;
            }
            Expression value = AstJson.strip(rs.getExpression());
            boolean field = value instanceof SimpleName n && n.resolveBinding() instanceof IVariableBinding vb && vb.isField()
                    || value instanceof FieldAccess fa && fa.getExpression() instanceof ThisExpression;
            if (!field) {
                return false;
            }
        }
        // a subclass in the project could override the accessor
        if (!Modifier.isPrivate(decl.getModifiers()) && !Modifier.isStatic(decl.getModifiers())
                && !Modifier.isFinal(decl.getModifiers()) && !Modifier.isFinal(decl.getDeclaringClass().getModifiers())
                && !decl.getDeclaringClass().isRecord()) {
            boolean[] overridden = {false};
            for (AstJson peer : loaded.jsons()) {
                peer.unit().accept(new ASTVisitor() {
                    @Override
                    public boolean visit(MethodDeclaration other) {
                        IMethodBinding ob = other.resolveBinding();
                        if (ob != null && ob != decl && ob.overrides(decl)) {
                            overridden[0] = true;
                        }
                        return !overridden[0];
                    }
                });
            }
            return !overridden[0];
        }
        return true;
    }

    // ---------- control flow of the matched body ----------

    private static final class ControlFlow extends ASTVisitor {
        boolean labeled;
        boolean looseBreak;
        boolean looseContinue;
        private int loops;

        @Override
        public boolean visit(BreakStatement n) {
            labeled |= n.getLabel() != null;
            looseBreak |= n.getLabel() == null && loops == 0 && !insideSwitch(n);
            return true;
        }

        @Override
        public boolean visit(ContinueStatement n) {
            labeled |= n.getLabel() != null;
            looseContinue |= n.getLabel() == null && loops == 0;
            return true;
        }

        private boolean insideSwitch(ASTNode n) {
            for (ASTNode p = n.getParent(); p != null; p = p.getParent()) {
                if (p instanceof SwitchStatement) {
                    return true;
                }
                if (p instanceof IfStatement || p instanceof Block || p instanceof TryStatement) {
                    continue;
                }
                if (p instanceof ForStatement || p instanceof EnhancedForStatement || p instanceof WhileStatement
                        || p instanceof DoStatement) {
                    return false;
                }
            }
            return false;
        }

        @Override
        public boolean visit(ForStatement n) {
            loops++;
            return true;
        }

        @Override
        public void endVisit(ForStatement n) {
            loops--;
        }

        @Override
        public boolean visit(EnhancedForStatement n) {
            loops++;
            return true;
        }

        @Override
        public void endVisit(EnhancedForStatement n) {
            loops--;
        }

        @Override
        public boolean visit(WhileStatement n) {
            loops++;
            return true;
        }

        @Override
        public void endVisit(WhileStatement n) {
            loops--;
        }

        @Override
        public boolean visit(DoStatement n) {
            loops++;
            return true;
        }

        @Override
        public void endVisit(DoStatement n) {
            loops--;
        }
    }

    // ---------- effects of the enclosing loop ----------

    /**
     * Visits the whole enclosing loop and records the first thing that could modify the scanned collection or a
     * key: calls outside a small benign set, constructors of project classes, writes to anything but locals,
     * lambdas and local/anonymous classes. The scan itself ({@code inner}) is skipped.
     */
    private static final class Effects extends ASTVisitor {
        final ProjectAnalyzer.Loaded loaded;
        final IVariableBinding collection;
        final EnhancedForStatement inner;
        final MethodDeclaration method;
        String problem;

        Effects(ProjectAnalyzer.Loaded loaded, IVariableBinding collection, EnhancedForStatement inner, MethodDeclaration method) {
            this.loaded = loaded;
            this.collection = collection;
            this.inner = inner;
            this.method = method;
        }

        private void problem(String text) {
            if (problem == null) {
                problem = text;
            }
        }

        @Override
        public boolean visit(LambdaExpression n) {
            problem("The enclosing loop contains a lambda, which may run later and modify state.");
            return false;
        }

        @Override
        public boolean visit(AnonymousClassDeclaration n) {
            problem("The enclosing loop contains an anonymous class.");
            return false;
        }

        @Override
        public boolean visit(TypeDeclarationStatement n) {
            problem("The enclosing loop declares a local class.");
            return false;
        }

        @Override
        public boolean visit(Assignment n) {
            checkTarget(n.getLeftHandSide());
            return true;
        }

        @Override
        public boolean visit(PostfixExpression n) {
            checkTarget(n.getOperand());
            return true;
        }

        @Override
        public boolean visit(PrefixExpression n) {
            if (n.getOperator() == PrefixExpression.Operator.INCREMENT || n.getOperator() == PrefixExpression.Operator.DECREMENT) {
                checkTarget(n.getOperand());
            }
            return true;
        }

        private void checkTarget(Expression target) {
            Expression t = AstJson.strip(target);
            if (t instanceof SimpleName n && n.resolveBinding() instanceof IVariableBinding vb && !vb.isField()) {
                if (collection != null && vb.isEqualTo(collection)) {
                    problem("The enclosing loop reassigns the scanned collection variable.");
                }
                return;
            }
            problem("The enclosing loop writes to a field or array element (" + AstJson.normalize(t.toString()) + "), which could change a key or the collection.");
        }

        @Override
        public boolean visit(ClassInstanceCreation n) {
            ITypeBinding t = n.resolveTypeBinding();
            String name = t == null ? "" : t.getErasure().getQualifiedName();
            boolean jdk = name.startsWith("java.lang.") || name.startsWith("java.util.") || name.startsWith("java.math.");
            boolean storesOnly = !jdk && ConstructorPurity.isTrivial(n, loaded.jsons().stream().map(AstJson::unit).toList());
            if (!(jdk || storesOnly) || n.getAnonymousClassDeclaration() != null) {
                problem("The enclosing loop constructs " + (t == null ? "an object" : t.getName())
                        + ", whose constructor may run arbitrary code.");
            }
            for (Object a : n.arguments()) {
                if (mentionsCollection((Expression) a)) {
                    problem("The scanned collection is passed to a constructor in the enclosing loop.");
                }
            }
            return true;
        }

        @Override
        public boolean visit(MethodInvocation mi) {
            IMethodBinding b = mi.resolveMethodBinding();
            if (b == null) {
                problem("The enclosing loop calls '" + mi.getName().getIdentifier() + "()', which could not be resolved.");
                return true;
            }
            if (collection != null) {
                if (mi.getExpression() instanceof SimpleName rn && rn.resolveBinding() instanceof IVariableBinding rv
                        && rv.isEqualTo(collection) && !isReadOnly(b)) {
                    problem("The enclosing loop modifies the scanned collection via " + mi.getName().getIdentifier() + "().");
                }
                for (Object a : mi.arguments()) {
                    if (mentionsCollection((Expression) a) && !isBenignLibrary(b)) {
                        problem("The scanned collection is passed to '" + mi.getName().getIdentifier() + "()' in the enclosing loop.");
                    }
                }
            }
            if (!isBenign(mi, b)) {
                problem("The enclosing loop calls '" + mi.getName().getIdentifier()
                        + "()', which is not known to leave the scanned collection and keys unchanged.");
            }
            return true;
        }

        private boolean mentionsCollection(Expression e) {
            return collection != null && mentions(e, collection);
        }

        private boolean isReadOnly(IMethodBinding b) {
            return Set.of("get", "size", "isEmpty", "contains", "indexOf", "iterator", "stream").contains(b.getName());
        }

        private boolean isBenignLibrary(IMethodBinding b) {
            String decl = b.getDeclaringClass().getErasure().getQualifiedName();
            return decl.equals("java.util.Objects") || decl.equals("java.lang.String")
                    || decl.equals("java.io.PrintStream") && Set.of("println", "print", "printf").contains(b.getName());
        }

        private boolean isBenign(MethodInvocation mi, IMethodBinding b) {
            if (isTrivialAccessor(loaded, b)) {
                return true;
            }
            // a plain setter on an object this loop itself allocated cannot change the scanned collection or its keys
            if (mi.getExpression() != null && isTrivialSetter(loaded, b) && freshLocal(mi.getExpression())) {
                return true;
            }
            String decl = b.getDeclaringClass().getErasure().getQualifiedName();
            String name = b.getName();
            switch (decl) {
                case "java.lang.Math", "java.lang.StrictMath", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
                     "java.lang.Boolean", "java.lang.Character", "java.util.Objects", "java.lang.String" -> {
                    return true;
                }
                case "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.AbstractStringBuilder" -> {
                    return true;
                }
                case "java.io.PrintStream" -> {
                    return Set.of("println", "print", "printf", "format").contains(name);
                }
                default -> {
                }
            }
            ITypeBinding receiverType = mi.getExpression() == null ? null : mi.getExpression().resolveTypeBinding();
            if (receiverType == null || !decl.startsWith("java.util.")) {
                return false;
            }
            boolean collectionLike = isCollectionFamily(decl);
            if (!collectionLike) {
                return false;
            }
            if (isReadOnlyCollectionOp(name)) {
                return true;
            }
            if (!isMutatingCollectionOp(name)) {
                return false;
            }
            // a mutator is benign if its receiver cannot be the scanned collection
            if (!couldAlias(receiverType)) {
                return true;
            }
            return freshLocal(mi.getExpression());
        }

        private boolean couldAlias(ITypeBinding receiverType) {
            if (collection == null) {
                return true;
            }
            ITypeBinding scanned = collection.getType();
            if (scanned.isArray()) {
                return false;
            }
            ITypeBinding a = receiverType.getErasure();
            ITypeBinding b = scanned.getErasure();
            return a.isAssignmentCompatible(b) || b.isAssignmentCompatible(a);
        }

        /** A local variable of this method that is initialised with `new` and never reassigned: nothing else can alias it. */
        private boolean freshLocal(Expression e) {
            if (!(AstJson.strip(e) instanceof SimpleName n) || !(n.resolveBinding() instanceof IVariableBinding vb) || vb.isField()
                    || vb.isParameter()) {
                return false;
            }
            boolean[] ok = {false};
            int[] writes = {0};
            method.getBody().accept(new ASTVisitor() {
                @Override
                public boolean visit(VariableDeclarationFragment f) {
                    if (vb.isEqualTo(f.resolveBinding()) && f.getInitializer() instanceof ClassInstanceCreation) {
                        ok[0] = true;
                    }
                    return true;
                }

                @Override
                public boolean visit(Assignment a) {
                    if (AstJson.strip(a.getLeftHandSide()) instanceof SimpleName an && vb.isEqualTo(an.resolveBinding())
                            && !(a.getOperator() == Assignment.Operator.ASSIGN
                            && AstJson.strip(a.getRightHandSide()) instanceof ClassInstanceCreation)) {
                        writes[0]++;
                    }
                    return true;
                }
            });
            return ok[0] && writes[0] == 0;
        }

        private static boolean isCollectionFamily(String decl) {
            return Set.of("java.util.List", "java.util.ArrayList", "java.util.LinkedList", "java.util.Collection",
                    "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet", "java.util.SortedSet",
                    "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.SortedMap",
                    "java.util.Deque", "java.util.ArrayDeque", "java.util.Queue", "java.util.AbstractList",
                    "java.util.AbstractMap", "java.util.AbstractSet", "java.util.AbstractCollection").contains(decl);
        }

        private static boolean isReadOnlyCollectionOp(String name) {
            return Set.of("get", "size", "isEmpty", "contains", "containsKey", "containsValue", "getOrDefault", "indexOf",
                    "peek", "first", "last").contains(name);
        }

        private static boolean isMutatingCollectionOp(String name) {
            return Set.of("add", "put", "putIfAbsent", "remove", "addAll", "push", "offer", "poll", "pop", "set", "clear")
                    .contains(name);
        }
    }

    // ---------- proposal description ----------

    private static List<Map<String, Object>> assumptions(Candidate c) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(assumption(A_NULL_TIMING, "If an element of '" + c.collectionName + "' is null, or its key accessor throws, the original code "
                + "fails only when the scan reaches that element. The optimized code reads every element's key while building the "
                + "index, so it may fail earlier or even when the enclosing loop would not run at all."));
        if (c.outerReceiver) {
            list.add(assumption(A_OUTER_KEY, "The key '" + AstJson.normalize(c.json.text(c.outerKey)) + "' is never null when "
                    + "'" + c.collectionName + "' is scanned: the original calls equals() on it and would throw a "
                    + "NullPointerException, while the index lookup treats null as a plain miss."));
        }
        list.add(assumption(A_THREADS, "No other thread modifies '" + c.collectionName
                + "' or the element keys while the enclosing loop runs."));
        return list;
    }

    private static Map<String, Object> assumption(String id, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("text", text);
        return m;
    }

    private static Map<String, Object> describe(Candidate c, Map<String, Object> analysis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("rule", RULE);
        m.put("title", "Replace the nested scan of '" + c.collectionName + "' with a HashMap index");
        boolean applicable = c.applicable();
        m.put("applicability", applicable ? "applicable" : "not_applicable");
        List<String> reasons = new ArrayList<>();
        for (Precondition p : c.preconditions) {
            if (!p.satisfied()) {
                reasons.add(p.detail());
            }
        }
        m.put("not_applicable_reasons", reasons);
        m.put("method_id", c.methodId);
        m.put("file", c.json.file());
        m.put("affected_files", List.of(c.json.file()));
        m.put("shape", c.allMatches ? "all_matches" : "first_match");
        m.put("outer_loop", c.json.range(c.outerLoop));
        m.put("inner_loop", c.json.range(c.inner));
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("element_expression", AstJson.normalize(c.json.text(c.innerKey)));
        key.put("lookup_expression", AstJson.normalize(c.json.text(c.outerKey)));
        key.put("type", c.keyTypeDescription);
        key.put("equality", c.equality);
        m.put("key", key);
        List<Object> pre = new ArrayList<>();
        for (Precondition p : c.preconditions) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.id());
            pm.put("status", p.satisfied() ? "satisfied" : "violated");
            pm.put("detail", p.detail());
            pre.add(pm);
        }
        m.put("preconditions", pre);
        m.put("assumptions", applicable ? assumptions(c) : new ArrayList<>());

        Map<String, Object> complexity = new LinkedHashMap<>();
        Object before = methodComplexity(analysis, c.methodId);
        complexity.put("method_before", before);
        complexity.put("scan_before", "O(n*m)");
        complexity.put("scan_after", c.allMatches ? "O(n + m + k)" : "O(n + m)");
        complexity.put("note", "n = iterations of the enclosing loop, m = elements of '" + c.collectionName
                + "'" + (c.allMatches ? ", k = matching pairs (the matched body runs for each, as before)" : "")
                + ". HashMap get/put are O(1) in expectation for well-distributed hash codes, not a worst-case bound. "
                + "Only the replaced scan changes; the method's other costs stay.");
        m.put("complexity", complexity);
        m.put("summary", applicable
                ? "Build a HashMap<" + c.keyTypeDescription + ", " + (c.allMatches ? "List<" : "") + "element" + (c.allMatches ? ">" : "")
                        + "> from '" + c.collectionName + "' once before the enclosing loop and look up '"
                        + AstJson.normalize(c.json.text(c.outerKey)) + "' instead of scanning."
                : "Not applicable: " + String.join(" ", reasons));
        return m;
    }

    @SuppressWarnings("unchecked")
    static Object methodComplexity(Object tree, String methodId) {
        if (methodId == null) {
            return null;
        }
        if (tree instanceof Map<?, ?> map) {
            if (methodId.equals(map.get("id")) && map.get("analysis") instanceof Map<?, ?> a && a.get("complexity") instanceof Map<?, ?> c) {
                return c.get("time");
            }
            for (Object child : map.values()) {
                Object hit = methodComplexity(child, methodId);
                if (hit != null) {
                    return hit;
                }
            }
        } else if (tree instanceof List<?> list) {
            for (Object child : list) {
                Object hit = methodComplexity(child, methodId);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    // ---------- apply ----------

    private static Map<String, Object> apply(Path root, List<String> files, String level, String proposalId,
            Set<String> acknowledged, boolean preview, Path outputDir) throws IOException {
        Discovered d = scan(root, files, level);
        if (d.failure != null) {
            return d.failure;
        }
        Candidate chosen = null;
        for (Candidate c : d.candidates) {
            if (c.id.equals(proposalId)) {
                chosen = c;
            }
        }
        if (chosen == null && StringBuilderRule.owns(d.loaded, proposalId)) {
            return StringBuilderRule.apply(d.loaded, d.analysis, root, files, level, proposalId, acknowledged, preview, outputDir);
        }
        if (chosen == null && ContainsToSetRule.owns(d.loaded, proposalId)) {
            return ContainsToSetRule.apply(d.loaded, d.analysis, root, files, level, proposalId, acknowledged, preview, outputDir);
        }
        if (chosen == null) {
            return failure("proposal_not_found", "No optimization proposal with this id exists in this revision.");
        }
        if (!chosen.applicable()) {
            return failure("not_applicable", "The proposal is not applicable: "
                    + ((List<?>) describe(chosen, d.analysis).get("not_applicable_reasons")).stream()
                            .map(Object::toString).reduce((x, y) -> x + " " + y).orElse(""));
        }
        Set<String> required = new TreeSet<>();
        for (Map<String, Object> a : assumptions(chosen)) {
            required.add((String) a.get("id"));
        }
        if (!preview && !acknowledged.containsAll(required)) {
            return failure("assumptions_not_acknowledged", "The caller must acknowledge every assumption of this proposal: " + required);
        }

        String source = chosen.json.sourceText();
        String rewritten = rewrite(d.loaded, chosen, source);

        // write the full candidate tree: unchanged files are copies, the changed file is the rewrite
        List<String> paths = new ArrayList<>(new TreeSet<>(files));
        for (String path : paths) {
            Path target = outputDir.resolve(path);
            Files.createDirectories(target.getParent());
            if (path.equals(chosen.json.file())) {
                Files.writeString(target, rewritten, StandardCharsets.UTF_8);
            } else {
                Files.copy(root.resolve(path), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Map<String, Object> candidateAnalysis = ProjectAnalyzer.analyze(outputDir, paths, level);
        List<String> introduced = newErrors(d.analysis, candidateAnalysis);
        if ("failed".equals(candidateAnalysis.get("status")) || !introduced.isEmpty()) {
            return failure("transformation_invalid", "The rewritten source does not analyse cleanly: "
                    + (introduced.isEmpty() ? "analysis failed" : String.join("; ", introduced)));
        }
        Object after = methodComplexity(candidateAnalysis, chosen.methodId);
        if (chosen.methodId != null && after == null) {
            return failure("transformation_invalid", "The rewritten project no longer contains the analysed method.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "completed");
        result.put("proposal", describe(chosen, d.analysis));
        result.put("changed_files", List.of(chosen.json.file()));
        result.put("method_complexity_before", methodComplexity(d.analysis, chosen.methodId));
        result.put("method_complexity_after", after);
        result.put("candidate_status", candidateAnalysis.get("status"));
        return result;
    }

    /** Error diagnostics of the candidate that the baseline did not have (compared by file, code and message). */
    @SuppressWarnings("unchecked")
    static List<String> newErrors(Map<String, Object> baseline, Map<String, Object> candidate) {
        Set<String> known = new HashSet<>();
        for (Object o : (List<Object>) baseline.getOrDefault("diagnostics", List.of())) {
            Map<String, Object> d = (Map<String, Object>) o;
            if ("error".equals(d.get("severity"))) {
                known.add(errorKey(d));
            }
        }
        List<String> fresh = new ArrayList<>();
        for (Object o : (List<Object>) candidate.getOrDefault("diagnostics", List.of())) {
            Map<String, Object> d = (Map<String, Object>) o;
            if ("error".equals(d.get("severity")) && !known.contains(errorKey(d))) {
                fresh.add(d.get("message") + " (" + ((Map<String, Object>) d.get("source_range")).get("file") + ")");
            }
        }
        return fresh;
    }

    @SuppressWarnings("unchecked")
    private static String errorKey(Map<String, Object> d) {
        return ((Map<String, Object>) d.get("source_range")).get("file") + "|" + d.get("code") + "|" + d.get("message");
    }

    // ---------- the rewrite ----------

    private static String rewrite(ProjectAnalyzer.Loaded loaded, Candidate c, String source) {
        CompilationUnit cu = c.json.unit();
        AST ast = cu.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        EnhancedForStatement inner = c.inner;
        SingleVariableDeclaration element = inner.getParameter();
        String elementName = element.getName().getIdentifier();

        Set<String> taken = new HashSet<>();
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName n) {
                taken.add(n.getIdentifier());
                return true;
            }
        });
        String keyName = AstJson.keyNameFor(c.innerKey);
        String indexName = unique(taken, c.collectionName.replaceAll("[^A-Za-z0-9_]", "") + "By" + keyName);

        Imports imports = new Imports(cu, rewrite, loaded);
        String mapType = imports.use("java.util.Map");
        String hashMapType = imports.use("java.util.HashMap");
        String listType = c.allMatches ? imports.use("java.util.List") : null;
        String arrayListType = c.allMatches ? imports.use("java.util.ArrayList") : null;

        // Map<K, E> or Map<K, List<E>>
        Type valueType = (Type) ASTNode.copySubtree(ast, element.getType());
        if (element.getExtraDimensions() > 0 || element.getType().isPrimitiveType()) {
            throw new IllegalStateException("element type is not a reference type");
        }
        Type indexValue = valueType;
        if (c.allMatches) {
            ParameterizedType lt = ast.newParameterizedType(ast.newSimpleType(ast.newName(listType)));
            lt.typeArguments().add(ASTNode.copySubtree(ast, element.getType()));
            indexValue = lt;
        }
        ParameterizedType mapDeclType = ast.newParameterizedType(ast.newSimpleType(ast.newName(mapType)));
        mapDeclType.typeArguments().add(ast.newSimpleType(ast.newName(
                c.keyType.startsWith("java.lang.") && c.keyType.indexOf('.', 10) < 0 ? c.keyType.substring(10) : c.keyType)));
        mapDeclType.typeArguments().add(indexValue);

        VariableDeclarationFragment indexFragment = ast.newVariableDeclarationFragment();
        indexFragment.setName(ast.newSimpleName(indexName));
        ClassInstanceCreation newMap = ast.newClassInstanceCreation();
        newMap.setType(ast.newParameterizedType(ast.newSimpleType(ast.newName(hashMapType))));
        indexFragment.setInitializer(newMap);
        VariableDeclarationStatement indexDecl = ast.newVariableDeclarationStatement(indexFragment);
        indexDecl.setType(mapDeclType);

        // for (E e : collection) { index.putIfAbsent(key, e);  |  index.computeIfAbsent(key, k -> new ArrayList<>()).add(e); }
        EnhancedForStatement build = ast.newEnhancedForStatement();
        build.setParameter((SingleVariableDeclaration) ASTNode.copySubtree(ast, element));
        build.setExpression((Expression) ASTNode.copySubtree(ast, inner.getExpression()));
        MethodInvocation fill;
        if (c.allMatches) {
            String lambdaParam = unique(taken, "ignored");
            MethodInvocation compute = ast.newMethodInvocation();
            compute.setExpression(ast.newSimpleName(indexName));
            compute.setName(ast.newSimpleName("computeIfAbsent"));
            compute.arguments().add(ASTNode.copySubtree(ast, c.innerKey));
            LambdaExpression lambda = ast.newLambdaExpression();
            lambda.setParentheses(false);
            VariableDeclarationFragment lp = ast.newVariableDeclarationFragment();
            lp.setName(ast.newSimpleName(lambdaParam));
            lambda.parameters().add(lp);
            ClassInstanceCreation newList = ast.newClassInstanceCreation();
            newList.setType(ast.newParameterizedType(ast.newSimpleType(ast.newName(arrayListType))));
            lambda.setBody(newList);
            compute.arguments().add(lambda);
            fill = ast.newMethodInvocation();
            fill.setExpression(compute);
            fill.setName(ast.newSimpleName("add"));
            fill.arguments().add(ast.newSimpleName(elementName));
        } else {
            fill = ast.newMethodInvocation();
            fill.setExpression(ast.newSimpleName(indexName));
            fill.setName(ast.newSimpleName("putIfAbsent"));
            fill.arguments().add(ASTNode.copySubtree(ast, c.innerKey));
            fill.arguments().add(ast.newSimpleName(elementName));
        }
        Block buildBody = ast.newBlock();
        buildBody.statements().add(ast.newExpressionStatement(fill));
        build.setBody(buildBody);

        // insert both before the enclosing loop
        Block outerParent = (Block) c.outerLoop.getParent();
        ListRewrite outerList = rewrite.getListRewrite(outerParent, Block.STATEMENTS_PROPERTY);
        outerList.insertBefore(indexDecl, c.outerLoop, null);
        outerList.insertBefore(build, c.outerLoop, null);

        // replace the scan
        MethodInvocation lookup = ast.newMethodInvocation();
        lookup.setExpression(ast.newSimpleName(indexName));
        lookup.setName(ast.newSimpleName("get"));
        lookup.arguments().add(ASTNode.copySubtree(ast, c.outerKey));

        Statement thenOriginal = c.test.getThenStatement();
        Block thenBlock = thenOriginal instanceof Block tb ? tb : null;
        Statement replacementTail;
        List<Statement> replacement = new ArrayList<>();
        if (c.allMatches) {
            String bucket = unique(taken, elementName + "Matches");
            VariableDeclarationFragment bf = ast.newVariableDeclarationFragment();
            bf.setName(ast.newSimpleName(bucket));
            bf.setInitializer(lookup);
            VariableDeclarationStatement bd = ast.newVariableDeclarationStatement(bf);
            ParameterizedType bt = ast.newParameterizedType(ast.newSimpleType(ast.newName(listType)));
            bt.typeArguments().add(ASTNode.copySubtree(ast, element.getType()));
            bd.setType(bt);
            replacement.add(bd);

            EnhancedForStatement each = ast.newEnhancedForStatement();
            each.setParameter((SingleVariableDeclaration) ASTNode.copySubtree(ast, element));
            each.setExpression(ast.newSimpleName(bucket));
            each.setBody(movedBody(rewrite, ast, thenOriginal, thenBlock, false));
            IfStatement guard = ast.newIfStatement();
            InfixExpression notNull = ast.newInfixExpression();
            notNull.setLeftOperand(ast.newSimpleName(bucket));
            notNull.setOperator(InfixExpression.Operator.NOT_EQUALS);
            notNull.setRightOperand(ast.newNullLiteral());
            guard.setExpression(notNull);
            Block guarded = ast.newBlock();
            guarded.statements().add(each);
            guard.setThenStatement(guarded);
            replacementTail = guard;
        } else {
            VariableDeclarationFragment ef = ast.newVariableDeclarationFragment();
            ef.setName(ast.newSimpleName(elementName));
            ef.setInitializer(lookup);
            VariableDeclarationStatement ed = ast.newVariableDeclarationStatement(ef);
            ed.setType((Type) ASTNode.copySubtree(ast, element.getType()));
            replacement.add(ed);

            IfStatement guard = ast.newIfStatement();
            InfixExpression notNull = ast.newInfixExpression();
            notNull.setLeftOperand(ast.newSimpleName(elementName));
            notNull.setOperator(InfixExpression.Operator.NOT_EQUALS);
            notNull.setRightOperand(ast.newNullLiteral());
            guard.setExpression(notNull);
            guard.setThenStatement(movedBody(rewrite, ast, thenOriginal, thenBlock, true));
            replacementTail = guard;
        }
        replacement.add(replacementTail);

        ASTNode innerParent = inner.getParent();
        boolean wrap = !(innerParent instanceof Block) || declaresName(innerParent, inner, elementName, c.allMatches ? elementName + "Matches" : null);
        if (innerParent instanceof Block pb && !wrap) {
            ListRewrite list = rewrite.getListRewrite(pb, Block.STATEMENTS_PROPERTY);
            list.replace(inner, replacement.get(0), null);
            list.insertAfter(replacement.get(1), replacement.get(0), null);
        } else {
            Block block = ast.newBlock();
            block.statements().addAll(replacement);
            rewrite.replace(inner, block, null);
        }

        Document document = new Document(source);
        Map<String, String> options = JavaCore.getOptions();
        applyIndentation(options, source);
        try {
            TextEdit edit = rewrite.rewriteAST(document, options);
            edit.apply(document);
        } catch (Exception e) {
            throw new IllegalStateException("could not apply the rewrite: " + e, e);
        }
        return document.get();
    }

    /** The matched body: the original block (minus its trailing break for first-match), or a block around a lone statement. */
    private static Statement movedBody(ASTRewrite rewrite, AST ast, Statement original, Block originalBlock, boolean dropBreak) {
        if (originalBlock != null) {
            if (dropBreak) {
                List<?> statements = originalBlock.statements();
                rewrite.getListRewrite(originalBlock, Block.STATEMENTS_PROPERTY)
                        .remove((ASTNode) statements.get(statements.size() - 1), null);
            }
            return (Statement) rewrite.createMoveTarget(originalBlock);
        }
        Block wrapper = ast.newBlock();
        wrapper.statements().add(rewrite.createMoveTarget(original));
        return wrapper;
    }

    private static boolean declaresName(ASTNode parent, ASTNode except, String... names) {
        boolean[] clash = {false};
        Set<String> wanted = new HashSet<>();
        for (String n : names) {
            if (n != null) {
                wanted.add(n);
            }
        }
        parent.accept(new ASTVisitor() {
            @Override
            public boolean preVisit2(ASTNode node) {
                return node != except;
            }

            @Override
            public boolean visit(VariableDeclarationFragment f) {
                clash[0] |= wanted.contains(f.getName().getIdentifier());
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration f) {
                clash[0] |= wanted.contains(f.getName().getIdentifier());
                return true;
            }
        });
        return clash[0];
    }

    private static String unique(Set<String> taken, String base) {
        String name = base.isEmpty() ? "index" : Character.toLowerCase(base.charAt(0)) + base.substring(1);
        String candidate = name;
        for (int i = 2; taken.contains(candidate); i++) {
            candidate = name + i;
        }
        taken.add(candidate);
        return candidate;
    }

    /** Makes inserted code follow the file's indentation (tabs, or the width of its first indented line). */
    static void applyIndentation(Map<String, String> options, String source) {
        boolean tabs = false;
        int width = 4;
        int sampled = 0;
        for (String line : source.split("\n", 400)) {
            if (line.startsWith("\t")) {
                tabs = true;
                break;
            }
            if (line.startsWith("  ") && sampled < 40) {
                int n = 0;
                while (n < line.length() && line.charAt(n) == ' ') {
                    n++;
                }
                if (n > 0 && n < width * 2 && sampled == 0) {
                    width = n % 4 == 0 ? 4 : n;
                }
                sampled++;
            }
        }
        options.put("org.eclipse.jdt.core.formatter.tabulation.char", tabs ? "tab" : "space");
        options.put("org.eclipse.jdt.core.formatter.tabulation.size", Integer.toString(width));
        options.put("org.eclipse.jdt.core.formatter.indentation.size", Integer.toString(width));
    }

    /** Adds {@code java.util} imports only when needed; falls back to the qualified name when a simple name is taken. */
    private static final class Imports {
        private final CompilationUnit cu;
        private final ASTRewrite rewrite;
        private final Set<String> imported = new HashSet<>();
        private final Set<String> simpleNames = new HashSet<>();
        private final boolean javaUtilWildcard;
        private final Set<String> localTypes = new HashSet<>();
        private final Set<String> added = new HashSet<>();

        Imports(CompilationUnit cu, ASTRewrite rewrite, ProjectAnalyzer.Loaded loaded) {
            this.cu = cu;
            this.rewrite = rewrite;
            boolean wildcard = false;
            for (Object o : cu.imports()) {
                ImportDeclaration imp = (ImportDeclaration) o;
                if (imp.isStatic()) {
                    continue;
                }
                String name = imp.getName().getFullyQualifiedName();
                if (imp.isOnDemand()) {
                    wildcard |= name.equals("java.util");
                } else {
                    imported.add(name);
                    simpleNames.add(name.substring(name.lastIndexOf('.') + 1));
                }
            }
            this.javaUtilWildcard = wildcard;
            // types of the same package (any analysed file) shadow on-demand imports
            for (AstJson peer : loaded.jsons()) {
                String pkg = peer.unit().getPackage() == null ? "" : peer.unit().getPackage().getName().getFullyQualifiedName();
                String own = cu.getPackage() == null ? "" : cu.getPackage().getName().getFullyQualifiedName();
                if (pkg.equals(own)) {
                    for (Object t : peer.unit().types()) {
                        localTypes.add(((AbstractTypeDeclaration) t).getName().getIdentifier());
                    }
                }
            }
            cu.accept(new ASTVisitor() {
                @Override
                public boolean visit(TypeDeclaration t) {
                    localTypes.add(t.getName().getIdentifier());
                    return true;
                }
            });
        }

        String use(String qualified) {
            String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
            if (imported.contains(qualified) && !localTypes.contains(simple)) {
                return simple;
            }
            if (localTypes.contains(simple) || simpleNames.contains(simple) && !imported.contains(qualified)) {
                return qualified;
            }
            if (javaUtilWildcard) {
                return simple;
            }
            if (added.add(qualified)) {
                AST ast = cu.getAST();
                ImportDeclaration imp = ast.newImportDeclaration();
                imp.setName(ast.newName(qualified));
                rewrite.getListRewrite(cu, CompilationUnit.IMPORTS_PROPERTY).insertLast(imp, null);
            }
            return simple;
        }
    }
}
