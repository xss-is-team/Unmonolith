package org.example;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

/**
 * Maintainability findings of the case mockup (slide 4: long method, duplicated logic, nesting), next to the complexity
 * findings. They are size and shape measurements of the source, not claims about running time.
 */
final class CodeSmells {
    static final int LONG_METHOD_LINES = 60;
    static final int DEEP_NESTING = 4;
    static final int MANY_PARAMETERS = 5;
    /** A duplicate is this many consecutive statements of a block, with at least this much code. */
    static final int DUPLICATE_STATEMENTS = 4;
    static final int DUPLICATE_CHARS = 120;

    private final AstJson json;
    private final CompilationUnit cu;
    private final MethodDeclaration method;
    private final List<Object> findings;

    CodeSmells(AstJson json, CompilationUnit cu, MethodDeclaration method, List<Object> findings) {
        this.json = json;
        this.cu = cu;
        this.method = method;
        this.findings = findings;
    }

    void detect() {
        if (method.getBody() == null) {
            return;
        }
        String name = method.getName().getIdentifier();
        Map<String, Object> range = json.range(method);
        int lines = ((Number) range.getOrDefault("end_line", 0)).intValue() - ((Number) range.getOrDefault("line", 0)).intValue() + 1;
        if (lines > LONG_METHOD_LINES) {
            findings.add(finding("long_method", "Long method: " + lines + " lines",
                    "'" + name + "' has " + lines + " lines (threshold " + LONG_METHOD_LINES + "). A long method mixes several "
                            + "responsibilities, is hard to read and to test, and hides defects such as nested scans.",
                    "Split it into smaller methods by responsibility (select statements on the graph and use «extract method»).",
                    method.getName()));
        }
        int[] deepest = {0};
        Statement[] at = {null};
        method.getBody().accept(new ASTVisitor() {
            int depth;

            private boolean enter(Statement s) {
                depth++;
                if (depth > deepest[0]) {
                    deepest[0] = depth;
                    at[0] = s;
                }
                return true;
            }

            @Override public boolean visit(IfStatement s) { return enter(s); }
            @Override public void endVisit(IfStatement s) { depth--; }
            @Override public boolean visit(ForStatement s) { return enter(s); }
            @Override public void endVisit(ForStatement s) { depth--; }
            @Override public boolean visit(EnhancedForStatement s) { return enter(s); }
            @Override public void endVisit(EnhancedForStatement s) { depth--; }
            @Override public boolean visit(WhileStatement s) { return enter(s); }
            @Override public void endVisit(WhileStatement s) { depth--; }
            @Override public boolean visit(DoStatement s) { return enter(s); }
            @Override public void endVisit(DoStatement s) { depth--; }
            @Override public boolean visit(SwitchStatement s) { return enter(s); }
            @Override public void endVisit(SwitchStatement s) { depth--; }
            @Override public boolean visit(TryStatement s) { return enter(s); }
            @Override public void endVisit(TryStatement s) { depth--; }
        });
        if (deepest[0] >= DEEP_NESTING && at[0] != null) {
            findings.add(finding("deep_nesting", "Deep nesting: " + deepest[0] + " levels",
                    "Conditions and loops in '" + name + "' are nested " + deepest[0] + " levels deep (threshold " + DEEP_NESTING
                            + "). Every level multiplies the paths a reader and a test have to cover.",
                    "Return early for the simple cases (guard clauses) and move the inner levels into a method of their own.",
                    at[0]));
        }
        if (method.parameters().size() > MANY_PARAMETERS) {
            findings.add(finding("too_many_parameters", "Too many parameters: " + method.parameters().size(),
                    "'" + name + "' takes " + method.parameters().size() + " parameters (threshold " + MANY_PARAMETERS
                            + "); calls are easy to get wrong and the method probably does several things.",
                    "Group parameters that travel together into a record or a small class.",
                    method.getName()));
        }
        duplicates(name);
    }

    /** The first run of statements of this method that also occurs elsewhere in the file (another method or later here). */
    private void duplicates(String name) {
        List<List<Statement>> mine = runs(method);
        Map<String, Statement> seen = new LinkedHashMap<>();
        for (List<Statement> run : mine) {
            for (int i = 0; i + DUPLICATE_STATEMENTS <= run.size(); i++) {
                String key = text(run.subList(i, i + DUPLICATE_STATEMENTS));
                if (key.length() < DUPLICATE_CHARS) {
                    continue;
                }
                if (seen.containsKey(key) && seen.get(key) != run.get(i)) {
                    report(name, run.get(i), name, seen.get(key));
                    return;
                }
                seen.putIfAbsent(key, run.get(i));
            }
        }
        for (Object type : cu.types()) {
            for (MethodDeclaration other : methods((ASTNode) type)) {
                if (other == method || other.getBody() == null) {
                    continue;
                }
                for (List<Statement> run : runs(other)) {
                    for (int i = 0; i + DUPLICATE_STATEMENTS <= run.size(); i++) {
                        String key = text(run.subList(i, i + DUPLICATE_STATEMENTS));
                        Statement mineAt = seen.get(key);
                        if (mineAt != null) {
                            report(name, mineAt, other.getName().getIdentifier(), run.get(i));
                            return;
                        }
                    }
                }
            }
        }
    }

    private void report(String name, Statement here, String otherName, Statement there) {
        Object line = json.range(there).get("line");
        findings.add(finding("duplicate_code", "Duplicated logic: " + DUPLICATE_STATEMENTS + "+ statements repeat",
                "The same " + DUPLICATE_STATEMENTS + " or more statements of '" + name + "' also appear in '" + otherName + "' (line "
                        + line + "). A fix applied to one copy is easily forgotten in the other.",
                "Move the repeated statements into one method and call it from both places (select them on the graph and use «extract method»).",
                here));
    }

    private static List<MethodDeclaration> methods(ASTNode root) {
        List<MethodDeclaration> list = new ArrayList<>();
        root.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration m) {
                list.add(m);
                return true;
            }
        });
        return list;
    }

    /** The statement lists of every block of a method. */
    private static List<List<Statement>> runs(MethodDeclaration m) {
        List<List<Statement>> list = new ArrayList<>();
        m.getBody().accept(new ASTVisitor() {
            @Override
            @SuppressWarnings("unchecked")
            public boolean visit(Block b) {
                list.add(new ArrayList<>((List<Statement>) b.statements()));
                return true;
            }
        });
        return list;
    }

    private static String text(List<Statement> statements) {
        StringBuilder out = new StringBuilder();
        for (Statement s : statements) {
            out.append(s.toString().replaceAll("\\s+", " ").trim()).append('\n');
        }
        return out.toString();
    }

    private Map<String, Object> finding(String id, String message, String explanation, String recommendation, ASTNode at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("severity", "info");
        m.put("category", "maintainability");
        m.put("message", message);
        m.put("explanation", explanation);
        m.put("recommendation", recommendation);
        m.put("source_range", json.range(at));
        m.put("node_ids", List.of());
        return m;
    }
}
