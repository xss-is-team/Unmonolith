package org.example;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

/**
 * {@code java -jar parser.jar transform --input F.java --operation op.json --output Out.java}.
 *
 * Applies one {@code replace_condition} operation: the CFG is rebuilt from the input, the CFG node
 * is resolved to its owning JDT expression (by exact source range, never by text search), the
 * expression is swapped with {@link ASTRewrite}, and the result is reparsed and validated. The
 * output file is written only after every check passed. The source is never compiled or executed.
 *
 * stdout: exactly one JSON document. Exit: 0 completed, 1 failed, 2 usage error.
 */
final class Transform {
    private Transform() {
    }

    /** A rejected transformation; {@code code} is part of the JSON contract. */
    private static final class Rejected extends Exception {
        final String code;

        Rejected(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private record Request(String methodId, String nodeId, String expression) {
    }

    /** What the CFG node is: the statement kind plus the condition expression's source range. */
    private record Target(String statementKind, int start, int length) {
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            String name = args[i];
            if (!Set.of("--input", "--operation", "--output").contains(name) || i + 1 >= args.length
                    || opts.putIfAbsent(name, args[i + 1]) != null) {
                return usage(out, err);
            }
        }
        if (opts.size() != 3) {
            return usage(out, err);
        }
        Path input = Path.of(opts.get("--input"));
        Path operation = Path.of(opts.get("--operation"));
        Path output = Path.of(opts.get("--output"));

        try {
            Request request = readRequest(operation);
            String source = readUtf8(input);
            if (Files.exists(output) && Files.isSameFile(input, output)) {
                throw new Rejected("invalid_arguments", "--output must not be the input file");
            }

            List<IProblem> warnings = new ArrayList<>();
            String transformed = replaceCondition(source, input.getFileName().toString(), request, warnings);
            writeAtomically(output, transformed);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "completed");
            result.put("operation", "replace_condition");
            result.put("method_id", request.methodId());
            result.put("node_id", request.nodeId());
            result.put("output_file", opts.get("--output"));
            result.put("diagnostics", diagnostics(warnings));
            out.println(Json.pretty(result));
            return 0;
        } catch (Rejected e) {
            err.println("transform rejected [" + e.code + "]: " + e.getMessage());
            return fail(out, e.code, e.getMessage());
        } catch (IOException | RuntimeException | StackOverflowError e) {
            e.printStackTrace(err);
            return fail(out, "internal_error", "Transformation failed: " + e);
        }
    }

    private static int usage(PrintStream out, PrintStream err) {
        err.println("usage: java -jar parser.jar transform --input <file.java> --operation <op.json> --output <out.java>");
        fail(out, "invalid_arguments", "Expected exactly --input, --operation and --output");
        return 2;
    }

