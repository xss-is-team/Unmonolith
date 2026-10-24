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

class MainTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Cli(int exit, String stdout, String stderr) {
        JsonNode json() throws IOException {
            // readTree stops after the first document, so reject trailing content explicitly
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

    private static Path write(Path dir, String source) throws IOException {
        return Files.writeString(dir.resolve("Example.java"), source);
    }

    @Test
    void validSourceKeepsStatementNesting(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                public class Example {
                    int f(int a, int b) {
                        if (a == b) {
                            return 1;
                        } else {
                            for (int i = 0; i < a; i++) {
                                while (b > 0) { b--; }
                            }
                        }
                        return 0;
                    }
                }
                """);

        Cli cli = run(file.toString());
        JsonNode root = cli.json();

        assertEquals(0, cli.exit());
        assertTrue(cli.stderr().contains("progress: source_loaded"));
        assertTrue(cli.stderr().contains("progress: ast_ready"));
        assertTrue(cli.stderr().contains("progress: model_ready"));
        assertEquals(1, root.get("protocol_version").asInt());
        assertEquals("completed", root.get("status").asText());
        assertEquals(0, root.get("diagnostics").size());
        assertEquals("Example.f(int,int)", root.get("methods").get(0).get("id").asText());

        JsonNode body = root.at("/types/0/members/0/body");
        JsonNode ifNode = body.get(0);
        assertEquals("if", ifNode.get("kind").asText());
        assertEquals("a == b", ifNode.get("condition").asText());
        assertEquals("return", ifNode.at("/then/0/kind").asText());
        JsonNode forNode = ifNode.at("/else/0");
        assertEquals("for", forNode.get("kind").asText());
        assertEquals("i < a", forNode.get("condition").asText());
        assertEquals("while", forNode.at("/body/0/kind").asText());
        assertEquals("return", body.get(1).get("kind").asText());
        assertEquals(3, ifNode.at("/source_range/line").asInt());
    }

    @Test
    void switchAndTryAreGrouped(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                class Example {
                    void f(int a) {
                        try {
                            switch (a) {
                                case 1: a++;
                                case 2: break;
                                default: a--;
                            }
                        } catch (IllegalStateException | IllegalArgumentException e) {
                            throw e;
                        } finally {
                            a = 0;
                        }
                    }
                }
                """);

        JsonNode tryNode = run(file.toString()).json().at("/types/0/members/0/body/0");

        assertEquals("try", tryNode.get("kind").asText());
        JsonNode cases = tryNode.at("/body/0/cases");
        assertEquals(3, cases.size());
        assertEquals("a++", cases.get(0).at("/body/0/expression").asText());
        assertEquals("break", cases.get(1).at("/body/0/kind").asText());
        assertTrue(cases.get(2).get("default").asBoolean());
        assertEquals("IllegalStateException | IllegalArgumentException",
                tryNode.at("/catches/0/exception_type").asText());
        assertEquals("expression", tryNode.at("/finally/0/kind").asText());
    }

    @Test
    void cfgKeepsBranchesNestedLoopsAndSwitchPaths(@TempDir Path dir) throws IOException {
        String source = """
                class Example {
                    int f(int[] values) {
                        int total = 0;
                        for (int value : values) {
                            if (value < 0) continue;
                            while (value > 0) {
                                if (value == 2) break;
                                total += value;
                                value--;
                            }
                        }
                        switch (total) {
                            case 0: total++;
                            case 1: break;
                            default: total--;
                        }
                        return total;
                    }
                }
                """;
        Path file = write(dir, source);
        JsonNode root = run(file.toString()).json();
        JsonNode cfg = root.get("cfgs").get(0);

        assertEquals("Example.f(int[])", cfg.get("method_id").asText());
        assertTrue(hasKind(cfg, "while"));
        assertTrue(hasKind(cfg, "foreach"));
        assertTrue(hasKind(cfg, "if"));
        assertTrue(hasEdge(cfg, "true"));
        assertTrue(hasEdge(cfg, "false"));
        assertTrue(hasEdge(cfg, "back"));
        assertTrue(hasEdge(cfg, "break"));
        assertTrue(hasEdge(cfg, "continue"));
        assertTrue(hasEdgePrefix(cfg, "case:"));
        assertTrue(hasEdge(cfg, "default"));
        assertFalse(hasEdge(cfg, "no-match"));

        var ids = new java.util.HashSet<String>();
        cfg.get("nodes").forEach(node -> {
            ids.add(node.get("id").asText());
            int start = node.at("/source_range/start").asInt();
            int length = node.at("/source_range/length").asInt();
            assertTrue(start >= 0 && start + length <= source.length());
        });
        cfg.get("edges").forEach(edge -> {
            assertTrue(ids.contains(edge.get("source").asText()));
            assertTrue(ids.contains(edge.get("target").asText()));
        });
    }

