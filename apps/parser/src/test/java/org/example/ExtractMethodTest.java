package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** extract_method: consecutive statements become a private method, or the request is refused with a reason. */
class ExtractMethodTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Run(int code, JsonNode json) {
    }

    private static final String SOURCE = """
            import java.util.*;

            public class Main {
                static String report(List<Integer> values, int limit) {
                    int sum = 0;
                    for (int v : values) {
                        sum += v;
                    }
                    int avg = values.isEmpty() ? 0 : sum / values.size();
                    String label = avg > limit ? "high" : "low";
                    return label + ":" + sum + ":" + avg;
                }

                static int early(List<Integer> values) {
                    int total = 0;
                    for (int v : values) {
                        if (v < 0) {
                            return -1;
                        }
                        total += v;
                    }
                    return total;
                }

                static String twoOut(int a, int b) {
                    int x = a * 2;
                    int y = b * 3;
                    return x + "," + y;
                }

                public static String run(int n) {
                    List<Integer> values = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        values.add(i * 3 - 4);
                    }
                    return report(values, 5) + "|" + early(values) + "|" + twoOut(n, n + 1);
                }
            }
            """;

    private static String nodeId(String source, String method, String text) {
        JsonNode analysis = JSON.valueToTree(Main.parse(source, "Main.java"));
        for (JsonNode m : analysis.findParents("cfg")) {
            if (m.has("name") && m.get("name").asText().equals(method)) {
                for (JsonNode n : m.get("cfg").get("nodes")) {
                    if (n.has("text") && n.get("text").asText().equals(text)) {
                        return n.get("id").asText();
                    }
                }
            }
        }
        throw new AssertionError("no CFG node '" + text + "' in " + method);
    }

    private static Run extract(Path dir, String method, String methodId, String first, String last, String name, Path out) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Main.java"), SOURCE);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("type", "extract_method");
        op.put("first_node_id", nodeId(SOURCE, method, first));
        op.put("last_node_id", nodeId(SOURCE, method, last));
        op.put("name", name);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "single");
        body.put("source_level", "21");
        body.put("files", List.of("Main.java"));
        body.put("method_id", methodId);
        body.put("operation", op);
        Files.writeString(dir.resolve("edit.json"), JSON.writeValueAsString(body));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"edit", "--root", dir.toString(), "--input", dir.resolve("edit.json").toString(), "--output-dir", out.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        return new Run(code, JSON.readTree(stdout.toByteArray()));
    }

    private static String runProgram(Path dir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        Path classes = Files.createTempDirectory(dir, "classes");
        assertEquals(0, compiler.run(null, null, null, "--release", "21", "-d", classes.toString(), dir.resolve("Main.java").toString()));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, null)) {
            return String.valueOf(loader.loadClass("Main").getMethod("run", int.class).invoke(null, 9));
        }
    }

    @Test
    void extractsAFlowThroughSelectionAndKeepsTheProgramsOutput(@TempDir Path dir) throws Exception {
        Path original = dir.resolve("in");
        Files.createDirectories(original);
        Files.writeString(original.resolve("Main.java"), SOURCE);
        Run run = extract(original, "report", "Main.report(List<Integer>,int)", "int avg = values.isEmpty() ? 0 : sum / values.size();", "int avg = values.isEmpty() ? 0 : sum / values.size();", "average", dir.resolve("out"));
        assertEquals(0, run.code, run.json.toString());
        String result = Files.readString(dir.resolve("out/Main.java"));
        assertTrue(result.contains("int avg = average(values, sum);"), result);
        assertTrue(result.contains("private static int average(List<Integer> values, int sum) {"), result);
        assertTrue(result.contains("return avg;"), result);
        assertEquals("average", run.json.get("operation").get("name").asText());
        assertEquals("avg", run.json.get("operation").get("returns").asText());
        assertEquals(runProgram(original), runProgram(dir.resolve("out")), "the extracted program prints the same");
        assertTrue(run.json.get("node_mapping").size() >= 3);
    }

    @Test
    void extractsStatementsWithNoOutputAndRefusesWhatCannotBeAMethod(@TempDir Path dir) throws Exception {
        Run noOutput = extract(dir.resolve("a"), "twoOut", "Main.twoOut(int,int)", "int x = a * 2;", "int y = b * 3;", "pair", dir.resolve("outA"));
        assertEquals(1, noOutput.code, "x and y both flow out: " + noOutput.json);
        assertTrue(noOutput.json.at("/error/message").asText().contains("More than one value"), noOutput.json.toString());

        Run oneOut = extract(dir.resolve("b"), "twoOut", "Main.twoOut(int,int)", "int x = a * 2;", "int x = a * 2;", "doubled", dir.resolve("outB"));
        assertEquals(0, oneOut.code, oneOut.json.toString());
        assertTrue(Files.readString(dir.resolve("outB/Main.java")).contains("int x = doubled(a);"));

        Run early = extract(dir.resolve("c"), "early", "Main.early(List<Integer>)", "int total = 0;", "return total;", "sumPositive", dir.resolve("outC"));
        assertEquals(1, early.code, early.json.toString());
        Run clash = extract(dir.resolve("d"), "report", "Main.report(List<Integer>,int)", "int sum = 0;", "int sum = 0;", "run", dir.resolve("outD"));
        assertEquals("invalid_operation", clash.json.at("/error/code").asText(), clash.json.toString());
        Run badName = extract(dir.resolve("e"), "report", "Main.report(List<Integer>,int)", "int sum = 0;", "int sum = 0;", "Bad Name", dir.resolve("outE"));
        assertEquals("invalid_operation", badName.json.at("/error/code").asText());
    }
}
