package org.example;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

/**
 * {@code java -jar parser.jar edit --root DIR --input request.json [--output-dir OUT]}: a structured edit
 * requested from the CFG view. The only operation is {@code move_statement}: a statement is moved before or
 * after another statement of the <em>same block</em>, over every statement in between.
 *
 * Dragging nodes in the UI changes layout only; this is the operation that changes Java. It is applied only
 * if the moved statement is independent of every statement it jumps over:
 * <ul>
 *   <li>no read/write, write/read or write/write conflict on a local variable, field, array or collection
 *       (declarations count as writes, so scope and definite assignment are respected);</li>
 *   <li>neither side can complete abruptly ({@code return}, {@code throw}, {@code break}/{@code continue}
 *       leaving the statement);</li>
 *   <li>no I/O, unknown call or other effect is reordered with anything that touches the same state;</li>
 *   <li>at most one side can throw, and the thrower is not moved over an effect (nor, inside a {@code try},
 *       over a local write a handler could observe);</li>
 *   <li>no synchronization, volatile access, thread or lock call, local class, labeled statement or
 *       explicit constructor call is involved.</li>
 * </ul>
 * Anything else is rejected with structured reasons and nothing is written. The edit itself is an
 * {@link ASTRewrite} move; the candidate project is re-parsed and must not have new errors.
 *
 * Request: {@code {"mode":"single"|"project","source_level","files":[...],"method_id","operation":
 * {"type":"move_statement","node_id","target_node_id","position":"before"|"after"}}}. Node ids are the CFG node
 * ids of the analysis of the same files (single mode: {@code m3.n5}; project mode: {@code f1m3.n5}).
 */
final class StatementMove {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEAP = "*heap*";
    private static final String IO = "*io*";

