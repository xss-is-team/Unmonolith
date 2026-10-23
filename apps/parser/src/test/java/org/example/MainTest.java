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
        assertEquals("", cli.stderr());
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
