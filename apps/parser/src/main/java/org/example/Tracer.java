package org.example;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EmptyStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.LabeledStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchExpression;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.SynchronizedStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;

/**
 * {@code trace}: writes a copy of the project in which every statement of a block counts how often it starts, so a
 * run in the Docker runner can show the executed path on the CFG. A counter is keyed by the original file and the
 * source offset the CFG node of that statement uses (the condition of an if/while/switch, the parameter of a
 * for-each, the statement itself otherwise); a loop body also counts its iterations ({@code <key>:i}). The counters
 * live in {@code System.getProperties()} and a shutdown hook added to every {@code main(String[])} prints them to
 * stderr as {@code #BPTRACE <key> <count>} lines. Java 8 syntax only, no helper class, nothing outside the copy changes.
 */
final class Tracer {
    static final String MARKER = "#BPTRACE ";
    private static final ObjectMapper JSON = new ObjectMapper();

    private Tracer() {
    }

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
                    err.println("usage: trace --root DIR --input request.json --output-dir OUT");
                    return 2;
                }
            }
        }
        if (root == null || input == null || outputDir == null) {
            err.println("usage: trace --root DIR --input request.json --output-dir OUT");
            return 2;
        }
        try {
            JsonNode request = JSON.readTree(Files.readAllBytes(Path.of(input)));
            String level = request.path("source_level").asText(ProjectAnalyzer.DEFAULT_SOURCE_LEVEL);
            List<String> files = new ArrayList<>();
            request.path("files").forEach(f -> files.add(f.asText()));
            int counters = 0;
            for (String file : files) {
                Path source = Path.of(root).resolve(file).normalize();
                if (!source.startsWith(Path.of(root).normalize())) {
                    return fail(out, "invalid_request", "File outside the project: " + file);
                }
                String text = Files.readString(source, StandardCharsets.UTF_8);
                Instrumented result = instrument(file, text, level);
                counters += result.counters();
                Path target = Path.of(outputDir).resolve(file);
                Files.createDirectories(target.getParent());
                Files.writeString(target, result.source(), StandardCharsets.UTF_8);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "completed");
            result.put("files", files);
            result.put("counters", counters);
            out.println(Json.pretty(result));
            return 0;
        } catch (IOException | RuntimeException | BadLocationException | StackOverflowError e) {
            e.printStackTrace(err);
            return fail(out, "internal_error", "Tracer failed: " + e);
        }
    }

    record Instrumented(String source, int counters) {
    }

    static Instrumented instrument(String file, String source, String level) throws BadLocationException {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(level.equals("8") ? JavaCore.VERSION_1_8 : level, options);
        parser.setCompilerOptions(options);
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        ASTRewrite rewrite = ASTRewrite.create(cu.getAST());
        int[] counters = {0};
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(Block block) {
                ListRewrite list = rewrite.getListRewrite(block, Block.STATEMENTS_PROPERTY);
                List<?> statements = block.statements();
                for (int i = 0; i < statements.size(); i++) {
                    Statement s = (Statement) statements.get(i);
                    if (!counted(s)) {
                        continue;
                    }
                    list.insertBefore(hit(rewrite, file + ":" + anchor(s)), s, null);
                    counters[0]++;
                }
                Statement loop = block.getParent() instanceof Statement p && loopBody(p) == block ? p : null;
                if (loop != null) {
                    list.insertFirst(hit(rewrite, file + ":" + anchor(loop) + ":i"), null);
                }
                if (block.getParent() instanceof MethodDeclaration m && isMain(m)) {
                    list.insertFirst(rewrite.createStringPlaceholder(HOOK, ASTNode.EXPRESSION_STATEMENT), null);
                }
                return true;
            }

            @Override
            public boolean visit(SwitchStatement sw) {
                colonCases(sw.statements(), rewrite.getListRewrite(sw, SwitchStatement.STATEMENTS_PROPERTY));
                return true;
            }

            @Override
            public boolean visit(SwitchExpression sw) {
                colonCases(sw.statements(), rewrite.getListRewrite(sw, SwitchExpression.STATEMENTS_PROPERTY));
                return true;
            }

            /** {@code case X:} groups take more statements; {@code case X ->} rules hold exactly one, so they are left as they are. */
            private void colonCases(List<?> statements, ListRewrite list) {
                if (statements.stream().anyMatch(o -> o instanceof SwitchCase c && c.isSwitchLabeledRule())) {
                    return;
                }
                for (Object o : statements) {
                    if (!(o instanceof SwitchCase) && counted((Statement) o)) {
                        list.insertBefore(hit(rewrite, file + ":" + anchor((Statement) o)), (Statement) o, null);
                        counters[0]++;
                    }
                }
            }
        });
        Document document = new Document(source);
        rewrite.rewriteAST(document, options).apply(document);
        return new Instrumented(document.get(), counters[0]);
    }

    private static final String HOOK = "java.lang.Runtime.getRuntime().addShutdownHook(new java.lang.Thread(new java.lang.Runnable() { "
            + "public void run() { for (java.util.Map.Entry<Object, Object> bpEntry : new java.util.ArrayList<java.util.Map.Entry<Object, Object>>("
            + "java.lang.System.getProperties().entrySet())) { String bpKey = String.valueOf(bpEntry.getKey()); if (bpKey.startsWith(\"bp:\")) { "
            + "java.lang.System.err.println(\"" + MARKER + "\" + bpKey.substring(3) + \" \" + bpEntry.getValue()); } } } }));";

    private static ASTNode hit(ASTRewrite rewrite, String key) {
        return rewrite.createStringPlaceholder("java.lang.System.getProperties().merge(\"bp:" + key
                + "\", 1, (bpCount, bpOne) -> (Integer) bpCount + (Integer) bpOne);", ASTNode.EXPRESSION_STATEMENT);
    }

    /**
     * Statements that have a CFG node of their own. A {@code try}, a nested block and an empty statement have none
     * (their inner statements do); {@code this(...)} / {@code super(...)} must stay the first statement.
     */
    private static boolean counted(Statement s) {
        return !(s instanceof TryStatement || s instanceof Block || s instanceof EmptyStatement
                || s instanceof ConstructorInvocation || s instanceof SuperConstructorInvocation);
    }

    /** The offset the CFG uses for this statement's node. */
    static int anchor(Statement s) {
        return switch (s) {
            case IfStatement st -> st.getExpression().getStartPosition();
            case WhileStatement st -> st.getExpression().getStartPosition();
            case DoStatement st -> st.getExpression().getStartPosition();
            case SwitchStatement st -> st.getExpression().getStartPosition();
            case SynchronizedStatement st -> st.getExpression().getStartPosition();
            case EnhancedForStatement st -> st.getParameter().getStartPosition();
            case ForStatement st -> st.getExpression() != null ? st.getExpression().getStartPosition() : st.getStartPosition();
            case LabeledStatement st -> anchor(st.getBody());
            default -> s.getStartPosition();
        };
    }

    private static Statement loopBody(Statement s) {
        return switch (s) {
            case WhileStatement st -> st.getBody();
            case DoStatement st -> st.getBody();
            case ForStatement st -> st.getBody();
            case EnhancedForStatement st -> st.getBody();
            default -> null;
        };
    }

    private static boolean isMain(MethodDeclaration m) {
        if (!m.getName().getIdentifier().equals("main") || m.parameters().size() != 1 || !Modifier.isStatic(m.getModifiers())) {
            return false;
        }
        String type = ((SingleVariableDeclaration) m.parameters().get(0)).getType().toString();
        return type.equals("String[]") || type.equals("java.lang.String[]") || ((SingleVariableDeclaration) m.parameters().get(0)).isVarargs();
    }

    private static int fail(PrintStream out, String code, String message) {
        out.println(Json.pretty(Decomposer.failure(code, message)));
        return 1;
    }
}