    private StatementMove() {
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
                    err.println("usage: edit --root DIR --input request.json [--output-dir OUT]");
                    return 2;
                }
            }
        }
        if (root == null || input == null) {
            err.println("usage: edit --root DIR --input request.json [--output-dir OUT]");
            return 2;
        }
        try {
            JsonNode request = JSON.readTree(Files.readAllBytes(Path.of(input)));
            Map<String, Object> result = edit(Path.of(root), request, outputDir == null ? null : Path.of(outputDir));
            out.println(Json.pretty(result));
            return "completed".equals(result.get("status")) ? 0 : 1;
        } catch (IOException | RuntimeException | StackOverflowError e) {
            e.printStackTrace(err);
            out.println(Json.pretty(failure("internal_error", "Edit failed: " + e, null)));
            return 1;
        }
    }

    private static Map<String, Object> failure(String code, String message, List<Object> reasons) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (reasons != null) {
            error.put("reasons", reasons);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "failed");
        result.put("error", error);
        return result;
    }

    private static Map<String, Object> reason(String code, String message, Object... statements) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        List<Object> list = new ArrayList<>();
        for (Object s : statements) {
            list.add(s);
        }
        m.put("statements", list);
        return m;
    }

    private static Map<String, Object> rejected(List<Object> reasons) {
        String summary = reasons.stream().map(r -> (String) ((Map<?, ?>) r).get("message")).reduce((a, b) -> a + " " + b)
                .orElse("The move is not safe.");
        return failure("move_rejected", summary, reasons);
    }

    // ---------- the edit ----------

    private static final class Resolved {
        AstJson json;
        MethodDeclaration method;
        Map<String, Object> methodJson;
        Map<String, Map<String, Object>> nodes = new HashMap<>();
        ProjectAnalyzer.Loaded loaded;
        Map<String, Object> analysis;
    }

    private static Map<String, Object> edit(Path root, JsonNode request, Path outputDir) throws IOException {
        String level = request.path("source_level").asText(ProjectAnalyzer.DEFAULT_SOURCE_LEVEL);
        boolean single = request.path("mode").asText("project").equals("single");
        List<String> files = new ArrayList<>();
        for (JsonNode file : request.path("files")) {
            files.add(file.asText());
        }
        String methodId = request.path("method_id").asText("");
        JsonNode op = request.path("operation");
        String type = op.path("type").asText("");
        if (type.equals("insert_statement") || type.equals("delete_statement") || type.equals("extract_method")) {
            return editStructural(root, request, outputDir, type);
        }
        if (!type.equals("move_statement")) {
            return failure("unsupported_operation", "Supported operations: move_statement, insert_statement, delete_statement, extract_method.", null);
        }
        String nodeId = op.path("node_id").asText("");
        String targetId = op.path("target_node_id").asText("");
        String position = op.path("position").asText("");
        if (nodeId.isEmpty() || targetId.isEmpty() || !(position.equals("before") || position.equals("after"))) {
            return failure("invalid_operation", "node_id, target_node_id and position (before|after) are required.", null);
        }

        Resolved r = new Resolved();
        r.analysis = single && files.size() == 1 ? analyzeSingle(root, files.get(0), level, r)
                : ProjectAnalyzer.analyze(root, files, level, loaded -> r.loaded = loaded);
        if ("failed".equals(r.analysis.get("status")) || r.loaded == null) {
            return failure("analysis_failed", "The project could not be analysed: " + firstDiagnostic(r.analysis), null);
        }
        Map<String, Object> failure = resolveMethod(r, methodId);
        if (failure != null) {
            return failure;
        }
        Statement source = statementOf(r, nodeId);
        Statement target = statementOf(r, targetId);
        if (source == null) {
            return failure("unsupported_node", "Node '" + nodeId + "' is not a statement that can be moved "
                    + "(only whole statements and branch conditions of a block can).", null);
        }
        if (target == null) {
            return failure("unsupported_node", "Node '" + targetId + "' is not a statement of a block.", null);
        }
        if (source == target) {
            return failure("invalid_operation", "A statement cannot be moved relative to itself.", null);
        }

        List<Object> reasons = new ArrayList<>();
        if (!(source.getParent() instanceof Block block) || source.getParent() != target.getParent()) {
            reasons.add(reason("different_scope", "Both statements must be directly in the same block; moving across "
                    + "blocks, loops or conditions is not supported.", describe(r, source), describe(r, target)));
            return rejected(reasons);
        }
        List<?> statements = block.statements();
        int from = statements.indexOf(source);
        int anchor = statements.indexOf(target);
        int to = position.equals("before") ? anchor : anchor + 1;
        // index in the list after the source is removed
        int insertAt = to > from ? to - 1 : to;
        if (insertAt == from) {
            return failure("no_change", "The statement is already at that position.", null);
        }
        // the statements jumped over are exactly those between the old and the new position
        List<Statement> jumped = new ArrayList<>();
        if (insertAt > from) {
            for (int i = from + 1; i <= insertAt; i++) {
                jumped.add((Statement) statements.get(i));
            }
        } else {
            for (int i = insertAt; i < from; i++) {
                jumped.add((Statement) statements.get(i));
            }
        }

        boolean inTry = insideTry(source);
        Footprint moved = Footprint.of(r.loaded, source);
        List<Object> evidence = new ArrayList<>();
        checkShape(r, source, moved, reasons);
        for (Statement other : jumped) {
            Footprint f = Footprint.of(r.loaded, other);
            checkShape(r, other, f, reasons);
            List<Object> conflicts = conflicts(r, source, moved, other, f, inTry);
            reasons.addAll(conflicts);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("statement", describe(r, other));
            e.put("independent", conflicts.isEmpty());
            e.put("moved_reads", sorted(moved.reads));
            e.put("moved_writes", sorted(moved.writes));
            e.put("other_reads", sorted(f.reads));
            e.put("other_writes", sorted(f.writes));
            evidence.add(e);
        }
        if (!reasons.isEmpty()) {
            return rejected(dedupe(reasons));
        }

        // ---- rewrite
        String sourceText = r.json.sourceText();
        ASTRewrite rewrite = ASTRewrite.create(r.json.unit().getAST());
        ListRewrite list = rewrite.getListRewrite(block, Block.STATEMENTS_PROPERTY);
        ASTNode moveTarget = rewrite.createMoveTarget(source);
        if (position.equals("before")) {
            list.insertBefore(moveTarget, target, null);
        } else {
            list.insertAfter(moveTarget, target, null);
        }
        Document document = new Document(sourceText);
        Map<String, String> formatting = JavaCore.getOptions();
        Optimizer.applyIndentation(formatting, sourceText);
        try {
            TextEdit edit = rewrite.rewriteAST(document, formatting);
            edit.apply(document);
        } catch (Exception e) {
            return failure("rewrite_failed", "Could not apply the move: " + e, null);
        }
        String rewritten = document.get();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "completed");
        result.put("method_id", methodId);
        result.put("file", r.json.file() == null ? files.get(0) : r.json.file());
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "move_statement");
        operation.put("moved", describe(r, source));
        operation.put("from_index", from);
        operation.put("to_index", insertAt);
        operation.put("position", position);
        operation.put("relative_to", describe(r, target));
        operation.put("jumped_over", evidence);
        result.put("operation", operation);
        return finish(root, files, level, single, r, methodId, rewritten, result, outputDir);
    }

    /** Writes the candidate tree, re-analyses it and maps the CFG nodes (shared by every statement edit). */
    private static Map<String, Object> finish(Path root, List<String> files, String level, boolean single, Resolved r,
            String methodId, String rewritten, Map<String, Object> result, Path outputDir) throws IOException {
        if (outputDir == null) {
            return result; // dry run: analysis only
        }
        String changed = (String) result.get("file");
        List<String> paths = new ArrayList<>(new TreeSet<>(files));
        for (String path : paths) {
            Path destination = outputDir.resolve(path);
            Files.createDirectories(destination.getParent());
            if (path.equals(changed)) {
                Files.writeString(destination, rewritten, StandardCharsets.UTF_8);
            } else {
                Files.copy(root.resolve(path), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // ---- validate the candidate and map CFG nodes
        Resolved after = new Resolved();
        after.analysis = single && files.size() == 1 ? analyzeSingle(outputDir, files.get(0), level, after)
                : ProjectAnalyzer.analyze(outputDir, paths, level, loaded -> after.loaded = loaded);
        List<String> introduced = newErrors(r.analysis, after.analysis);
        if ("failed".equals(after.analysis.get("status")) || !introduced.isEmpty()) {
            return failure("transformation_invalid", "The moved source does not analyse cleanly: "
                    + (introduced.isEmpty() ? "analysis failed" : String.join("; ", introduced)), null);
        }
        Map<String, Object> afterMethod = findMethodJson(after.analysis, methodId);
        if (afterMethod == null || !(afterMethod.get("cfg") instanceof Map<?, ?>)) {
            return failure("transformation_invalid", "The moved source no longer has a control-flow graph for the method.", null);
        }
        result.put("changed_files", List.of(changed));
        result.put("node_mapping", nodeMapping(r.methodJson, afterMethod));
        result.put("candidate_status", after.analysis.get("status"));
        return result;
    }

    private static final int MAX_INSERT_CHARS = 4000;

    /**
     * {@code insert_statement} (Java statements placed before/after a statement of a block) and {@code delete_statement}
     * (one statement of a block removed). Both are text-preserving ASTRewrite edits; the result is re-analysed and
     * refused when it introduces an error (a deleted declaration that is still used, a missing return, a type error).
     * Whether the edit keeps the behaviour is for the verify step to show.
     */
    private static Map<String, Object> editStructural(Path root, JsonNode request, Path outputDir, String type) throws IOException {
        String level = request.path("source_level").asText(ProjectAnalyzer.DEFAULT_SOURCE_LEVEL);
        boolean single = request.path("mode").asText("project").equals("single");
        List<String> files = new ArrayList<>();
        for (JsonNode file : request.path("files")) {
            files.add(file.asText());
        }
        String methodId = request.path("method_id").asText("");
        JsonNode op = request.path("operation");

        Resolved r = new Resolved();
        r.analysis = single && files.size() == 1 ? analyzeSingle(root, files.get(0), level, r)
                : ProjectAnalyzer.analyze(root, files, level, loaded -> r.loaded = loaded);
        if ("failed".equals(r.analysis.get("status")) || r.loaded == null) {
            return failure("analysis_failed", "The project could not be analysed: " + firstDiagnostic(r.analysis), null);
        }
        Map<String, Object> failure = resolveMethod(r, methodId);
        if (failure != null) {
            return failure;
        }
        if (type.equals("extract_method")) {
            return extractMethod(root, files, level, single, r, methodId, op, outputDir);
        }
        ASTRewrite rewrite = ASTRewrite.create(r.json.unit().getAST());
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", type);
        if (type.equals("delete_statement")) {
            String nodeId = op.path("node_id").asText("");
            Statement statement = statementOf(r, nodeId);
            if (nodeId.isEmpty() || statement == null) {
                return failure("unsupported_node", "Node '" + nodeId + "' is not a whole statement of a block that can be deleted.", null);
            }
            rewrite.getListRewrite((Block) statement.getParent(), Block.STATEMENTS_PROPERTY).remove(statement, null);
            operation.put("deleted", describe(r, statement));
        } else {
            String targetId = op.path("target_node_id").asText("");
            String position = op.path("position").asText("");
            String code = op.path("code").asText("");
            if (targetId.isEmpty() || !(position.equals("before") || position.equals("after")) || code.isBlank()) {
                return failure("invalid_operation", "target_node_id, position (before|after) and code are required.", null);
            }
            if (code.length() > MAX_INSERT_CHARS) {
                return failure("invalid_operation", "The code to insert is longer than " + MAX_INSERT_CHARS + " characters.", null);
            }
            Statement target = statementOf(r, targetId);
            if (target == null) {
                return failure("unsupported_node", "Node '" + targetId + "' is not a statement of a block.", null);
            }
            List<String> pieces = parseStatements(code);
            if (pieces == null) {
                return failure("invalid_operation", "The code is not a sequence of valid Java statements.", null);
            }
            Block block = (Block) target.getParent();
            ListRewrite list = rewrite.getListRewrite(block, Block.STATEMENTS_PROPERTY);
            int base = block.statements().indexOf(target) + (position.equals("after") ? 1 : 0);
            for (int i = 0; i < pieces.size(); i++) {
                list.insertAt(rewrite.createStringPlaceholder(pieces.get(i), ASTNode.EMPTY_STATEMENT), base + i, null);
            }
            operation.put("inserted", pieces);
            operation.put("position", position);
            operation.put("relative_to", describe(r, target));
        }
        String sourceText = r.json.sourceText();
        Document document = new Document(sourceText);
        Map<String, String> formatting = JavaCore.getOptions();
        Optimizer.applyIndentation(formatting, sourceText);
        try {
            rewrite.rewriteAST(document, formatting).apply(document);
        } catch (Exception e) {
            return failure("rewrite_failed", "Could not apply the edit: " + e, null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "completed");
        result.put("method_id", methodId);
        result.put("file", r.json.file() == null ? files.get(0) : r.json.file());
        result.put("operation", operation);
        return finish(root, files, level, single, r, methodId, document.get(), result, outputDir);
    }

    // ---------- extract method ----------

    private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null");

    private static Map<String, Object> refused(String code, String message) {
        return failure(code, message, null);
    }

    /**
     * {@code extract_method}: consecutive statements of one block become a private method of the same class and are replaced
     * by a call. Values that flow in become parameters; at most one value may flow out (returned and assigned back). Refused
     * when the selection returns, jumps out of itself, lets more than one value out, or the result does not analyse cleanly.
     */
    private static Map<String, Object> extractMethod(Path root, List<String> files, String level, boolean single, Resolved r,
            String methodId, JsonNode op, Path outputDir) throws IOException {
        String firstId = op.path("first_node_id").asText("");
        String lastId = op.path("last_node_id").asText("");
        String name = op.path("name").asText("");
        if (firstId.isEmpty() || lastId.isEmpty() || !name.matches("[a-z][A-Za-z0-9_]*") || KEYWORDS.contains(name)) {
            return refused("invalid_operation", "first_node_id, last_node_id and a method name (a valid Java identifier starting with a lowercase letter) are required.");
        }
        Statement first = statementOf(r, firstId);
        Statement last = statementOf(r, lastId);
        if (first == null || last == null) {
            return refused("unsupported_node", "Both ends of the selection must be whole statements of a block.");
        }
        if (first.getParent() != last.getParent() || !(first.getParent() instanceof Block block)) {
            return refused("invalid_operation", "The selection must be consecutive statements of one block.");
        }
        int from = block.statements().indexOf(first);
        int to = block.statements().indexOf(last);
        if (from > to) {
            int swap = from;
            from = to;
            to = swap;
            Statement s = first;
            first = last;
            last = s;
        }
        List<Statement> selected = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            selected.add((Statement) block.statements().get(i));
        }
        int start = first.getStartPosition();
        int end = last.getStartPosition() + last.getLength();
        if (!(r.method.getParent() instanceof AbstractTypeDeclaration type) || type instanceof AnnotationTypeDeclaration
                || type instanceof TypeDeclaration td && td.isInterface()) {
            return refused("unsupported_node", "The method must belong to a class, enum or record.");
        }
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof MethodDeclaration other && other.getName().getIdentifier().equals(name)) {
                return refused("invalid_operation", "The class already has a method named '" + name + "'.");
            }
        }

        // what the selection does
        Set<IVariableBinding> declaredInside = new LinkedHashSet<>();
        Map<IVariableBinding, boolean[]> outside = new LinkedHashMap<>(); // [read, written]
        List<String> problems = new ArrayList<>();
        Set<ITypeBinding> thrown = new LinkedHashSet<>();
        List<TryStatement> tries = new ArrayList<>();
        for (Statement s : selected) {
            s.accept(new ASTVisitor() {
                @Override
                public boolean visit(VariableDeclarationFragment f) {
                    if (f.resolveBinding() != null) {
                        declaredInside.add(f.resolveBinding());
                    }
                    return true;
                }

                @Override
                public boolean visit(SingleVariableDeclaration d) {
                    if (d.resolveBinding() != null) {
                        declaredInside.add(d.resolveBinding());
                    }
                    return true;
                }

                @Override
                public boolean visit(ReturnStatement n) {
                    problems.add("The selection contains a return statement (line " + r.json.unit().getLineNumber(n.getStartPosition()) + ").");
                    return true;
                }

                @Override
                public boolean visit(YieldStatement n) {
                    problems.add("The selection contains a yield statement.");
                    return true;
                }

                @Override
                public boolean visit(BreakStatement n) {
                    if (!jumpsInside(n, selected)) {
                        problems.add("A break at line " + r.json.unit().getLineNumber(n.getStartPosition()) + " leaves the selection.");
                    }
                    return true;
                }

                @Override
                public boolean visit(ContinueStatement n) {
                    if (!jumpsInside(n, selected)) {
                        problems.add("A continue at line " + r.json.unit().getLineNumber(n.getStartPosition()) + " leaves the selection.");
                    }
                    return true;
                }

                @Override
                public boolean visit(TryStatement n) {
                    tries.add(n);
                    return true;
                }

                @Override
                public boolean visit(ThrowStatement n) {
                    ITypeBinding t = n.getExpression().resolveTypeBinding();
                    if (t != null) {
                        thrown.add(t);
                    }
                    return true;
                }

                @Override
                public boolean visit(MethodInvocation n) {
                    IMethodBinding b = n.resolveMethodBinding();
                    if (b != null) {
                        thrown.addAll(List.of(b.getExceptionTypes()));
                    }
                    return true;
                }

                @Override
                public boolean visit(ClassInstanceCreation n) {
                    IMethodBinding b = n.resolveConstructorBinding();
                    if (b != null) {
                        thrown.addAll(List.of(b.getExceptionTypes()));
                    }
                    return true;
                }

                @Override
                public boolean visit(SimpleName n) {
                    if (n.isDeclaration() || !(n.resolveBinding() instanceof IVariableBinding v) || v.isField()) {
                        return true;
                    }
                    if (declaredInside.contains(v) || isDeclaredInside(r, v, selected)) {
                        return true;
                    }
                    boolean[] flags = outside.computeIfAbsent(v, k -> new boolean[2]);
                    ASTNode parent = n.getParent();
                    boolean write = parent instanceof Assignment a && a.getLeftHandSide() == n
                            || parent instanceof PostfixExpression
                            || parent instanceof PrefixExpression p
                                    && (p.getOperator() == PrefixExpression.Operator.INCREMENT || p.getOperator() == PrefixExpression.Operator.DECREMENT);
                    boolean compound = parent instanceof Assignment a2 && a2.getLeftHandSide() == n && a2.getOperator() != Assignment.Operator.ASSIGN
                            || parent instanceof PostfixExpression || parent instanceof PrefixExpression;
                    if (write) {
                        flags[1] = true;
                    }
                    if (!write || compound) {
                        flags[0] = true;
                    }
                    return true;
                }
            });
        }
        // checked exceptions that are not caught inside the selection must stay declared by the method
        List<ITypeBinding> checked = new ArrayList<>();
        for (ITypeBinding t : thrown) {
            if (isChecked(t) && tries.stream().noneMatch(tr -> catches(tr, t)) && checked.stream().noneMatch(c -> c.isEqualTo(t))) {
                checked.add(t);
            }
        }
        if (!problems.isEmpty()) {
            return refused("invalid_operation", String.join(" ", problems));
        }

        // values that flow out: written in the selection and used elsewhere, or declared in it and used after it
        List<IVariableBinding> outputs = new ArrayList<>();
        for (Map.Entry<IVariableBinding, boolean[]> e : outside.entrySet()) {
            if (e.getValue()[1] && usedOutside(r, e.getKey(), start, end)) {
                outputs.add(e.getKey());
            }
        }
        for (IVariableBinding v : declaredInside) {
            if (usedOutside(r, v, start, end)) {
                outputs.add(v);
            }
        }
        if (outputs.size() > 1) {
            return refused("invalid_operation", "More than one value flows out of the selection ("
                    + String.join(", ", outputs.stream().map(IVariableBinding::getName).toList()) + "); a method returns one.");
        }
        IVariableBinding output = outputs.isEmpty() ? null : outputs.get(0);
        boolean outputDeclaredInside = output != null && declaredInside.contains(output);

        List<IVariableBinding> inputs = new ArrayList<>();
        List<IVariableBinding> assignedOnly = new ArrayList<>(); // written but never read in the selection: a local of the new method
        for (Map.Entry<IVariableBinding, boolean[]> e : outside.entrySet()) {
            (e.getValue()[0] ? inputs : assignedOnly).add(e.getKey());
        }
        StringBuilder params = new StringBuilder();
        StringBuilder args = new StringBuilder();
        for (IVariableBinding v : inputs) {
            if (params.length() > 0) {
                params.append(", ");
                args.append(", ");
            }
            params.append(typeText(r, v)).append(' ').append(v.getName());
            args.append(v.getName());
        }
        String returnType = output == null ? "void" : typeText(r, output);

        String source = r.json.sourceText();
        int lineStart = start;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        String statementIndent = source.substring(lineStart, start).isBlank() ? source.substring(lineStart, start) : "    ";
        int methodStart = r.method.getStartPosition();
        int methodLineStart = methodStart;
        while (methodLineStart > 0 && source.charAt(methodLineStart - 1) != '\n') {
            methodLineStart--;
        }
        String methodIndent = source.substring(methodLineStart, methodStart).isBlank() ? source.substring(methodLineStart, methodStart) : "";

        String call = name + "(" + args + ");";
        if (output != null) {
            call = (outputDeclaredInside ? returnType + " " : "") + output.getName() + " = " + call;
        }
        StringBuilder method = new StringBuilder();
        method.append(methodIndent).append("private ").append(Modifier.isStatic(r.method.getModifiers()) ? "static " : "")
                .append(returnType).append(' ').append(name).append('(').append(params).append(')');
        if (!checked.isEmpty()) {
            method.append(" throws ").append(String.join(", ", checked.stream().map(ITypeBinding::getName).toList()));
        }
        method.append(" {\n");
        for (IVariableBinding v : assignedOnly) {
            method.append(statementIndent).append(typeText(r, v)).append(' ').append(v.getName()).append(";\n");
        }
        method.append(statementIndent).append(source, start, end).append('\n');
        if (output != null) {
            method.append(statementIndent).append("return ").append(output.getName()).append(";\n");
        }
        method.append(methodIndent).append('}');

        int methodEnd = methodStart + r.method.getLength();
        StringBuilder rewritten = new StringBuilder(source);
        rewritten.insert(methodEnd, "\n\n" + method);
        rewritten.replace(start, end, call);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "completed");
        result.put("method_id", methodId);
        result.put("file", r.json.file() == null ? files.get(0) : r.json.file());
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "extract_method");
        operation.put("name", name);
        operation.put("statements", selected.size());
        operation.put("parameters", inputs.stream().map(IVariableBinding::getName).toList());
        operation.put("returns", output == null ? null : output.getName());
        operation.put("throws", checked.stream().map(ITypeBinding::getName).toList());
        result.put("operation", operation);
        return finish(root, files, level, single, r, methodId, rewritten.toString(), result, outputDir);
    }

    private static boolean isDeclaredInside(Resolved r, IVariableBinding v, List<Statement> selected) {
        ASTNode declaration = r.json.unit().findDeclaringNode(v);
        for (ASTNode p = declaration; p != null; p = p.getParent()) {
            if (selected.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /** True if a break/continue stays inside the selection (its loop, switch or label is part of it). */
    private static boolean jumpsInside(Statement jump, List<Statement> selected) {
        String label = jump instanceof BreakStatement b ? (b.getLabel() == null ? null : b.getLabel().getIdentifier())
                : ((ContinueStatement) jump).getLabel() == null ? null : ((ContinueStatement) jump).getLabel().getIdentifier();
        for (ASTNode p = jump.getParent(); p != null; p = p.getParent()) {
            boolean target = label != null ? p instanceof LabeledStatement l && l.getLabel().getIdentifier().equals(label)
                    : p instanceof ForStatement || p instanceof EnhancedForStatement || p instanceof WhileStatement
                            || p instanceof DoStatement || jump instanceof BreakStatement && p instanceof SwitchStatement;
            if (target) {
                return true;
            }
            if (selected.contains(p)) {
                return false;
            }
        }
        return false;
    }

    private static boolean usedOutside(Resolved r, IVariableBinding v, int start, int end) {
        boolean[] used = {false};
        r.method.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName n) {
                if (!n.isDeclaration() && n.resolveBinding() instanceof IVariableBinding other && other.isEqualTo(v)
                        && (n.getStartPosition() < start || n.getStartPosition() >= end)) {
                    used[0] = true;
                }
                return true;
            }
        });
        return used[0];
    }

    private static boolean isChecked(ITypeBinding t) {
        for (ITypeBinding c = t; c != null; c = c.getSuperclass()) {
            String name = c.getErasure().getQualifiedName();
            if (name.equals("java.lang.RuntimeException") || name.equals("java.lang.Error")) {
                return false;
            }
        }
        return true;
    }

    private static boolean catches(TryStatement tr, ITypeBinding thrown) {
        for (Object o : tr.catchClauses()) {
            ITypeBinding caught = ((CatchClause) o).getException().getType().resolveBinding();
            if (caught != null && thrown.isSubTypeCompatible(caught)) {
                return true;
            }
        }
        return false;
    }

    /** The declared type as written in the source (keeps generics readable); the resolved name for `var` and lambda parameters. */
    private static String typeText(Resolved r, IVariableBinding v) {
        ASTNode declaration = r.json.unit().findDeclaringNode(v);
        Type type = null;
        if (declaration instanceof SingleVariableDeclaration d) {
            type = d.getType();
        } else if (declaration instanceof VariableDeclarationFragment f && f.getParent() instanceof VariableDeclarationStatement s) {
            type = s.getType();
        }
        if (type != null && !type.isVar()) {
            String text = r.json.text(type);
            int extra = declaration instanceof VariableDeclarationFragment f ? f.getExtraDimensions()
                    : ((SingleVariableDeclaration) declaration).getExtraDimensions();
            return text + "[]".repeat(extra);
        }
        return v.getType().getQualifiedName();
    }

    /** The statements of {@code code} as source text, or null when it is not a valid statement sequence. */
    private static List<String> parseStatements(String code) {
        String prefix = "class Snippet { void run() {\n";
        String wrapped = prefix + code + "\n} }";
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(wrapped.toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        for (IProblem problem : unit.getProblems()) {
            if (problem.isError()) {
                return null;
            }
        }
        if (unit.types().size() != 1 || !(unit.types().get(0) instanceof TypeDeclaration type) || type.getMethods().length != 1
                || type.getMethods()[0].getBody() == null) {
            return null;
        }
        List<String> pieces = new ArrayList<>();
        for (Object s : type.getMethods()[0].getBody().statements()) {
            Statement statement = (Statement) s;
            pieces.add(wrapped.substring(statement.getStartPosition(), statement.getStartPosition() + statement.getLength()));
        }
        return pieces.isEmpty() ? null : pieces;
    }

    @SuppressWarnings("unchecked")
    private static String firstDiagnostic(Map<String, Object> analysis) {
        Object diagnostics = analysis.get("diagnostics");
        if (diagnostics instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> d) {
            return String.valueOf(d.get("message"));
        }
        return "unknown error";
    }

    private static Map<String, Object> analyzeSingle(Path root, String file, String level, Resolved r) throws IOException {
        String source = Files.readString(root.resolve(file), StandardCharsets.UTF_8);
        String unit = Path.of(file).getFileName().toString();
        CompilationUnit cu = Main.createAst(source, unit, true);
        AstJson json = new AstJson(cu, source, true);
        Map<String, Object> converted = json.convert();
        r.loaded = new ProjectAnalyzer.Loaded(List.of(json), json.methodLinks(), true);
        return converted;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findMethodJson(Object tree, String methodId) {
        if (tree instanceof Map<?, ?> map) {
            if (methodId.equals(map.get("id")) && map.get("cfg") instanceof Map<?, ?>) {
                return (Map<String, Object>) map;
            }
            for (Object child : map.values()) {
                Map<String, Object> hit = findMethodJson(child, methodId);
                if (hit != null) {
                    return hit;
                }
            }
        } else if (tree instanceof List<?> list) {
            for (Object child : list) {
                Map<String, Object> hit = findMethodJson(child, methodId);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveMethod(Resolved r, String methodId) {
        r.methodJson = findMethodJson(r.analysis, methodId);
        if (r.methodJson == null) {
            return failure("unknown_method", "No method with id '" + methodId + "' that has a control-flow graph.", null);
        }
        Map<String, Object> cfg = (Map<String, Object>) r.methodJson.get("cfg");
        for (Object o : (List<Object>) cfg.get("nodes")) {
            Map<String, Object> n = (Map<String, Object>) o;
            r.nodes.put((String) n.get("id"), n);
        }
        for (AstJson json : r.loaded.jsons()) {
            for (Map.Entry<MethodDeclaration, ProjectGraph.Link> e : r.loaded.links().entrySet()) {
                if (e.getValue().methodId().equals(methodId) && e.getKey().getRoot() == json.unit()) {
                    r.method = e.getKey();
                    r.json = json;
                }
            }
        }
        if (r.method == null) {
            return failure("unknown_method", "Cannot map '" + methodId + "' to a declaration.", null);
        }
        return null;
    }

    /** The statement (a direct child of a block) a CFG node stands for, or null. */
    private static Statement statementOf(Resolved r, String nodeId) {
        Map<String, Object> node = r.nodes.get(nodeId);
        if (node == null) {
            return null;
        }
        String kind = (String) node.get("kind");
        if (kind.equals("entry") || kind.equals("exit")) {
            return null;
        }
        String statementKind = (String) node.get("statement_kind");
        if (statementKind != null && (statementKind.equals("for_init") || statementKind.equals("for_update"))) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> range = (Map<String, Object>) node.get("source_range");
        int start = ((Number) range.get("start")).intValue();
        int length = ((Number) range.get("length")).intValue();
        List<Statement> exact = new ArrayList<>();
        List<Statement> owners = new ArrayList<>();
        r.method.getBody().accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode n) {
                if (n instanceof Statement s && !(n instanceof Block) && s.getStartPosition() == start
                        && s.getLength() == length) {
                    exact.add(s);
                }
                if (n instanceof Expression e && e.getStartPosition() == start && e.getLength() == length
                        && e.getParent() instanceof Statement p && isConditionOf(e, p)) {
                    owners.add(p);
                }
            }
        });
        Statement found = exact.size() == 1 ? exact.get(0) : owners.size() == 1 ? owners.get(0) : null;
        if (found == null || !(found.getParent() instanceof Block)) {
            return null;
        }
        return found;
    }

    private static boolean isConditionOf(Expression e, Statement p) {
        StructuralPropertyDescriptor loc = e.getLocationInParent();
        return loc == IfStatement.EXPRESSION_PROPERTY || loc == WhileStatement.EXPRESSION_PROPERTY
                || loc == DoStatement.EXPRESSION_PROPERTY || loc == ForStatement.EXPRESSION_PROPERTY
                || loc == EnhancedForStatement.EXPRESSION_PROPERTY || loc == SwitchStatement.EXPRESSION_PROPERTY;
    }

    private static Map<String, Object> describe(Resolved r, Statement s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", s.getClass().getSimpleName());
        m.put("line", r.json.unit().getLineNumber(s.getStartPosition()));
        String text = AstJson.normalize(r.json.text(s));
        m.put("text", text.length() > 80 ? text.substring(0, 77) + "..." : text);
        m.put("source_range", r.json.range(s));
        return m;
    }

    private static boolean insideTry(ASTNode n) {
        for (ASTNode p = n.getParent(); p != null && !(p instanceof MethodDeclaration); p = p.getParent()) {
            if (p instanceof TryStatement) {
                return true;
            }
        }
        return false;
    }

    private static List<String> sorted(Set<String> keys) {
        List<String> list = new ArrayList<>();
        for (String k : keys) {
            list.add(displayKey(k));
        }
        java.util.Collections.sort(list);
        return list;
    }

    private static String displayKey(String key) {
        if (key.equals(HEAP) || key.equals(IO)) {
            return key;
        }
        int hash = key.lastIndexOf('#');
        return hash >= 0 ? key.substring(hash + 1) : key;
    }

    private static List<Object> dedupe(List<Object> reasons) {
        Set<String> seen = new HashSet<>();
        List<Object> out = new ArrayList<>();
        for (Object o : reasons) {
            if (seen.add(((Map<?, ?>) o).get("code") + "|" + ((Map<?, ?>) o).get("message"))) {
                out.add(o);
            }
        }
        return out;
    }

    // ---------- structural limits ----------

    private static void checkShape(Resolved r, Statement s, Footprint f, List<Object> reasons) {
        if (s instanceof ConstructorInvocation || s instanceof SuperConstructorInvocation) {
            reasons.add(reason("constructor_call", "An explicit constructor call must stay the first statement.", describe(r, s)));
        }
        if (s instanceof LabeledStatement) {
            reasons.add(reason("labeled_statement", "Labeled statements are not moved.", describe(r, s)));
        }
        if (s instanceof TypeDeclarationStatement || f.declaresType) {
            reasons.add(reason("local_type", "Statements that declare local classes are not moved.", describe(r, s)));
        }
    }

    // ---------- independence ----------

    private static List<Object> conflicts(Resolved r, Statement s, Footprint a, Statement other, Footprint b, boolean inTry) {
        List<Object> reasons = new ArrayList<>();
        Map<String, Object> ds = describe(r, s);
        Map<String, Object> dt = describe(r, other);
        Set<String> dependent = new LinkedHashSet<>();
        for (String w : a.writes) {
            if (b.reads.contains(w) || b.writes.contains(w)) {
                dependent.add(w);
            }
        }
        for (String w : b.writes) {
            if (a.reads.contains(w)) {
                dependent.add(w);
            }
        }
        if (!dependent.isEmpty()) {
            reasons.add(reason("data_dependency", "The statements depend on each other through "
                    + String.join(", ", sorted(dependent)) + ": one writes what the other reads or writes.", ds, dt));
        }
        if (a.abrupt || b.abrupt) {
            reasons.add(reason("control_flow", "A statement that can return, throw, break or continue cannot be reordered "
                    + "with its neighbours: whether the other statement runs would change.", ds, dt));
        }
        if (a.sync || b.sync) {
            reasons.add(reason("synchronization", "Synchronization, volatile access or thread/lock calls are not reordered.", ds, dt));
        }
        boolean aEffect = a.hasExternalEffect() || inTry && a.writesLocals();
        boolean bEffect = b.hasExternalEffect() || inTry && b.writesLocals();
        if (a.mayThrow && b.mayThrow) {
            reasons.add(reason("exception_order", "Both statements can throw; swapping them could change which exception "
                    + "(and which prior side effects) is observed.", ds, dt));
        } else if (a.mayThrow && bEffect || b.mayThrow && aEffect) {
            reasons.add(reason("exception_effects", "One statement can throw and the other has an observable effect"
                    + (inTry ? " (a local write is observable by a catch/finally block)" : "")
                    + "; their order decides whether the effect happens.", ds, dt));
        }
        return reasons;
    }

    /** What a statement subtree reads, writes and may do. Keys are binding keys, {@link #HEAP} or {@link #IO}. */
    static final class Footprint {
        final Set<String> reads = new LinkedHashSet<>();
        final Set<String> writes = new LinkedHashSet<>();
        final Set<String> localWrites = new LinkedHashSet<>();
        boolean mayThrow;
        boolean abrupt;
        boolean sync;
        boolean declaresType;

        boolean hasExternalEffect() {
            return writes.contains(HEAP) || writes.contains(IO);
        }

        boolean writesLocals() {
            return !localWrites.isEmpty();
        }

        static Footprint of(ProjectAnalyzer.Loaded loaded, Statement s) {
            Footprint f = new Footprint();
            s.accept(new Collector(loaded, f, s));
            return f;
        }
    }

    private static final class Collector extends ASTVisitor {
        private final ProjectAnalyzer.Loaded loaded;
        private final Footprint f;
        private final Statement root;
        private int loops;
        private int switches;

        Collector(ProjectAnalyzer.Loaded loaded, Footprint f, Statement root) {
            this.loaded = loaded;
            this.f = f;
            this.root = root;
        }

        private void unknown() {
            f.reads.add(HEAP);
            f.reads.add(IO);
            f.writes.add(HEAP);
            f.writes.add(IO);
            f.mayThrow = true;
        }

        // --- control flow
        @Override
        public boolean visit(ReturnStatement n) {
            f.abrupt = true;
            return true;
        }

        @Override
        public boolean visit(ThrowStatement n) {
            f.abrupt = true;
            f.mayThrow = true;
            return true;
        }

        @Override
        public boolean visit(BreakStatement n) {
            f.abrupt |= n.getLabel() != null || loops == 0 && switches == 0;
            return true;
        }

        @Override
        public boolean visit(ContinueStatement n) {
            f.abrupt |= n.getLabel() != null || loops == 0;
            return true;
        }

        @Override
        public boolean visit(YieldStatement n) {
            f.abrupt = true;
            return true;
        }

        @Override
        public boolean visit(AssertStatement n) {
            f.mayThrow = true;
            return true;
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

        @Override
        public boolean visit(SwitchStatement n) {
            switches++;
            return true;
        }

        @Override
        public void endVisit(SwitchStatement n) {
            switches--;
        }

        @Override
        public boolean visit(SynchronizedStatement n) {
            f.sync = true;
            return true;
        }

        @Override
        public boolean visit(TypeDeclarationStatement n) {
            f.declaresType = true;
            return false;
        }

        @Override
        public boolean visit(AnonymousClassDeclaration n) {
            f.declaresType = true;
            return false;
        }

        // --- deferred code: creating a lambda does not run it, but it reads what it captures
        @Override
        public boolean visit(LambdaExpression n) {
            n.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName name) {
                    if (name.resolveBinding() instanceof IVariableBinding vb && !vb.isField()) {
                        f.reads.add(vb.getKey());
                    }
                    return true;
                }
            });
            return false;
        }

        // --- variables
        @Override
        public boolean visit(VariableDeclarationFragment n) {
            IVariableBinding vb = n.resolveBinding();
            if (vb != null) {
                f.writes.add(vb.getKey());
                f.localWrites.add(vb.getKey());
            }
            if (n.getInitializer() != null) {
                n.getInitializer().accept(this); // the declared name itself is not a read
            }
            return false;
        }

        @Override
        public boolean visit(SingleVariableDeclaration n) {
            IVariableBinding vb = n.resolveBinding();
            if (vb != null) {
                f.writes.add(vb.getKey());
                f.localWrites.add(vb.getKey());
            }
            if (n.getInitializer() != null) {
                n.getInitializer().accept(this);
            }
            return false;
        }

        @Override
        public boolean visit(Assignment n) {
            target(n.getLeftHandSide(), n.getOperator() != Assignment.Operator.ASSIGN);
            n.getRightHandSide().accept(this);
            if (n.getOperator() == Assignment.Operator.DIVIDE_ASSIGN || n.getOperator() == Assignment.Operator.REMAINDER_ASSIGN) {
                f.mayThrow |= isIntegral(n.getLeftHandSide().resolveTypeBinding());
            }
            return false;
        }

        @Override
        public boolean visit(PostfixExpression n) {
            target(n.getOperand(), true);
            return false;
        }

        @Override
        public boolean visit(PrefixExpression n) {
            if (n.getOperator() == PrefixExpression.Operator.INCREMENT || n.getOperator() == PrefixExpression.Operator.DECREMENT) {
                target(n.getOperand(), true);
                return false;
            }
            return true;
        }

        private void target(Expression raw, boolean alsoRead) {
            Expression t = AstJson.strip(raw);
            switch (t) {
                case SimpleName n when n.resolveBinding() instanceof IVariableBinding vb -> {
                    if (vb.isField()) {
                        f.sync |= Modifier.isVolatile(vb.getModifiers());
                        f.writes.add(HEAP);
                        if (alsoRead) {
                            f.reads.add(HEAP);
                        }
                    } else {
                        f.writes.add(vb.getKey());
                        f.localWrites.add(vb.getKey());
                        if (alsoRead) {
                            f.reads.add(vb.getKey());
                        }
                    }
                }
                case ArrayAccess aa -> {
                    f.writes.add(HEAP);
                    f.reads.add(HEAP);
                    f.mayThrow = true;
                    aa.getArray().accept(this);
                    aa.getIndex().accept(this);
                }
                case FieldAccess fa -> {
                    f.sync |= fa.resolveFieldBinding() != null && Modifier.isVolatile(fa.resolveFieldBinding().getModifiers());
                    f.writes.add(HEAP);
                    if (alsoRead) {
                        f.reads.add(HEAP);
                    }
                    f.mayThrow |= !(fa.getExpression() instanceof ThisExpression);
                    fa.getExpression().accept(this);
                }
                case QualifiedName q -> {
                    f.sync |= q.resolveBinding() instanceof IVariableBinding qv && Modifier.isVolatile(qv.getModifiers());
                    f.writes.add(HEAP);
                    if (alsoRead) {
                        f.reads.add(HEAP);
                    }
                    f.mayThrow |= !(q.resolveBinding() instanceof IVariableBinding vb && java.lang.reflect.Modifier.isStatic(vb.getModifiers()));
                    q.getQualifier().accept(this);
                }
                default -> {
                    unknown();
                    t.accept(this);
                }
            }
        }

        @Override
        public boolean visit(SimpleName n) {
            if (n.resolveBinding() instanceof IVariableBinding vb) {
                if (vb.isField()) {
                    f.reads.add(HEAP);
                    if (Modifier.isVolatile(vb.getModifiers())) {
                        f.sync = true;
                    }
                } else {
                    f.reads.add(vb.getKey());
                }
            }
            return true;
        }

        @Override
        public boolean visit(QualifiedName n) {
            if (n.resolveBinding() instanceof IVariableBinding vb && vb.isField()) {
                f.reads.add(HEAP);
                if (Modifier.isVolatile(vb.getModifiers())) {
                    f.sync = true;
                }
                if (!Modifier.isStatic(vb.getModifiers()) && !(vb.getDeclaringClass() != null && vb.getName().equals("length")
                        && n.getQualifier().resolveTypeBinding() != null && n.getQualifier().resolveTypeBinding().isArray())) {
                    f.mayThrow = true;
                }
                n.getQualifier().accept(this);
                return false;
            }
            return true;
        }

        @Override
        public boolean visit(FieldAccess n) {
            f.reads.add(HEAP);
            if (!(n.getExpression() instanceof ThisExpression)) {
                f.mayThrow = true;
            }
            return true;
        }

        @Override
        public boolean visit(ArrayAccess n) {
            f.reads.add(HEAP);
            f.mayThrow = true;
            return true;
        }

        @Override
        public boolean visit(ArrayCreation n) {
            for (Object d : n.dimensions()) {
                if (((Expression) d).resolveConstantExpressionValue() == null) {
                    f.mayThrow = true;
                }
            }
            return true;
        }

        @Override
        public boolean visit(CastExpression n) {
            ITypeBinding t = n.getType().resolveBinding();
            f.mayThrow |= t != null && !t.isPrimitive();
            return true;
        }

        @Override
        public boolean visit(InfixExpression n) {
            InfixExpression.Operator op = n.getOperator();
            if ((op == InfixExpression.Operator.DIVIDE || op == InfixExpression.Operator.REMAINDER)
                    && isIntegral(n.resolveTypeBinding())) {
                Object divisor = n.getRightOperand().resolveConstantExpressionValue();
                if (n.hasExtendedOperands() || !(divisor instanceof Number num) || num.longValue() == 0) {
                    f.mayThrow = true;
                }
            }
            if (op == InfixExpression.Operator.PLUS && n.resolveTypeBinding() != null
                    && n.resolveTypeBinding().getQualifiedName().equals("java.lang.String")) {
                List<Expression> operands = new ArrayList<>();
                operands.add(n.getLeftOperand());
                operands.add(n.getRightOperand());
                for (Object o : n.extendedOperands()) {
                    operands.add((Expression) o);
                }
                for (Expression operand : operands) {
                    if (!stringConvertible(operand.resolveTypeBinding())) {
                        unknown(); // toString() of an arbitrary object runs arbitrary code
                    }
                }
            }
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation n) {
            ITypeBinding t = n.resolveTypeBinding();
            String name = t == null ? "" : t.getErasure().getQualifiedName();
            boolean jdkValue = name.startsWith("java.lang.") && !name.equals("java.lang.Thread") || name.startsWith("java.util.")
                    && !name.startsWith("java.util.concurrent");
            if (n.getAnonymousClassDeclaration() != null || !jdkValue) {
                unknown();
            } else {
                for (Object a : n.arguments()) {
                    ITypeBinding at = ((Expression) a).resolveTypeBinding();
                    if (at != null && !at.isPrimitive()) {
                        f.reads.add(HEAP);
                    }
                }
                f.mayThrow |= !n.arguments().isEmpty();
            }
            return true;
        }

        @Override
        public boolean visit(MethodInvocation n) {
            call(n, n.resolveMethodBinding(), n.getExpression(), n.arguments());
            return true;
        }

        @Override
        public boolean visit(SuperMethodInvocation n) {
            unknown();
            return true;
        }

        private void call(MethodInvocation n, IMethodBinding b, Expression receiver, List<?> args) {
            if (b == null) {
                unknown();
                return;
            }
            String decl = b.getDeclaringClass().getErasure().getQualifiedName();
            String name = b.getName();
            if (decl.startsWith("java.util.concurrent") || decl.equals("java.lang.Thread")
                    || Set.of("wait", "notify", "notifyAll", "join", "sleep", "lock", "unlock", "await", "signal", "signalAll")
                            .contains(name)) {
                f.sync = true;
                unknown();
                return;
            }
            switch (decl) {
                case "java.lang.Math", "java.lang.StrictMath" -> {
                    f.mayThrow |= name.endsWith("Exact") || name.startsWith("floor") && name.endsWith("Mod")
                            || name.equals("floorDiv") || name.equals("ceilDiv");
                    return;
                }
                case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Character",
                     "java.lang.Boolean", "java.lang.Double", "java.lang.Float", "java.util.Objects" -> {
                    f.mayThrow = true;
                    return;
                }
                case "java.lang.String" -> {
                    f.mayThrow |= !Set.of("length", "isEmpty", "equals", "hashCode", "isBlank").contains(name);
                    for (Object a : args) {
                        if (!stringConvertible(((Expression) a).resolveTypeBinding())) {
                            unknown();
                        }
                    }
                    return;
                }
                case "java.io.PrintStream" -> {
                    if (Set.of("println", "print", "printf", "format").contains(name)) {
                        for (Object a : args) {
                            if (!stringConvertible(((Expression) a).resolveTypeBinding())) {
                                unknown();
                                return;
                            }
                        }
                        f.writes.add(IO);
                        f.reads.add(IO);
                        return;
                    }
                }
                default -> {
                }
            }
            if (decl.startsWith("java.util.") && isCollectionType(decl)) {
                if (Set.of("get", "size", "isEmpty", "contains", "containsKey", "containsValue", "getOrDefault", "indexOf",
                        "peek", "first", "last", "peekFirst", "peekLast").contains(name)) {
                    f.reads.add(HEAP);
                    f.mayThrow |= !Set.of("size", "isEmpty").contains(name);
                    return;
                }
                if (Set.of("add", "put", "putIfAbsent", "remove", "addAll", "push", "offer", "poll", "pop", "set", "clear",
                        "addFirst", "addLast").contains(name)) {
                    f.reads.add(HEAP);
                    f.writes.add(HEAP);
                    f.mayThrow = true;
                    return;
                }
            }
            if (isTrivialAccessor(b)) {
                f.reads.add(HEAP);
                f.mayThrow |= receiver != null && !(receiver instanceof ThisExpression);
                return;
            }
            unknown();
        }

        private boolean isTrivialAccessor(IMethodBinding b) {
            IMethodBinding decl = b.getMethodDeclaration();
            for (AstJson peer : loaded.jsons()) {
                if (peer.unit().findDeclaringNode(decl) instanceof MethodDeclaration md) {
                    if (md.getBody() == null || md.getBody().statements().size() != 1 || !md.parameters().isEmpty()
                            || !(md.getBody().statements().get(0) instanceof ReturnStatement rs) || rs.getExpression() == null) {
                        return false;
                    }
                    Expression v = AstJson.strip(rs.getExpression());
                    return (v instanceof SimpleName sn && sn.resolveBinding() instanceof IVariableBinding vb && vb.isField()
                            || v instanceof FieldAccess fa && fa.getExpression() instanceof ThisExpression)
                            && (Modifier.isPrivate(decl.getModifiers()) || Modifier.isFinal(decl.getModifiers())
                            || Modifier.isFinal(decl.getDeclaringClass().getModifiers()) || decl.getDeclaringClass().isRecord());
                }
            }
            return decl.getDeclaringClass().isRecord() && decl.getParameterTypes().length == 0;
        }

        private static boolean isCollectionType(String decl) {
            return Set.of("java.util.List", "java.util.ArrayList", "java.util.LinkedList", "java.util.Collection",
                    "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet", "java.util.Map",
                    "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.Deque", "java.util.ArrayDeque",
                    "java.util.Queue", "java.util.SortedMap", "java.util.SortedSet").contains(decl);
        }

        private static boolean stringConvertible(ITypeBinding t) {
            return t != null && (t.isPrimitive() || t.getQualifiedName().equals("java.lang.String")
                    || Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Boolean",
                            "java.lang.Character", "java.lang.Short", "java.lang.Byte", "java.lang.Float").contains(t.getQualifiedName()));
        }

        private static boolean isIntegral(ITypeBinding t) {
            return t != null && Set.of("int", "long", "short", "byte", "char").contains(t.getName());
        }
    }

    // ---------- candidate validation ----------

    @SuppressWarnings("unchecked")
    private static List<String> newErrors(Map<String, Object> baseline, Map<String, Object> candidate) {
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
                fresh.add(String.valueOf(d.get("message")));
            }
        }
        return fresh;
    }

    @SuppressWarnings("unchecked")
    private static String errorKey(Map<String, Object> d) {
        Map<String, Object> range = (Map<String, Object>) d.get("source_range");
        return (range == null ? "" : range.get("file")) + "|" + d.get("code") + "|" + d.get("message");
    }

    /**
     * Old CFG node id to new CFG node id, matched by (kind, statement kind, text) in order of appearance. Node ids
     * are positional, so every node after the moved one usually gets a new id; this is how a client re-links them.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> nodeMapping(Map<String, Object> before, Map<String, Object> after) {
        Map<String, List<String>> bucket = new HashMap<>();
        for (Object o : (List<Object>) ((Map<String, Object>) after.get("cfg")).get("nodes")) {
            Map<String, Object> n = (Map<String, Object>) o;
            bucket.computeIfAbsent(signature(n), k -> new ArrayList<>()).add((String) n.get("id"));
        }
        Map<String, Integer> used = new HashMap<>();
        List<Object> mapping = new ArrayList<>();
        for (Object o : (List<Object>) ((Map<String, Object>) before.get("cfg")).get("nodes")) {
            Map<String, Object> n = (Map<String, Object>) o;
            String sig = signature(n);
            List<String> candidates = bucket.getOrDefault(sig, List.of());
            int index = used.merge(sig, 1, Integer::sum) - 1;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("old_id", n.get("id"));
            m.put("new_id", index < candidates.size() ? candidates.get(index) : null);
            mapping.add(m);
        }
        return mapping;
    }

    private static String signature(Map<String, Object> node) {
        return node.get("kind") + "|" + node.get("statement_kind") + "|" + node.get("text");
    }
}