    private static int fail(PrintStream out, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "failed");
        result.put("error", error);
        out.println(Json.pretty(result));
        return 1;
    }

    // ---------- input ----------

    private static String readUtf8(Path file) throws Rejected {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        } catch (CharacterCodingException e) {
            throw new Rejected("input_unreadable", "Input file is not valid UTF-8");
        } catch (IOException | RuntimeException e) {
            throw new Rejected("input_unreadable", "Cannot read input file");
        }
    }

    private static Request readRequest(Path file) throws Rejected {
        JsonNode root;
        try (JsonParser parser = JSON.getFactory().createParser(readUtf8(file))) {
            root = JSON.readTree(parser);
            if (parser.nextToken() != null) {
                throw new Rejected("invalid_operation", "Operation file holds more than one JSON document");
            }
        } catch (IOException e) {
            throw new Rejected("invalid_operation", "Operation file is not valid JSON");
        }
        requireObject(root, Set.of("method_id", "node_id", "operation"), "operation file");
        JsonNode op = root.get("operation");
        requireObject(op, Set.of("type", "expression"), "operation");
        String type = text(op, "type");
        if (!type.equals("replace_condition")) {
            throw new Rejected("invalid_operation", "Unsupported operation type: " + type);
        }
        return new Request(text(root, "method_id"), text(root, "node_id"), text(op, "expression"));
    }

    private static void requireObject(JsonNode node, Set<String> fields, String what) throws Rejected {
        if (node == null || !node.isObject()) {
            throw new Rejected("invalid_operation", "The " + what + " must be a JSON object");
        }
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!fields.contains(name)) {
                throw new Rejected("invalid_operation", "Unknown field '" + name + "' in " + what);
            }
        }
        for (String f : fields) {
            if (!node.has(f)) {
                throw new Rejected("invalid_operation", "Missing field '" + f + "' in " + what);
            }
        }
    }

    private static String text(JsonNode node, String field) throws Rejected {
        JsonNode v = node.get(field);
        if (!v.isTextual() || v.asText().isBlank()) {
            throw new Rejected("invalid_operation", "Field '" + field + "' must be a non-empty string");
        }
        return v.asText();
    }

    // ---------- transformation ----------

    private static String replaceCondition(String source, String unitName, Request req, List<IProblem> warnings)
            throws Rejected {
        // 1. Rebuild the CFG exactly as analysis mode does and resolve method + node from it.
        Map<String, Object> analysis = Main.parse(source, unitName);
        Map<String, Object> method = findMethod(analysis, req.methodId());
        Target target = findTarget(method, req.nodeId());

        // 2. Map the CFG node to its owning JDT expression.
        CompilationUnit cu = Main.createAst(source, unitName, true);
        MethodDeclaration md = findMethodDeclaration(cu, method);
        Expression old = findCondition(md, target);

        // 3. Validate the replacement text on its own.
        String replacement = req.expression().strip();
        checkExpressionSyntax(replacement);

        // 4. Rewrite just that node.
        ASTRewrite rewrite = ASTRewrite.create(cu.getAST());
        rewrite.replace(old, rewrite.createStringPlaceholder(replacement, ASTNode.SIMPLE_NAME), null);
        Document document = new Document(source);
        try {
            TextEdit edit = rewrite.rewriteAST(document, JavaCore.getOptions());
            edit.apply(document);
        } catch (BadLocationException | RuntimeException e) {
            throw new Rejected("validation_failed", "Could not apply the edit: " + e);
        }
        String result = document.get();

        // 5. Reparse and compare against the original: no new errors allowed.
        validate(source, result, unitName, old.getStartPosition(), replacement, target, warnings);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findMethod(Object ast, String methodId) throws Rejected {
        List<Map<String, Object>> found = new ArrayList<>();
        collectMethods(ast, methodId, found);
        if (found.isEmpty()) {
            throw new Rejected("unknown_method", "No method with id '" + methodId + "' that has a control-flow graph");
        }
        if (found.size() > 1) {
            throw new Rejected("unknown_method", "Method id '" + methodId + "' is ambiguous");
        }
        return found.get(0);
    }

    @SuppressWarnings("unchecked")
    private static void collectMethods(Object o, String methodId, List<Map<String, Object>> found) {
        if (o instanceof Map<?, ?> m) {
            if (methodId.equals(m.get("id")) && m.get("cfg") instanceof Map<?, ?>) {
                found.add((Map<String, Object>) m);
            }
            for (Object v : m.values()) {
                collectMethods(v, methodId, found);
            }
        } else if (o instanceof List<?> l) {
            for (Object v : l) {
                collectMethods(v, methodId, found);
            }
        }
    }

    private static Target findTarget(Map<String, Object> method, String nodeId) throws Rejected {
        Map<?, ?> cfg = (Map<?, ?>) method.get("cfg");
        Map<?, ?> match = null;
        for (Object o : (List<?>) cfg.get("nodes")) {
            Map<?, ?> n = (Map<?, ?>) o;
            if (nodeId.equals(n.get("id"))) {
                if (match != null) {
                    throw new Rejected("unknown_node", "Node id '" + nodeId + "' is ambiguous");
                }
                match = n;
            }
        }
        if (match == null) {
            throw new Rejected("unknown_node", "No node '" + nodeId + "' in the control-flow graph of the method");
        }
        Object statementKind = match.get("statement_kind");
        boolean condition = "branch".equals(match.get("kind"))
                && Set.of("if", "while", "do_while", "for").contains(statementKind);
        if (!condition) {
            throw new Rejected("unsupported_node", "Node '" + nodeId + "' (" + match.get("kind")
                    + (statementKind == null ? "" : "/" + statementKind)
                    + ") is not the condition of an if, while, do/while or for statement");
        }
        Map<?, ?> range = (Map<?, ?>) match.get("source_range");
        return new Target((String) statementKind, ((Number) range.get("start")).intValue(),
                ((Number) range.get("length")).intValue());
    }

    private static MethodDeclaration findMethodDeclaration(CompilationUnit cu, Map<String, Object> method)
            throws Rejected {
        Map<?, ?> range = (Map<?, ?>) method.get("source_range");
        int start = ((Number) range.get("start")).intValue();
        int length = ((Number) range.get("length")).intValue();
        List<MethodDeclaration> found = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.getStartPosition() == start && node.getLength() == length) {
                    found.add(node);
                }
                return true;
            }
        });
        if (found.size() != 1) {
            throw new Rejected("unknown_method", "Cannot map method '" + method.get("id") + "' to a single declaration");
        }
        return found.get(0);
    }

    private static Expression findCondition(MethodDeclaration md, Target target) throws Rejected {
        List<Expression> found = new ArrayList<>();
        md.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (node instanceof Expression e && e.getStartPosition() == target.start()
                        && e.getLength() == target.length() && isConditionOf(e, target.statementKind())) {
                    found.add(e);
                }
            }
        });
        if (found.size() != 1) {
            throw new Rejected("unsupported_node", "Cannot map the node to exactly one condition expression");
        }
        return found.get(0);
    }

    private static boolean isConditionOf(Expression e, String statementKind) {
        StructuralPropertyDescriptor loc = e.getLocationInParent();
        return switch (statementKind) {
            case "if" -> loc == IfStatement.EXPRESSION_PROPERTY;
            case "while" -> loc == WhileStatement.EXPRESSION_PROPERTY;
            case "do_while" -> loc == DoStatement.EXPRESSION_PROPERTY;
            case "for" -> loc == ForStatement.EXPRESSION_PROPERTY;
            default -> false;
        };
    }

    /** The text must be exactly one syntactically valid Java expression. */
    private static void checkExpressionSyntax(String text) throws Rejected {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(text.toCharArray());
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.VERSION_21, options);
        parser.setCompilerOptions(options);
        ASTNode node = parser.createAST(null);
        boolean whole = node instanceof Expression && node.getStartPosition() == 0 && node.getLength() == text.length();
        boolean damaged = node != null && (node.getFlags() & (ASTNode.MALFORMED | ASTNode.RECOVERED)) != 0;
        if (!whole || damaged) {
            throw new Rejected("invalid_expression", "Replacement expression contains a syntax error");
        }
    }

    private static void validate(String before, String after, String unitName, int start, String replacement,
                                 Target target, List<IProblem> warnings) throws Rejected {
        CompilationUnit oldCu = Main.createAst(before, unitName, true);
        CompilationUnit newCu = Main.createAst(after, unitName, true);

        // The replacement must now sit, intact, in the same condition slot.
        List<Expression> slot = new ArrayList<>();
        newCu.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (node instanceof Expression e && e.getStartPosition() == start
                        && e.getLength() == replacement.length() && isConditionOf(e, target.statementKind())) {
                    slot.add(e);
                }
            }
        });
        if (slot.size() != 1 || !after.startsWith(replacement, start)) {
            throw new Rejected("invalid_expression", "Replacement expression does not parse as a single condition");
        }

        Map<String, Integer> known = new HashMap<>();
        for (IProblem p : oldCu.getProblems()) {
            known.merge(p.getID() + ":" + p.getMessage(), 1, Integer::sum);
        }
        for (IProblem p : newCu.getProblems()) {
            String key = p.getID() + ":" + p.getMessage();
            boolean preexisting = known.merge(key, -1, Integer::sum) >= 0;
            if (preexisting) {
                continue;
            }
            if (p.isError()) {
                boolean syntax = (p.getID() & IProblem.Syntax) != 0;
                throw new Rejected(syntax ? "invalid_expression" : "validation_failed",
                        (syntax ? "Replacement introduces a syntax error: " : "Replacement fails validation: ")
                                + p.getMessage() + " (line " + p.getSourceLineNumber() + ")");
            }
            warnings.add(p);
        }
    }

    // ---------- output ----------

    private static void writeAtomically(Path output, String content) throws Rejected {
        Path dir = output.toAbsolutePath().getParent();
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, ".transform-", ".tmp");
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
            throw new Rejected("output_unwritable", "Cannot write output file");
        }
    }

    private static List<Object> diagnostics(List<IProblem> problems) {
        List<Object> list = new ArrayList<>();
        for (IProblem p : problems) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("code", Integer.toString(p.getID()));
            d.put("severity", "warning");
            d.put("message", p.getMessage());
            Map<String, Object> range = new LinkedHashMap<>();
            range.put("start", p.getSourceStart());
            range.put("length", Math.max(0, p.getSourceEnd() - p.getSourceStart() + 1));
            range.put("line", p.getSourceLineNumber());
            d.put("source_range", range);
            list.add(d);
        }
        return list;
    }
}
