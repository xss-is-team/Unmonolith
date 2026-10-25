package org.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/**
 * Optimization rule {@code contains_to_hashset}: {@code list.contains(x)} inside a loop scans the list on every
 * iteration; a {@code HashSet} built once before the loop answers in O(1).
 *
 * <p>The set sits before the outermost loop in which the list is only read (contains/get/size/isEmpty/indexOf or
 * iterated). The element type must have consistent {@code equals}/{@code hashCode}: {@code String}, a boxed number,
 * {@code Character}, {@code Boolean} or an enum. A list that is a parameter or escapes the method could be changed
 * through an alias, which needs an acknowledged assumption (and no calls into project code in the loop); a list that
 * is not provably non-null needs one too, because the set is built even if the loop would not run.
 */
final class ContainsToSetRule {
    static final String RULE = "contains_to_hashset";
    private static final String A_ALIAS = "list_not_changed_through_alias";
    private static final String A_NON_NULL = "list_not_null_before_loop";
    private static final Set<String> READ_ONLY = Set.of("contains", "get", "size", "isEmpty", "indexOf", "lastIndexOf", "containsAll");
    private static final Set<String> SAFE_ELEMENTS = Set.of("java.lang.String", "java.lang.Integer", "java.lang.Long",
            "java.lang.Short", "java.lang.Byte", "java.lang.Character", "java.lang.Boolean");

    private ContainsToSetRule() {
    }

    private record Precondition(String id, boolean satisfied, String detail) {
    }

    private static final class Candidate {
        String id;
        AstJson json;
        MethodDeclaration method;
        String methodId;
        IVariableBinding list;
        String listName;
        String setName;
        String elementType;
        Statement loop;
        final List<MethodInvocation> calls = new ArrayList<>();
        final List<Precondition> preconditions = new ArrayList<>();
        boolean aliasable;
        boolean nonNullProven;

        boolean applicable() {
            return preconditions.stream().allMatch(Precondition::satisfied);
        }
    }

    // ---------- discovery ----------

    static List<Object> discover(ProjectAnalyzer.Loaded loaded, Map<String, Object> analysis) {
        List<Object> out = new ArrayList<>();
        for (Candidate c : scan(loaded)) {
            out.add(describe(c, analysis));
        }
        return out;
    }

    static boolean owns(ProjectAnalyzer.Loaded loaded, String proposalId) {
        return scan(loaded).stream().anyMatch(c -> c.id.equals(proposalId));
    }

