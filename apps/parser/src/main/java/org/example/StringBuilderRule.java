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
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

/**
 * Optimization rule {@code string_concat_to_builder}: a local {@code String} that grows by {@code s += x} or
 * {@code s = s + x} inside a loop is built in a {@link StringBuilder} instead (O(L^2) copying becomes O(L)).
 *
 * <p>The rewrite sits at the outermost loop in which the string is only ever appended to: a builder is created
 * just before it, the appends become {@code builder.append(x)}, and the string is assigned back right after it.
 * It is refused when the string is read inside the loop, the loop sits in a {@code try} that catches or finishes
 * (the partial string could be observed), an operand is a {@code char[]} or the literal {@code null}, or the loop is
 * not directly inside a block. A string that is not provably non-null needs an acknowledged assumption.
 */
final class StringBuilderRule {
    static final String RULE = "string_concat_to_builder";
    private static final String A_NON_NULL = "string_never_null";

    private StringBuilderRule() {
    }

    private record Precondition(String id, boolean satisfied, String detail) {
    }

    private static final class Candidate {
        String id;
        AstJson json;
        MethodDeclaration method;
        String methodId;
        String varName;
        String builderName;
        IVariableBinding variable;
        Statement loop;
        final List<ExpressionStatement> appends = new ArrayList<>();
        final List<Precondition> preconditions = new ArrayList<>();
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

