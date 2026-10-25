package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The traced copy compiles, behaves like the original on stdout, and its counters point at CFG nodes. */
class TracerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Result(String stdout, String stderr) {
    }

    private static Result compileAndRun(Path dir, Path source, String stdin) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests need a JDK");
        Path classes = Files.createDirectories(dir.resolve("classes"));
        assertEquals(0, compiler.run(null, null, null, "--release", "21", "-d", classes.toString(), source.toString()), "must compile: " + source);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", classes.toString(), "Main").start();
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return new Result(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static Path traced(Path dir, Path original) throws IOException {
        Path root = Files.createDirectories(dir.resolve("root"));
        Files.copy(original, root.resolve("Main.java"));
        Path request = dir.resolve("request.json");
        Files.writeString(request, JSON.writeValueAsString(Map.of("source_level", "21", "files", List.of("Main.java"))));
        var out = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"trace", "--root", root.toString(), "--input", request.toString(), "--output-dir", dir.resolve("out").toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, code, out.toString(StandardCharsets.UTF_8));
        assertTrue(JSON.readTree(out.toByteArray()).get("counters").asInt() > 100);
        return dir.resolve("out/Main.java");
    }

    @Test
    void theTracedDemoMonolithPrintsTheSameAndCountsCfgNodes(@TempDir Path dir) throws Exception {
        Path original = Path.of("../api/tests/fixtures/demo/Main.java");
        String stdin = "help\ninventory\nmerge_inventory\ninventory\nexit\n";
        Result plain = compileAndRun(Files.createDirectories(dir.resolve("plain")), original, stdin);
        Result traced = compileAndRun(Files.createDirectories(dir.resolve("traced")), traced(dir, original), stdin);
        assertEquals(plain.stdout(), traced.stdout(), "tracing must not change the program's output");

        Map<String, Integer> hits = new HashMap<>();
        for (String line : traced.stderr().split("\n")) {
            if (line.startsWith(Tracer.MARKER)) {
                String[] parts = line.substring(Tracer.MARKER.length()).split(" ");
                hits.put(parts[0], Integer.parseInt(parts[1].trim()));
            }
        }
        assertTrue(hits.size() > 30, traced.stderr());

        // every counted offset is the start of a CFG node of the parsed original
        var parsed = new ByteArrayOutputStream();
        Main.run(new String[] {original.toString()}, new PrintStream(parsed, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        Set<Integer> starts = new HashSet<>();
        collect(JSON.readTree(parsed.toByteArray()), starts);
        for (String key : hits.keySet()) {
            String[] parts = key.split(":");
            assertTrue(starts.contains(Integer.parseInt(parts[1])), "no CFG node starts at " + key);
        }
        assertTrue(hits.keySet().stream().anyMatch(k -> k.endsWith(":i")), "loop iterations are counted");
    }

    private static void collect(JsonNode node, Set<Integer> starts) {
        if (node.isObject()) {
            if (node.has("cfg") && node.get("cfg").has("nodes")) {
                for (JsonNode n : node.get("cfg").get("nodes")) {
                    starts.add(n.at("/source_range/start").asInt());
                }
            }
            node.elements().forEachRemaining(child -> collect(child, starts));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collect(child, starts));
        }
    }

    @Test
    void theReferenceMonolithStillCompilesWhenTraced(@TempDir Path dir) throws Exception {
        Path traced = traced(dir, Path.of("src/test/resources/acceptance/Organizer.java"));
        Path renamed = Files.createDirectories(dir.resolve("organizer")).resolve("Organizer.java");
        Files.copy(traced, renamed);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "--release", "21", "-d", dir.resolve("organizer-classes").toString(), renamed.toString()));
    }
}