    @Test
    void cfgRoutesDeclaredExceptionsAndAbruptPathsThroughFinally(@TempDir Path dir) throws IOException {
        String source = """
                import java.io.IOException;
                class Example {
                    void fail() throws IOException {}
                    void clean() {}
                    void run() {
                        try {
                            fail();
                        } catch (IOException error) {
                            return;
                        } finally {
                            clean();
                        }
                    }
                }
                """;
        JsonNode cfg = run(write(dir, source).toString()).json().get("cfgs").get(2);
        JsonNode catchNode = null;
        JsonNode catchCall = null;
        var nodes = new java.util.HashMap<String, JsonNode>();
        for (JsonNode node : cfg.get("nodes")) {
            nodes.put(node.get("id").asText(), node);
            if (node.get("kind").asText().equals("catch")) catchNode = node;
        }
        for (JsonNode edge : cfg.get("edges")) {
            if (edge.get("kind").asText().equals("exception")
                    && nodes.get(edge.get("source").asText()).get("label").asText().contains("fail()")) {
                catchCall = edge;
            }
        }
        assertTrue(catchNode != null);
        assertTrue(catchCall != null);
        assertEquals(catchNode.get("id").asText(), catchCall.get("target").asText());

        String returnId = null;
        var cleanupIds = new java.util.HashSet<String>();
        for (JsonNode node : cfg.get("nodes")) {
            if (node.get("kind").asText().equals("return")) returnId = node.get("id").asText();
            if (node.get("label").asText().equals("clean();")) cleanupIds.add(node.get("id").asText());
        }
        assertTrue(returnId != null && !cleanupIds.isEmpty());
        boolean returnRunsFinally = false;
        for (JsonNode edge : cfg.get("edges")) {
            if (edge.get("source").asText().equals(returnId)
                    && cleanupIds.contains(edge.get("target").asText())
                    && edge.get("kind").asText().equals("finally")) returnRunsFinally = true;
        }
        assertTrue(returnRunsFinally);
    }

    @Test
    void cfgCoversDoWhileAndSwitchWithoutDefault(@TempDir Path dir) throws IOException {
        String source = """
                class Example {
                    int loops(int value) {
                        for (int i = 0; i < value; i++) { if (i > 2) continue; }
                        do { value--; } while (value > 0);
                        switch (value) { case 1: value++; }
                        return value;
                    }
                }
                """;
        JsonNode cfg = run(write(dir, source).toString()).json().get("cfgs").get(0);

        assertTrue(hasKind(cfg, "for"));
        assertTrue(hasKind(cfg, "do_while"));
        assertTrue(hasEdge(cfg, "back"));
        assertTrue(hasEdge(cfg, "back_true"));
        assertTrue(hasEdge(cfg, "no-match"));
    }

    private static boolean hasKind(JsonNode cfg, String kind) {
        for (JsonNode node : cfg.get("nodes")) if (node.get("kind").asText().equals(kind)) return true;
        return false;
    }

    private static boolean hasEdge(JsonNode cfg, String kind) {
        for (JsonNode edge : cfg.get("edges")) if (edge.get("kind").asText().equals(kind)) return true;
        return false;
    }

    private static boolean hasEdgePrefix(JsonNode cfg, String prefix) {
        for (JsonNode edge : cfg.get("edges")) if (edge.get("kind").asText().startsWith(prefix)) return true;
        return false;
    }

    @Test
    void malformedSourceYieldsDiagnosticsAndPartialAst(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                class Example {
                    void ok() { int x = 1; }
                    void broken( {
                        if (
                    }
                }
                """);

        Cli cli = run(file.toString());
        JsonNode root = cli.json();

        assertEquals(0, cli.exit());
        assertEquals("partial", root.get("status").asText());
        assertTrue(root.get("diagnostics").size() > 0);
        JsonNode diagnostic = root.at("/diagnostics/0");
        assertEquals("error", diagnostic.get("severity").asText());
        assertTrue(diagnostic.at("/source_range/line").asInt() > 0);
        assertEquals("Example.ok()", root.at("/methods/0/id").asText());
    }

    @Test
    void garbageInputDoesNotCrash(@TempDir Path dir) throws IOException {
        Cli cli = run(write(dir, "}}} (( \u0000 class ;;; @@").toString());

        assertEquals(0, cli.exit());
        assertEquals("partial", cli.json().get("status").asText());
    }

    @Test
    void cyrillicAndEscapesSurviveRoundTrip(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                class Example {
                    void f() { System.out.println("Привет\\n\\"мир\\""); }
                }
                """);

        JsonNode statement = run(file.toString()).json().at("/types/0/members/0/body/0");

        assertEquals("System.out.println(\"Привет\\n\\\"мир\\\"\")", statement.get("expression").asText());
    }

    @Test
    void usageErrorGoesToStderrOnly() {
        Cli cli = run();

        assertEquals(2, cli.exit());
        assertEquals("", cli.stdout());
        assertTrue(cli.stderr().startsWith("usage:"));
    }

    @Test
    void missingFileReportsFailedJson(@TempDir Path dir) throws IOException {
        Cli cli = run(dir.resolve("nope.java").toString());

        assertEquals(1, cli.exit());
        assertFalse(cli.stderr().isEmpty());
        assertEquals("failed", cli.json().get("status").asText());
        assertEquals("input_unreadable", cli.json().at("/diagnostics/0/code").asText());
    }
}