    private static List<Candidate> scan(ProjectAnalyzer.Loaded loaded) {
        List<Candidate> all = new ArrayList<>();
        for (AstJson json : loaded.jsons()) {
            json.unit().accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration md) {
                    ProjectGraph.Link link = loaded.links().get(md);
                    if (md.getBody() != null && link != null) {
                        scanMethod(loaded, json, md, link.methodId(), all);
                    }
                    return true;
                }
            });
        }
        return all;
    }

    private static boolean isListType(ITypeBinding t) {
        if (t == null) {
            return false;
        }
        ITypeBinding erasure = t.getErasure();
        String name = erasure.getQualifiedName();
        return name.equals("java.util.List") || name.equals("java.util.ArrayList") || name.equals("java.util.LinkedList");
    }

    private static boolean safeElement(ITypeBinding t) {
        return t != null && (SAFE_ELEMENTS.contains(t.getErasure().getQualifiedName()) || t.isEnum());
    }

    private static void scanMethod(ProjectAnalyzer.Loaded loaded, AstJson json, MethodDeclaration md, String methodId, List<Candidate> out) {
        // every list.contains(arg) with a local list or parameter as receiver
        Map<IVariableBinding, List<MethodInvocation>> byList = new LinkedHashMap<>();
        md.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation mi) {
                if (mi.getName().getIdentifier().equals("contains") && mi.arguments().size() == 1
                        && mi.getExpression() instanceof SimpleName receiver
                        && receiver.resolveBinding() instanceof IVariableBinding v && !v.isField() && isListType(v.getType())) {
                    byList.computeIfAbsent(v, k -> new ArrayList<>()).add(mi);
                }
                return true;
            }
        });
        for (Map.Entry<IVariableBinding, List<MethodInvocation>> entry : byList.entrySet()) {
            IVariableBinding list = entry.getKey();
            Map<Statement, List<MethodInvocation>> perLoop = new LinkedHashMap<>();
            for (MethodInvocation call : entry.getValue()) {
                Statement loop = outermostReadOnlyLoop(md, call, list);
                if (loop != null) {
                    perLoop.computeIfAbsent(loop, k -> new ArrayList<>()).add(call);
                }
            }
            for (Map.Entry<Statement, List<MethodInvocation>> loop : perLoop.entrySet()) {
                out.add(candidate(loaded, json, md, methodId, list, loop.getKey(), loop.getValue()));
            }
        }
    }

    /** The outermost loop around {@code call} in which the list is only read; null if there is none or the list is declared inside it. */
    private static Statement outermostReadOnlyLoop(MethodDeclaration md, MethodInvocation call, IVariableBinding list) {
        CompilationUnit unit = (CompilationUnit) md.getRoot();
        ASTNode declaration = unit.findDeclaringNode(list);
        Statement best = null;
        for (ASTNode p = call.getParent(); p != null && p != md; p = p.getParent()) {
            if (p instanceof LambdaExpression || p instanceof AnonymousClassDeclaration) {
                break;
            }
            if (StringBuilderRule.isLoop(p)) {
                boolean declaredInside = false;
                for (ASTNode d = declaration; d != null; d = d.getParent()) {
                    if (d == p) {
                        declaredInside = true;
                    }
                }
                if (declaredInside) {
                    break;
                }
                if (nonReadUses((Statement) p, list).isEmpty()) {
                    best = (Statement) p;
                }
            }
        }
        return best;
    }

    /** Uses of {@code list} inside {@code loop} that are not plain reads. */
    private static List<String> nonReadUses(Statement loop, IVariableBinding list) {
        List<String> uses = new ArrayList<>();
        loop.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName n) {
                IBinding b = n.resolveBinding();
                if (n.isDeclaration() || b == null || !b.isEqualTo(list)) {
                    return true;
                }
                ASTNode parent = n.getParent();
                boolean read = parent instanceof MethodInvocation mi && mi.getExpression() == n
                        && READ_ONLY.contains(mi.getName().getIdentifier())
                        || parent instanceof EnhancedForStatement ef && ef.getExpression() == n;
                if (!read) {
                    uses.add("line " + ((CompilationUnit) n.getRoot()).getLineNumber(n.getStartPosition()));
                }
                return true;
            }
        });
        return uses;
    }

    private static Candidate candidate(ProjectAnalyzer.Loaded loaded, AstJson json, MethodDeclaration md, String methodId, IVariableBinding list, Statement loop,
            List<MethodInvocation> calls) {
        Candidate c = new Candidate();
        c.json = json;
        c.method = md;
        c.methodId = methodId;
        c.list = list;
        c.listName = list.getName();
        c.loop = loop;
        c.calls.addAll(calls);
        ITypeBinding[] arguments = list.getType().getTypeArguments();
        ITypeBinding element = arguments.length == 1 ? arguments[0] : null;
        c.elementType = element == null ? "?" : element.getName();

        Set<String> taken = new HashSet<>();
        md.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName n) {
                taken.add(n.getIdentifier());
                return true;
            }
        });
        String base = c.listName + "Set";
        String name = base;
        for (int i = 2; taken.contains(name); i++) {
            name = base + i;
        }
        c.setName = name;

        c.preconditions.add(new Precondition("only_read_in_loop", true,
                "Inside the loop '" + c.listName + "' is only read (contains, get, size, isEmpty, indexOf, iteration), so a set built before the loop stays in step with it."));
        boolean safe = safeElement(element);
        c.preconditions.add(new Precondition("element_equality_is_consistent", safe,
                safe ? c.elementType + " has equals() consistent with hashCode(), so a HashSet answers contains() exactly like the list."
                        : "Elements of type " + c.elementType + " may define equals() without a matching hashCode(); only String, boxed numbers, Character, Boolean and enums are accepted."));
        boolean inBlock = loop.getParent() instanceof Block && StringBuilderRule.startsLine(json.sourceText(), loop.getStartPosition());
        c.preconditions.add(new Precondition("loop_in_block", inBlock,
                inBlock ? "The loop is a statement of a block, so the set can be created before it."
                        : "The loop is not a statement of its own in a block (for example the unbraced body of an 'if')."));

        // aliasing: a parameter, or a list that is handed to other code, may be changed from there while the loop runs
        boolean[] escapes = {false};
        md.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName n) {
                IBinding b = n.resolveBinding();
                if (n.isDeclaration() || b == null || !b.isEqualTo(list)) {
                    return true;
                }
                ASTNode parent = n.getParent();
                boolean receiver = parent instanceof MethodInvocation mi && mi.getExpression() == n;
                boolean iterated = parent instanceof EnhancedForStatement ef && ef.getExpression() == n;
                boolean assigned = parent instanceof org.eclipse.jdt.core.dom.Assignment a && a.getLeftHandSide() == n;
                if (!receiver && !iterated && !assigned) {
                    escapes[0] = true;
                }
                return true;
            }
        });
        boolean isParameter = false;
        for (Object p : md.parameters()) {
            if (((org.eclipse.jdt.core.dom.SingleVariableDeclaration) p).resolveBinding() != null
                    && ((org.eclipse.jdt.core.dom.SingleVariableDeclaration) p).resolveBinding().isEqualTo(list)) {
                isParameter = true;
            }
        }
        c.aliasable = isParameter || escapes[0];
        boolean projectCalls = false;
        if (c.aliasable) {
            projectCalls = callsProjectCode(loaded, loop);
        }
        c.preconditions.add(new Precondition("no_project_calls_when_aliasable", !projectCalls,
                !c.aliasable ? "'" + c.listName + "' is a local that never leaves the method, so no other code can change it."
                        : projectCalls ? "'" + c.listName + "' may be reachable from other code and the loop calls project methods that could change it."
                                : "The loop calls no project code, only the JDK."));

        c.nonNullProven = provablyNonNull(md, list);
        c.id = proposalId(json, c);
        return c;
    }

    private static boolean callsProjectCode(ProjectAnalyzer.Loaded loaded, Statement loop) {
        boolean[] found = {false};
        loop.accept(new ASTVisitor() {
            private void check(IMethodBinding m) {
                if (m == null) {
                    found[0] = true;
                    return;
                }
                String owner = m.getDeclaringClass().getErasure().getQualifiedName();
                // a getter that only returns a field cannot change a list
                if (!owner.startsWith("java.") && !Optimizer.isTrivialAccessor(loaded, m)) {
                    found[0] = true;
                }
            }

            @Override
            public boolean visit(MethodInvocation n) {
                check(n.resolveMethodBinding());
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation n) {
                found[0] = true;
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation n) {
                check(n.resolveConstructorBinding());
                return true;
            }
        });
        return found[0];
    }

    private static boolean provablyNonNull(MethodDeclaration md, IVariableBinding list) {
        for (Object p : md.parameters()) {
            org.eclipse.jdt.core.dom.SingleVariableDeclaration d = (org.eclipse.jdt.core.dom.SingleVariableDeclaration) p;
            if (d.resolveBinding() != null && d.resolveBinding().isEqualTo(list)) {
                return false;
            }
        }
        boolean[] ok = {true};
        md.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment f) {
                if (f.getName().resolveBinding() != null && f.getName().resolveBinding().isEqualTo(list)) {
                    ok[0] &= f.getInitializer() instanceof ClassInstanceCreation;
                }
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.Assignment a) {
                if (a.getLeftHandSide() instanceof SimpleName n && n.resolveBinding() != null && n.resolveBinding().isEqualTo(list)) {
                    ok[0] &= a.getRightHandSide() instanceof ClassInstanceCreation;
                }
                return true;
            }
        });
        return ok[0];
    }

    private static String proposalId(AstJson json, Candidate c) {
        String basis = RULE + "|" + json.file() + "|" + c.methodId + "|" + c.listName + "|" + c.loop.getStartPosition();
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

    private static List<Map<String, Object>> assumptions(Candidate c) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (c.aliasable) {
            list.add(assumption(A_ALIAS, "No other reference to '" + c.listName + "' changes it while the loop runs (it is a parameter or leaves the method, "
                    + "so code outside could hold another reference)."));
        }
        if (!c.nonNullProven) {
            list.add(assumption(A_NON_NULL, "'" + c.listName + "' is not null when the loop is reached: the set is built before the loop, "
                    + "while the original touches the list only if the loop body runs."));
        }
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
        m.put("title", "Look up '" + c.listName + "' in a HashSet instead of scanning the list in a loop");
        boolean applicable = c.applicable();
        m.put("applicability", applicable ? "applicable" : "not_applicable");
        m.put("not_applicable_reasons", c.preconditions.stream().filter(p -> !p.satisfied()).map(Precondition::detail).toList());
        m.put("method_id", c.methodId);
        m.put("file", c.json.file());
        m.put("affected_files", List.of(c.json.file()));
        m.put("outer_loop", c.json.range(c.loop));
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
        complexity.put("method_before", Optimizer.methodComplexity(analysis, c.methodId));
        complexity.put("scan_before", "O(n*m)");
        complexity.put("scan_after", "O(n + m)");
        complexity.put("note", "n = iterations of the loop, m = size of '" + c.listName + "'. Building the set costs O(m) once; each contains() is O(1) "
                + "in expectation instead of O(m). Only the replaced lookups change; the method's other costs stay.");
        m.put("complexity", complexity);
        m.put("summary", applicable
                ? "Build Set<" + c.elementType + "> '" + c.setName + "' from '" + c.listName + "' before the loop and call contains() on it ("
                        + c.calls.size() + " lookup" + (c.calls.size() == 1 ? "" : "s") + ")."
                : "Not applicable: " + String.join(" ", c.preconditions.stream().filter(p -> !p.satisfied()).map(Precondition::detail).toList()));
        return m;
    }

    // ---------- apply ----------

    static Map<String, Object> apply(ProjectAnalyzer.Loaded loaded, Map<String, Object> baseline, Path root, List<String> files,
            String level, String proposalId, Set<String> acknowledged, boolean preview, Path outputDir) throws IOException {
        Candidate chosen = scan(loaded).stream().filter(c -> c.id.equals(proposalId)).findFirst().orElse(null);
        if (chosen == null) {
            return Optimizer.failure("proposal_not_found", "No optimization proposal with this id exists in this revision.");
        }
        if (!chosen.applicable()) {
            return Optimizer.failure("not_applicable", "The proposal is not applicable: "
                    + String.join(" ", chosen.preconditions.stream().filter(p -> !p.satisfied()).map(Precondition::detail).toList()));
        }
        Set<String> required = new TreeSet<>();
        for (Map<String, Object> a : assumptions(chosen)) {
            required.add((String) a.get("id"));
        }
        if (!preview && !acknowledged.containsAll(required)) {
            return Optimizer.failure("assumptions_not_acknowledged", "The caller must acknowledge every assumption of this proposal: " + required);
        }
        String rewritten = rewrite(chosen);

        List<String> paths = new ArrayList<>(new TreeSet<>(files));
        for (String path : paths) {
            Path target = outputDir.resolve(path);
            Files.createDirectories(target.getParent());
            if (path.equals(chosen.json.file())) {
                Files.writeString(target, rewritten, StandardCharsets.UTF_8);
            } else {
                Files.copy(root.resolve(path), target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Map<String, Object> candidateAnalysis = ProjectAnalyzer.analyze(outputDir, paths, level);
        List<String> introduced = Optimizer.newErrors(baseline, candidateAnalysis);
        if ("failed".equals(candidateAnalysis.get("status")) || !introduced.isEmpty()) {
            return Optimizer.failure("transformation_invalid", "The rewritten source does not analyse cleanly: "
                    + (introduced.isEmpty() ? "analysis failed" : String.join("; ", introduced)));
        }
        Object after = Optimizer.methodComplexity(candidateAnalysis, chosen.methodId);
        if (chosen.methodId != null && after == null) {
            return Optimizer.failure("transformation_invalid", "The rewritten project no longer contains the analysed method.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "completed");
        result.put("proposal", describe(chosen, baseline));
        result.put("changed_files", List.of(chosen.json.file()));
        result.put("method_complexity_before", Optimizer.methodComplexity(baseline, chosen.methodId));
        result.put("method_complexity_after", after);
        result.put("candidate_status", candidateAnalysis.get("status"));
        return result;
    }

    private record Edit(int start, int end, String text) {
    }

    private static String rewrite(Candidate c) {
        String source = c.json.sourceText();
        List<Edit> edits = new ArrayList<>();
        int loopStart = c.loop.getStartPosition();
        int lineStart = loopStart;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        String indent = source.substring(lineStart, loopStart);
        edits.add(new Edit(lineStart, lineStart,
                indent + "Set<" + c.elementType + "> " + c.setName + " = new HashSet<>(" + c.listName + ");\n"));
        for (MethodInvocation call : c.calls) {
            SimpleName receiver = (SimpleName) call.getExpression();
            edits.add(new Edit(receiver.getStartPosition(), receiver.getStartPosition() + receiver.getLength(), c.setName));
        }
        // imports
        CompilationUnit unit = c.json.unit();
        for (String type : List.of("java.util.Set", "java.util.HashSet")) {
            if (!imported(unit, type)) {
                edits.add(importEdit(unit, source, type));
            }
        }
        edits.sort((x, y) -> y.start() != x.start() ? Integer.compare(y.start(), x.start()) : 0);
        StringBuilder out = new StringBuilder(source);
        for (Edit e : edits) {
            out.replace(e.start(), e.end(), e.text());
        }
        return out.toString();
    }

    private static boolean imported(CompilationUnit unit, String type) {
        for (Object o : unit.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            String name = imp.getName().getFullyQualifiedName();
            if (!imp.isStatic() && (name.equals(type) || imp.isOnDemand() && name.equals("java.util"))) {
                return true;
            }
        }
        return false;
    }

    private static Edit importEdit(CompilationUnit unit, String source, String type) {
        int position;
        String text;
        if (!unit.imports().isEmpty()) {
            ImportDeclaration last = (ImportDeclaration) unit.imports().get(unit.imports().size() - 1);
            position = last.getStartPosition() + last.getLength();
            text = "\nimport " + type + ";";
        } else if (unit.getPackage() != null) {
            position = unit.getPackage().getStartPosition() + unit.getPackage().getLength();
            text = "\n\nimport " + type + ";";
        } else {
            position = 0;
            text = "import " + type + ";\n\n";
        }
        return new Edit(position, position, text);
    }
}
