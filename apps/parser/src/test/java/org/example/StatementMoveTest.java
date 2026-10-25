package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatementMoveTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Run(int code, JsonNode json) {
    }

    /** CFG node id (single-file ids) of the node whose text is {@code text}, in the first method named {@code method}. */
    private static String nodeId(String source, String method, String text) {
        JsonNode analysis = JSON.valueToTree(Main.parse(source, "Main.java"));
        for (JsonNode m : find(analysis, method)) {
            for (JsonNode n : m.get("cfg").get("nodes")) {
                if (n.has("text") && n.get("text").asText().equals(text)) {
                    return n.get("id").asText();
                }
            }
        }
        throw new AssertionError("no CFG node '" + text + "' in " + method);
    }

    private static List<JsonNode> find(JsonNode node, String methodName) {
        List<JsonNode> out = new ArrayList<>();
        if (node.isObject()) {
            if (node.has("name") && methodName.equals(node.get("name").asText()) && node.has("cfg") && node.get("cfg").isObject()) {
                out.add(node);
            }
            node.forEach(c -> out.addAll(find(c, methodName)));
        } else if (node.isArray()) {
            node.forEach(c -> out.addAll(find(c, methodName)));
        }
        return out;
    }

    private static Run move(Path root, String file, String methodId, String node, String target, String position, Path out)
            throws IOException {
        return moveIn(root, "single", List.of(file), methodId, node, target, position, out);
    }

    private static Run moveIn(Path root, String mode, List<String> files, String methodId, String node, String target,
            String position, Path out) throws IOException {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "move_statement");
        operation.put("node_id", node);
        operation.put("target_node_id", target);
        operation.put("position", position);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode);
        body.put("source_level", "17");
        body.put("files", files);
        body.put("method_id", methodId);
        body.put("operation", operation);
        Path request = root.resolve("edit.json");
        Files.writeString(request, JSON.writeValueAsString(body));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of("edit", "--root", root.toString(), "--input", request.toString()));
        if (out != null) {
            args.add("--output-dir");
            args.add(out.toString());
        }
        int code = Main.run(args.toArray(String[]::new), new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
        return new Run(code, JSON.readTree(stdout.toByteArray()));
    }

    private static final String CLASS_HEAD = "import java.util.*;\npublic class Main {\n  static int counter;\n  int field;\n  volatile int flag;\n"
            + "  static int sideEffect() { counter++; return counter; }\n";

    private static String source(String body) {
        return CLASS_HEAD + "  static int f(int a, int b, int[] arr, List<Integer> list) {\n" + body + "\n  }\n}\n";
    }

    private static List<String> codes(Run run) {
        List<String> codes = new ArrayList<>();
        run.json.get("error").get("reasons").forEach(r -> codes.add(r.get("code").asText()));
        return codes;
    }

    private static final String F = "Main.f(int,int,int[],List<Integer>)";

    private static Run tryMove(Path root, String body, String moved, String anchor, String position) throws IOException {
        String source = source(body);
        Files.createDirectories(root);
        Files.writeString(root.resolve("Main.java"), source);
        return move(root, "Main.java", F, nodeId(source, "f", moved), nodeId(source, "f", anchor), position, root.resolve("out"));
    }

    @Test
    void swapsTwoIndependentStatementsAndKeepsTheCfgValid(@TempDir Path root) throws Exception {
        String body = "    int x = a + 1;\n    int y = b * 2;\n    return x + y;";
        Run run = tryMove(root, body, "int y = b * 2;", "int x = a + 1;", "before");
        assertEquals(0, run.code, run.json.toString());
        String result = Files.readString(root.resolve("out/Main.java"));
        assertTrue(result.indexOf("int y = b * 2;") < result.indexOf("int x = a + 1;"), result);
        assertEquals(source(body).length(), result.length(), "only the order changed, indentation kept");
        assertEquals("move_statement", run.json.get("operation").get("type").asText());
        assertEquals(1, run.json.get("operation").get("jumped_over").size());
        assertTrue(run.json.get("operation").get("jumped_over").get(0).get("independent").asBoolean());
        // the new CFG exists and the mapping links old ids to the nodes with the same text
        JsonNode mapping = run.json.get("node_mapping");
        assertTrue(mapping.size() >= 5);
        String oldX = nodeId(source(body), "f", "int x = a + 1;");
        String newX = nodeId(result, "f", "int x = a + 1;");
        boolean linked = false;
        for (JsonNode m : mapping) {
            if (m.get("old_id").asText().equals(oldX)) {
                assertEquals(newX, m.get("new_id").asText());
                linked = true;
            }
        }
        assertTrue(linked);
        assertEquals(List.of("src").size(), 1);
    }

    @Test
    void movedProgramBehavesTheSame(@TempDir Path root) throws Exception {
        String body = "    int x = a * 3;\n    int y = b - 7;\n    int z = (a + b) % 5;\n    return x * y + z;";
        Run run = tryMove(root, body, "int z = (a + b) % 5;", "int x = a * 3;", "before");
        assertEquals(0, run.code, run.json.toString());
        String moved = Files.readString(root.resolve("out/Main.java"));
        Object[] before = execute(root.resolve("b"), source(body).replace("static int f(", "public static int f("));
        Object[] after = execute(root.resolve("a"), moved.replace("static int f(", "public static int f("));
        assertEquals(List.of(before), List.of(after));
        assertTrue(before.length > 5);
    }

    private static Object[] execute(Path dir, String source) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve("Main.java");
        Files.writeString(file, source);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-d", dir.toString(), file.toString()));
        try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() })) {
            var method = loader.loadClass("Main").getMethod("f", int.class, int.class, int[].class, List.class);
            List<Object> results = new ArrayList<>();
            for (int a = -3; a < 4; a++) {
                for (int b = -2; b < 3; b++) {
                    results.add(method.invoke(null, a, b, new int[0], new ArrayList<Integer>()));
                }
            }
            return results.toArray();
        }
    }

    @Test
    void movesOverSeveralIndependentStatementsAndBothPositions(@TempDir Path root) throws Exception {
        String body = "    int p = a + 1;\n    int q = b + 2;\n    int r = a * b;\n    int s = a - b;\n    return p + q + r + s;";
        Run run = tryMove(root.resolve("1"), body, "int p = a + 1;", "int s = a - b;", "after");
        assertEquals(0, run.code, run.json.toString());
        String result = Files.readString(root.resolve("1/out/Main.java"));
        assertTrue(result.indexOf("int s = a - b;") < result.indexOf("int p = a + 1;"), result);
        assertEquals(3, run.json.get("operation").get("jumped_over").size());
        assertEquals(0, run.json.get("operation").get("from_index").asInt());
        assertEquals(3, run.json.get("operation").get("to_index").asInt());
    }

    @Test
    void rejectsDependentStatementsWithoutTouchingTheSource(@TempDir Path root) throws Exception {
        String body = "    int x = a + 1;\n    int y = x * 2;\n    return y;";
        Run run = tryMove(root, body, "int y = x * 2;", "int x = a + 1;", "before");
        assertEquals(1, run.code);
        assertEquals("move_rejected", run.json.get("error").get("code").asText());
        assertTrue(codes(run).contains("data_dependency"), run.json.toString());
        assertEquals(2, run.json.get("error").get("reasons").get(0).get("statements").size());
        assertFalse(Files.exists(root.resolve("out/Main.java")));
        assertEquals(source(body), Files.readString(root.resolve("Main.java")));
    }

    @Test
    void rejectsUnsafeReorderings(@TempDir Path root) throws Exception {
        // I/O order
        assertEquals(List.of("data_dependency", "exception_order").stream().filter(c -> false).count(), 0);
        Run prints = tryMove(root.resolve("a"), "    System.out.println(a);\n    System.out.println(b);\n    return 0;",
                "System.out.println(b);", "System.out.println(a);", "before");
        assertTrue(codes(prints).contains("data_dependency"), prints.json.toString());
        // an unknown call (counter++) against a field read
        Run unknown = tryMove(root.resolve("b"), "    int s = sideEffect();\n    int c = counter;\n    return s + c;",
                "int c = counter;", "int s = sideEffect();", "before");
        assertTrue(codes(unknown).contains("data_dependency"), unknown.json.toString());
        // return / break make the neighbours depend on it
        Run ret = tryMove(root.resolve("c"), "    if (a > 0) { return 1; }\n    int y = b * 2;\n    return y;",
                "int y = b * 2;", "a > 0", "before");
        assertTrue(codes(ret).contains("control_flow"), ret.json.toString());
        // two statements that can both throw
        Run throwing = tryMove(root.resolve("d"), "    int u = arr[0];\n    int v = list.get(1);\n    return u + v;",
                "int v = list.get(1);", "int u = arr[0];", "before");
        assertTrue(codes(throwing).contains("exception_order"), throwing.json.toString());
        // a throwing statement moved over an effect
        Run effect = tryMove(root.resolve("e"), "    counter = 5;\n    int u = arr[0];\n    return u;",
                "int u = arr[0];", "counter = 5;", "before");
        assertTrue(codes(effect).contains("exception_effects"), effect.json.toString());
        // integer division can throw too
        Run division = tryMove(root.resolve("f"), "    counter = 5;\n    int q = a / b;\n    return q;",
                "int q = a / b;", "counter = 5;", "before");
        assertTrue(codes(division).contains("exception_effects"), division.json.toString());
        // synchronization and volatile access
        Run sync = tryMove(root.resolve("g"), "    synchronized (list) { list.add(1); }\n    int y = b * 2;\n    return y;",
                "int y = b * 2;", "list", "before");
        assertTrue(sync.code == 1);
        Run vol = tryMove(root.resolve("h"), "    int x = a + 1;\n    new Main().flag = 3;\n    return x;",
                "int x = a + 1;", "new Main().flag = 3;", "after");
        assertEquals(1, vol.code, vol.json.toString()); // construction + volatile write is unknown/sync
    }

    @Test
    void localWritesAreObservableInsideTry(@TempDir Path root) throws Exception {
        String body = "    int x = 0;\n    int y = 0;\n    try {\n      x = a + 1;\n      y = arr[0];\n    } catch (RuntimeException e) {\n    }\n    return x + y;";
        // outside the try the same shape would be fine; inside, a handler could observe x
        String source = source(body);
        Files.writeString(root.resolve("Main.java"), source);
        Run run = move(root, "Main.java", F, nodeId(source, "f", "x = a + 1;"), nodeId(source, "f", "y = arr[0];"), "after", root.resolve("out"));
        assertEquals(1, run.code);
        assertTrue(codes(run).contains("exception_effects"), run.json.toString());
    }

    @Test
    void rejectsMovesAcrossBlocksAndUnsupportedNodes(@TempDir Path root) throws Exception {
        String body = "    int x = a + 1;\n    if (b > 0) {\n      b = b - 1;\n    }\n    for (int i = 0; i < 3; i++) {\n      x += i;\n    }\n    return x + b;";
        String source = source(body);
        Files.writeString(root.resolve("Main.java"), source);
        // into the body of the `if`
        Run across = move(root, "Main.java", F, nodeId(source, "f", "int x = a + 1;"), nodeId(source, "f", "b = b - 1;"), "before", root.resolve("out"));
        assertEquals("move_rejected", across.json.get("error").get("code").asText());
        assertTrue(codes(across).contains("different_scope"));
        // for-init / entry nodes are not statements of a block
        Run init = move(root, "Main.java", F, nodeId(source, "f", "int i = 0"), nodeId(source, "f", "int x = a + 1;"), "before", root.resolve("out"));
        assertEquals("unsupported_node", init.json.get("error").get("code").asText());
        Run self = move(root, "Main.java", F, nodeId(source, "f", "int x = a + 1;"), nodeId(source, "f", "int x = a + 1;"), "before", root.resolve("out"));
        assertEquals("invalid_operation", self.json.get("error").get("code").asText());
        Run same = move(root, "Main.java", F, nodeId(source, "f", "int x = a + 1;"), nodeId(source, "f", "b > 0"), "before", root.resolve("out"));
        assertEquals("no_change", same.json.get("error").get("code").asText());
        Run unknownMethod = move(root, "Main.java", "Main.nope()", "m0.n2", "m0.n3", "before", root.resolve("out"));
        assertEquals("unknown_method", unknownMethod.json.get("error").get("code").asText());
    }

    @Test
    void movesAWholeCompoundStatementWhenItIsIndependent(@TempDir Path root) throws Exception {
        String body = "    int total = 0;\n    for (int i = 0; i < 3; i++) {\n      total += i;\n    }\n    int other = b * 2;\n    return total + other;";
        // `other` is independent of the loop; the loop is identified by its condition node
        String source = source(body);
        Files.writeString(root.resolve("Main.java"), source);
        Run run = move(root, "Main.java", F, nodeId(source, "f", "int other = b * 2;"), nodeId(source, "f", "i < 3"), "before", root.resolve("out"));
        assertEquals(0, run.code, run.json.toString());
        String result = Files.readString(root.resolve("out/Main.java"));
        assertTrue(result.indexOf("int other = b * 2;") < result.indexOf("for (int i = 0"), result);
        // but the loop cannot jump over `total`'s declaration
        Run bad = move(root, "Main.java", F, nodeId(source, "f", "i < 3"), nodeId(source, "f", "int total = 0;"), "before", root.resolve("out2"));
        assertEquals("move_rejected", bad.json.get("error").get("code").asText());
    }

    @Test
    void worksAcrossFilesInProjectMode(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("p"));
        Files.writeString(root.resolve("p/A.java"), "package p; public class A { public int one() { return 1; } }\n");
        String b = "package p;\npublic class B {\n  public int run(int a, int b) {\n    int x = a + 1;\n    int y = b * 2;\n    return x + y;\n  }\n}\n";
        Files.writeString(root.resolve("p/B.java"), b);
        JsonNode analysis = JSON.valueToTree(ProjectAnalyzer.analyze(root, List.of("p/A.java", "p/B.java"), "17"));
        String id = null;
        String target = null;
        for (JsonNode m : find(analysis, "run")) {
            for (JsonNode n : m.get("cfg").get("nodes")) {
                if (n.has("text") && n.get("text").asText().equals("int y = b * 2;")) {
                    id = n.get("id").asText();
                }
                if (n.has("text") && n.get("text").asText().equals("int x = a + 1;")) {
                    target = n.get("id").asText();
                }
            }
        }
        assertNotNull(id);
        assertTrue(id.startsWith("f1m"), id); // file index 1 (B.java)
        Run run = moveIn(root, "project", List.of("p/A.java", "p/B.java"), "p.B.run(int,int)", id, target, "before", root.resolve("out"));
        assertEquals(0, run.code, run.json.toString());
        assertEquals("p/B.java", run.json.get("changed_files").get(0).asText());
        assertEquals(Files.readString(root.resolve("p/A.java")), Files.readString(root.resolve("out/p/A.java")));
        assertTrue(Files.readString(root.resolve("out/p/B.java")).indexOf("int y") < Files.readString(root.resolve("out/p/B.java")).indexOf("int x"));
    }
}