    private static List<Candidate> scan(ProjectAnalyzer.Loaded loaded) {
        List<Candidate> all = new ArrayList<>();
        for (AstJson json : loaded.jsons()) {
            json.unit().accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration md) {
                    ProjectGraph.Link link = loaded.links().get(md);
                    if (md.getBody() != null && link != null) {
                        scanMethod(json, md, link.methodId(), all);
                    }
                    return true;
                }
            });
        }
        return all;
    }

    private static boolean isString(ITypeBinding t) {
        return t != null && "java.lang.String".equals(t.getQualifiedName());
    }

    private static void scanMethod(AstJson json, MethodDeclaration md, String methodId, List<Candidate> out) {
        List<VariableDeclarationFragment> strings = new ArrayList<>();
        md.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(LambdaExpression n) {
                return false;
            }

            @Override
            public boolean visit(AnonymousClassDeclaration n) {
                return false;
            }

            @Override
            public boolean visit(VariableDeclarationStatement n) {
                if (n.fragments().size() == 1 && isString(n.getType().resolveBinding())) {
                    strings.add((VariableDeclarationFragment) n.fragments().get(0));
                }
                return true;
            }
        });
        for (VariableDeclarationFragment fragment : strings) {
            IVariableBinding var = fragment.resolveBinding();
            if (var == null || fragment.getInitializer() == null) {
                continue;
            }
            // every appending statement of this variable, with the loops around it
            List<ExpressionStatement> appends = new ArrayList<>();
            md.getBody().accept(new ASTVisitor() {
                @Override
                public boolean visit(ExpressionStatement n) {
                    if (appendTarget(n) != null && appendTarget(n).isEqualTo(var)) {
                        appends.add(n);
                    }
                    return true;
                }
            });
            Set<Statement> loops = new LinkedHashSet<>();
            for (ExpressionStatement append : appends) {
                Statement loop = outermostPureLoop(md, append, var);
                if (loop != null) {
                    loops.add(loop);
                }
            }
            for (Statement loop : loops) {
                out.add(candidate(json, md, methodId, fragment, var, loop));
            }
        }
    }

    private static boolean isLoop(ASTNode n) {
        return n instanceof ForStatement || n instanceof EnhancedForStatement || n instanceof WhileStatement || n instanceof DoStatement;
    }

    /** The variable that a statement appends to ({@code s += e} or {@code s = s + e ...}), or null. */
    private static IVariableBinding appendTarget(ExpressionStatement st) {
        if (!(st.getExpression() instanceof Assignment a) || !(a.getLeftHandSide() instanceof SimpleName name)
                || !(name.resolveBinding() instanceof IVariableBinding v) || v.isField() || !isString(v.getType())) {
            return null;
        }
        if (a.getOperator() == Assignment.Operator.PLUS_ASSIGN) {
            return v;
        }
        if (a.getOperator() == Assignment.Operator.ASSIGN && a.getRightHandSide() instanceof InfixExpression infix
                && infix.getOperator() == InfixExpression.Operator.PLUS && infix.getLeftOperand() instanceof SimpleName left
                && left.resolveBinding() != null && left.resolveBinding().isEqualTo(v)) {
            return v;
        }
        return null;
    }

    /** The operands appended by one appending statement, in evaluation order. */
    private static List<Expression> operands(ExpressionStatement st) {
        Assignment a = (Assignment) st.getExpression();
        List<Expression> list = new ArrayList<>();
        if (a.getOperator() == Assignment.Operator.PLUS_ASSIGN) {
            list.add(a.getRightHandSide());
        } else {
            InfixExpression infix = (InfixExpression) a.getRightHandSide();
            list.add(infix.getRightOperand());
            for (Object o : infix.extendedOperands()) {
                list.add((Expression) o);
            }
        }
        return list;
    }

    /** The outermost loop around {@code append} (inside the method) in which {@code var} is only appended to. */
    private static Statement outermostPureLoop(MethodDeclaration md, ExpressionStatement append, IVariableBinding var) {
        Statement best = null;
        for (ASTNode p = append.getParent(); p != null && p != md; p = p.getParent()) {
            if (p instanceof LambdaExpression || p instanceof AnonymousClassDeclaration) {
                break;
            }
            if (isLoop(p) && readsOutsideAppends((Statement) p, var).isEmpty()) {
                best = (Statement) p;
            }
        }
        return best;
    }

    /** Descriptions of the uses of {@code var} inside {@code loop} that are not plain appends. */
    private static List<String> readsOutsideAppends(Statement loop, IVariableBinding var) {
        List<String> reads = new ArrayList<>();
        loop.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName n) {
                IBinding b = n.resolveBinding();
                if (n.isDeclaration() || b == null || !b.isEqualTo(var)) {
                    return true;
                }
                ASTNode parent = n.getParent();
                boolean target = parent instanceof Assignment a && a.getLeftHandSide() == n && a.getParent() instanceof ExpressionStatement st
                        && appendTarget(st) != null;
                boolean leftOperand = parent instanceof InfixExpression infix && infix.getLeftOperand() == n
                        && infix.getParent() instanceof Assignment a2 && a2.getRightHandSide() == infix
                        && a2.getParent() instanceof ExpressionStatement st2 && appendTarget(st2) != null;
                if (!target && !leftOperand) {
                    reads.add("line " + ((org.eclipse.jdt.core.dom.CompilationUnit) n.getRoot()).getLineNumber(n.getStartPosition()));
                }
                return true;
            }
        });
        return reads;
    }

    private static Candidate candidate(AstJson json, MethodDeclaration md, String methodId, VariableDeclarationFragment decl,
            IVariableBinding var, Statement loop) {
        Candidate c = new Candidate();
        c.json = json;
        c.method = md;
        c.methodId = methodId;
        c.variable = var;
        c.varName = var.getName();
        c.loop = loop;
        loop.accept(new ASTVisitor() {
            @Override
            public boolean visit(ExpressionStatement n) {
                IVariableBinding target = appendTarget(n);
                if (target != null && target.isEqualTo(var)) {
                    c.appends.add(n);
                }
                return true;
            }
        });
        Set<String> taken = new HashSet<>();
        md.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName n) {
                taken.add(n.getIdentifier());
                return true;
            }
        });
        String base = c.varName + "Builder";
        String name = base;
        for (int i = 2; taken.contains(name); i++) {
            name = base + i;
        }
        c.builderName = name;

        // 1. the string is only appended to inside the loop (outermostPureLoop proved it; stated for the report)
        c.preconditions.add(new Precondition("only_appended_in_loop", true,
                "Inside the loop '" + c.varName + "' is only appended to; it is never read there, so the builder can hold it."));

        // 2. no try with catch/finally around the loop: the partially built string could be observed there
        boolean observable = false;
        for (ASTNode p = loop.getParent(); p != null && p != md; p = p.getParent()) {
            if (p instanceof TryStatement t && (!t.catchClauses().isEmpty() || t.getFinally() != null)) {
                observable = true;
            }
        }
        c.preconditions.add(new Precondition("no_observable_partial_result", !observable,
                observable ? "The loop is inside a try with catch or finally: an exception in the loop would leave the original string partly built, which a handler could read."
                        : "No try with catch or finally encloses the loop, so a failure in the loop cannot leave a partly built string visible."));

        // 3. operands that StringBuilder.append treats differently from string concatenation
        String bad = null;
        for (ExpressionStatement append : c.appends) {
            for (Expression operand : operands(append)) {
                ITypeBinding t = operand.resolveTypeBinding();
                if (operand instanceof NullLiteral) {
                    bad = "an operand is the literal null (append(null) is ambiguous)";
                } else if (t != null && t.isArray() && t.getElementType() != null && "char".equals(t.getElementType().getName())) {
                    bad = "an operand is a char[] (concatenation prints its identity, append() copies its characters)";
                }
            }
        }
        c.preconditions.add(new Precondition("operands_append_like_concatenation", bad == null,
                bad == null ? "Every appended value converts to text the same way in concatenation and in StringBuilder.append." : "Not applicable: " + bad + "."));

        // 4. the loop is a statement of a block, so a statement can go before and after it
        boolean inBlock = loop.getParent() instanceof Block && startsLine(json.sourceText(), loop.getStartPosition());
        c.preconditions.add(new Precondition("loop_in_block", inBlock,
                inBlock ? "The loop is a statement of a block, so the builder can be created before it and read after it."
                        : "The loop is not a statement of its own in a block (for example the unbraced body of an 'if')."));

        // 5. null: concatenation prints "null", new StringBuilder(null) throws
        c.nonNullProven = provablyNonNull(md, var);
        c.preconditions.add(new Precondition("string_non_null", true, c.nonNullProven
                ? "'" + c.varName + "' is only ever assigned literals or concatenations, so it is never null."
                : "'" + c.varName + "' may be null before the loop (acknowledge the assumption)."));
        c.id = proposalId(json, c);
        return c;
    }

    private static boolean startsLine(String source, int position) {
        int i = position;
        while (i > 0 && (source.charAt(i - 1) == ' ' || source.charAt(i - 1) == '\t')) {
            i--;
        }
        return i == 0 || source.charAt(i - 1) == '\n';
    }

    private static boolean provablyNonNull(MethodDeclaration md, IVariableBinding var) {
        boolean[] ok = {true};
        md.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment f) {
                if (f.getName().resolveBinding() != null && f.getName().resolveBinding().isEqualTo(var)) {
                    ok[0] &= nonNull(f.getInitializer());
                }
                return true;
            }

            @Override
            public boolean visit(Assignment a) {
                if (a.getLeftHandSide() instanceof SimpleName n && n.resolveBinding() != null && n.resolveBinding().isEqualTo(var)
                        && a.getOperator() == Assignment.Operator.ASSIGN) {
                    ok[0] &= nonNull(a.getRightHandSide());
                }
                return true;
            }
        });
        return ok[0];
    }

    private static boolean nonNull(Expression e) {
        return e instanceof StringLiteral || e instanceof InfixExpression infix && infix.getOperator() == InfixExpression.Operator.PLUS
                || e instanceof org.eclipse.jdt.core.dom.ParenthesizedExpression p && nonNull(p.getExpression());
    }

    private static String proposalId(AstJson json, Candidate c) {
        String basis = RULE + "|" + json.file() + "|" + c.methodId + "|" + c.varName + "|" + c.loop.getStartPosition();
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
        if (!c.nonNullProven) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", A_NON_NULL);
            m.put("text", "'" + c.varName + "' is not null when the loop starts: the original would print \"null\" followed by the appended text, "
                    + "while new StringBuilder(null) throws a NullPointerException.");
            list.add(m);
        }
        return list;
    }

    private static Map<String, Object> describe(Candidate c, Map<String, Object> analysis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("rule", RULE);
        m.put("title", "Build '" + c.varName + "' in a StringBuilder instead of concatenating in a loop");
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
        complexity.put("scan_before", "O(k*L)");
        complexity.put("scan_after", "O(L)");
        complexity.put("note", "L = length of the finished string, k = appends. Every concatenation copies the whole string built so far; a StringBuilder "
                + "appends in amortised O(1) per character. Only this loop changes; the method's other costs stay.");
        m.put("complexity", complexity);
        m.put("summary", applicable
                ? "Create StringBuilder '" + c.builderName + "' before the loop, append inside it, and assign '" + c.varName + "' once after it."
                : "Not applicable: " + String.join(" ", c.preconditions.stream().filter(p -> !p.satisfied()).map(Precondition::detail).toList()));
        return m;
    }

    // ---------- apply ----------

    static boolean owns(ProjectAnalyzer.Loaded loaded, String proposalId) {
        return scan(loaded).stream().anyMatch(c -> c.id.equals(proposalId));
    }

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
        int loopEnd = loopStart + c.loop.getLength();
        int lineStart = loopStart;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        String indent = source.substring(lineStart, loopStart);
        edits.add(new Edit(lineStart, lineStart, indent + "StringBuilder " + c.builderName + " = new StringBuilder(" + c.varName + ");\n"));
        edits.add(new Edit(loopEnd, loopEnd, "\n" + indent + c.varName + " = " + c.builderName + ".toString();"));
        for (ExpressionStatement append : c.appends) {
            StringBuilder call = new StringBuilder(c.builderName);
            for (Expression operand : operands(append)) {
                call.append(".append(").append(source, operand.getStartPosition(), operand.getStartPosition() + operand.getLength()).append(')');
            }
            call.append(';');
            edits.add(new Edit(append.getStartPosition(), append.getStartPosition() + append.getLength(), call.toString()));
        }
        edits.sort((x, y) -> Integer.compare(y.start(), x.start()));
        StringBuilder out = new StringBuilder(source);
        for (Edit e : edits) {
            out.replace(e.start(), e.end(), e.text());
        }
        return out.toString();
    }
}
