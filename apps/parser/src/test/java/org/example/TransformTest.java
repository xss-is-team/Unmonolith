package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransformTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String METHOD = "Example.process(int)";

    private record Cli(int exit, String stdout, String stderr) {
        JsonNode json() throws IOException {
            var parser = JSON.getFactory().createParser(stdout);
            JsonNode node = JSON.readTree(parser);
            assertEquals(null, parser.nextToken(), "stdout must hold exactly one JSON document");
            return node;
        }
    }

    private static Cli run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Cli(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Id of the nth (0-based, in source order) CFG branch node of the given statement kind. */
    private static String nodeId(Path file, String methodId, String statementKind, int nth) throws IOException {
        JsonNode root = run(file.toString()).json();
        for (JsonNode type : root.get("types")) {
            for (JsonNode m : type.get("members")) {
                if (!methodId.equals(m.path("id").asText())) {
                    continue;
                }
                var matches = new java.util.ArrayList<JsonNode>();
                for (JsonNode n : m.at("/cfg/nodes")) {
                    if ("branch".equals(n.path("kind").asText())
                            && statementKind.equals(n.path("statement_kind").asText())) {
                        matches.add(n);
                    }
                }
                matches.sort(java.util.Comparator.comparingInt(n -> n.at("/source_range/start").asInt()));
                return matches.get(nth).get("id").asText();
            }
        }
        throw new AssertionError("no method " + methodId);
    }

    private static String opJson(String methodId, String nodeId, String expression) throws IOException {
        var op = JSON.createObjectNode();
        op.put("method_id", methodId);
        op.put("node_id", nodeId);
        op.putObject("operation").put("type", "replace_condition").put("expression", expression);
        return JSON.writeValueAsString(op);
    }

    private static Cli transform(Path dir, String source, String opJson) throws IOException {
        Files.writeString(dir.resolve("Example.java"), source);
        Files.writeString(dir.resolve("op.json"), opJson);
        return run("transform", "--input", dir.resolve("Example.java").toString(),
                "--operation", dir.resolve("op.json").toString(),
                "--output", dir.resolve("Out.java").toString());
    }

    private static Cli transform(Path dir, String source, String kind, int nth, String expr) throws IOException {
        Files.writeString(dir.resolve("Example.java"), source);
        String id = nodeId(dir.resolve("Example.java"), METHOD, kind, nth);
        return transform(dir, source, opJson(METHOD, id, expr));
    }

    private static String wrap(String body) {
        return "public class Example {\n    int process(int count) {\n" + body + "\n        return count;\n    }\n}\n";
    }

    private static void assertCompleted(Cli cli) throws IOException {
        assertEquals(0, cli.exit(), cli.stdout() + cli.stderr());
        JsonNode r = cli.json();
        assertEquals("completed", r.get("status").asText());
        assertEquals("replace_condition", r.get("operation").asText());
        assertEquals(0, r.get("diagnostics").size());
    }

    private static void assertRejected(Cli cli, Path dir, String code) throws IOException {
        assertTrue(cli.exit() != 0);
        JsonNode r = cli.json();
        assertEquals("failed", r.get("status").asText());
        assertEquals(code, r.at("/error/code").asText());
        assertFalse(Files.exists(dir.resolve("Out.java")), "failed transform must not create output");
        assertEquals(0, dir.toFile().list((d, n) -> n.endsWith(".tmp")).length, "no temp files left behind");
    }

    private static String out(Path dir) throws IOException {
        return Files.readString(dir.resolve("Out.java"));
    }

    @Test
    void replacesIfCondition(@TempDir Path dir) throws IOException {
        String src = wrap("        if (count > 10) {\n            count--;\n        }");
        assertCompleted(transform(dir, src, "if", 0, "count >= 0"));
        assertEquals(src.replace("count > 10", "count >= 0"), out(dir));
    }

    @Test
    void replacesWhileCondition(@TempDir Path dir) throws IOException {
        String src = wrap("        while (count > 10) {\n            count--;\n        }");
        assertCompleted(transform(dir, src, "while", 0, "count != 3 && count < 99"));
        assertEquals(src.replace("count > 10", "count != 3 && count < 99"), out(dir));
    }

    @Test
    void replacesDoWhileCondition(@TempDir Path dir) throws IOException {
        String src = wrap("        do {\n            count--;\n        } while (count > 10);");
        assertCompleted(transform(dir, src, "do_while", 0, "count > 0"));
        assertEquals(src.replace("count > 10", "count > 0"), out(dir));
    }

    @Test
    void replacesForCondition(@TempDir Path dir) throws IOException {
        String src = wrap("        for (int i = 0; i < count; i++) {\n            count--;\n        }");
        assertCompleted(transform(dir, src, "for", 0, "i <= count"));
        assertEquals(src.replace("i < count", "i <= count"), out(dir));
    }

    @Test
    void rejectsUnknownMethod(@TempDir Path dir) throws IOException {
        String src = wrap("        if (count > 1) { count--; }");
        Files.writeString(dir.resolve("Example.java"), src);
        String id = nodeId(dir.resolve("Example.java"), METHOD, "if", 0);
        assertRejected(transform(dir, src, opJson("Example.nope(int)", id, "true")), dir, "unknown_method");
    }

    @Test
    void rejectsUnknownNode(@TempDir Path dir) throws IOException {
        String src = wrap("        if (count > 1) { count--; }");
        assertRejected(transform(dir, src, opJson(METHOD, "m0.n99", "true")), dir, "unknown_node");
    }

    @Test
    void rejectsNonConditionNode(@TempDir Path dir) throws IOException {
        String src = wrap("        if (count > 1) { count--; }");
        // m0.n0 is the CFG entry node
        assertRejected(transform(dir, src, opJson(METHOD, "m0.n0", "true")), dir, "unsupported_node");
    }

    @Test
    void rejectsForeachAndConditionlessFor(@TempDir Path dir) throws IOException {
        String src = wrap("        for (int x : new int[] {1}) { count += x; }\n        for (;;) { break; }");
        Files.writeString(dir.resolve("Example.java"), src);
        JsonNode nodes = run(dir.resolve("Example.java").toString()).json().at("/types/0/members/0/cfg/nodes");
        int checked = 0;
        for (JsonNode n : nodes) {
            if ("foreach".equals(n.path("statement_kind").asText()) || "join".equals(n.path("kind").asText())) {
                assertRejected(transform(dir, src, opJson(METHOD, n.get("id").asText(), "true")), dir, "unsupported_node");
                checked++;
            }
        }
        assertEquals(2, checked);
    }

    @Test
    void rejectsMalformedExpression(@TempDir Path dir) throws IOException {
        String src = wrap("        if (count > 1) { count--; }");
        for (String bad : new String[] {"count >", "count > 1) { count = 0; } if (true", "a b", "count > 1; count = 0", "count > 1 // x"}) {
            assertRejected(transform(dir, src, "if", 0, bad), dir, "invalid_expression");
        }
    }

    @Test
    void rejectsMalformedOperationFile(@TempDir Path dir) throws IOException {
        String src = wrap("        if (count > 1) { count--; }");
        assertRejected(transform(dir, src, "{\"method_id\":\"x\"}"), dir, "invalid_operation");
        assertRejected(transform(dir, src, "not json"), dir, "invalid_operation");
        assertRejected(transform(dir, src, opJson(METHOD, "m0.n1", "true").replace("replace_condition", "delete")),
                dir, "invalid_operation");
    }

    @Test
    void repeatedIdenticalConditionsAreEditedIndividually(@TempDir Path dir) throws IOException {
        String src = wrap("        if (count > 1) { count--; }\n        if (count > 1) { count++; }\n"
                + "        if (count > 1) { count += 2; }");
        assertCompleted(transform(dir, src, "if", 1, "count < 5"));
        String expected = src.replaceFirst("(?s)(if \\(count > 1\\).*?)if \\(count > 1\\)", "$1if (count < 5)");
        assertEquals(expected, out(dir));
        assertEquals(2, out(dir).split("count > 1", -1).length - 1);
    }

    @Test
    void preservesUnrelatedSourceAndComments(@TempDir Path dir) throws IOException {
        String src = "// header\r\npackage p;\r\n\r\nimport java.util.*;\r\n\r\n/** doc */\r\npublic class Example {\r\n"
                + "    private int   spaced  =  1;  // keep\r\n"
                + "    int process(int count) {\r\n        /* before */ if (count>1)   { // trailing\r\n"
                + "            count--;\r\n        }   /* after */\r\n        return count;\r\n    }\r\n"
                + "    void other() { while(true) { break; } }\r\n}\r\n";
        assertCompleted(transform(dir, src, "if", 0, "count < 2"));
        assertEquals(src.replace("count>1", "count < 2"), out(dir));
    }

    @Test
    void preservesUnicodeAndUtf16Offsets(@TempDir Path dir) throws IOException {
        String src = "public class Example {\n    String имя = \"Привет 😀\";\n"
                + "    int process(int count) {\n        // комментарий 😀\n"
                + "        String s = \"😀😀\";\n        if (count > 1) { count--; }\n        return count;\n    }\n}\n";
        assertCompleted(transform(dir, src, "if", 0, "имя.length() > count && \"😀\".length() == 2"));
        assertEquals(src.replace("count > 1", "имя.length() > count && \"😀\".length() == 2"), out(dir));
        assertEquals(src.length() - "count > 1".length() + "имя.length() > count && \"😀\".length() == 2".length(),
                out(dir).length());
    }

    @Test
    void rejectsSemanticallyInvalidReplacement(@TempDir Path dir) throws IOException {
        String src = wrap("        if (count > 1) { count--; }");
        assertRejected(transform(dir, src, "if", 0, "count + 1"), dir, "validation_failed");
        assertRejected(transform(dir, src, "if", 0, "undefinedVar > 1"), dir, "validation_failed");
        assertRejected(transform(dir, src, "if", 0, "count.foo()"), dir, "validation_failed");
    }

    @Test
    void failureLeavesExistingOutputUntouched(@TempDir Path dir) throws IOException {
        String src = wrap("        if (count > 1) { count--; }");
        Files.writeString(dir.resolve("Out.java"), "previous");
        Cli cli = transform(dir, src, "if", 0, "count +");
        assertEquals(1, cli.exit());
        assertEquals("previous", out(dir));
    }

    @Test
    void refusesToOverwriteInput(@TempDir Path dir) throws IOException {
        String src = wrap("        if (count > 1) { count--; }");
        Files.writeString(dir.resolve("Example.java"), src);
        Files.writeString(dir.resolve("op.json"), opJson(METHOD, nodeId(dir.resolve("Example.java"), METHOD, "if", 0), "true"));
        Cli cli = run("transform", "--input", dir.resolve("Example.java").toString(),
                "--operation", dir.resolve("op.json").toString(), "--output", dir.resolve("Example.java").toString());
        assertEquals(1, cli.exit());
        assertEquals(src, Files.readString(dir.resolve("Example.java")));
    }

    @Test
    void transformedSourceReparsesWithValidCfg(@TempDir Path dir) throws IOException {
        String src = wrap("        while (count > 10) {\n            if (count == 12) { count--; }\n            count--;\n        }");
        assertCompleted(transform(dir, src, "while", 0, "count > 3"));
        Path again = Files.createDirectory(dir.resolve("again")).resolve("Example.java");
        Files.copy(dir.resolve("Out.java"), again); // the API re-stores it under the original basename
        Cli cli = run(again.toString());
        JsonNode root = cli.json();
        assertEquals(0, cli.exit());
        assertEquals("completed", root.get("status").asText());
        JsonNode cfg = root.at("/types/0/members/0/cfg");
        boolean found = false;
        for (JsonNode n : cfg.get("nodes")) {
            found |= "while".equals(n.path("statement_kind").asText()) && "count > 3".equals(n.path("text").asText());
        }
        assertTrue(found);
        assertTrue(cfg.get("edges").size() > 0);
        assertEquals("count > 3", root.at("/types/0/members/0/body/0/condition").asText());
    }

    @Test
    void usageErrors(@TempDir Path dir) throws IOException {
        Cli cli = run("transform", "--input", "a.java");
        assertEquals(2, cli.exit());
        assertEquals("invalid_arguments", cli.json().at("/error/code").asText());
        assertEquals(2, run("transform", "--input", "a", "--input", "b", "--output", "c").exit());
    }

    @Test
    void successResultSchema(@TempDir Path dir) throws IOException {
        String src = wrap("        if (count > 1) { count--; }");
        Cli cli = transform(dir, src, "if", 0, "count > 2");
        JsonNode r = cli.json();
        assertEquals("[status, operation, method_id, node_id, output_file, diagnostics]",
                java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(r.fieldNames(), 0), false)
                        .toList().toString());
        assertEquals(dir.resolve("Out.java").toString(), r.get("output_file").asText());
        assertEquals("", cli.stderr());
    }
}
