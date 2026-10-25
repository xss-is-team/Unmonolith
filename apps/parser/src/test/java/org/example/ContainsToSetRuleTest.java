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
import java.util.Set;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The contains_to_hashset rule: list.contains(x) in a loop becomes a HashSet lookup, or is refused with a reason. */
class ContainsToSetRuleTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode optimize(Path root, String action, String proposalId, Set<String> acknowledged, Path out) throws IOException {
        Path request = root.resolve("request.json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_level", "21");
        body.put("files", List.of("Text.java"));
        body.put("action", action);
        body.put("proposal_id", proposalId);
        body.put("acknowledged_assumptions", acknowledged);
        Files.writeString(request, JSON.writeValueAsString(body));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of("optimize", "--root", root.toString(), "--input", request.toString()));
        if (out != null) {
            args.add("--output-dir");
            args.add(out.toString());
        }
        Main.run(args.toArray(String[]::new), new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        return JSON.readTree(stdout.toByteArray());
    }

    private static JsonNode proposal(Path root, String method) throws IOException {
        for (JsonNode p : optimize(root, "discover", "", Set.of(), null).get("proposals")) {
            if (p.get("rule").asText().equals(ContainsToSetRule.RULE) && p.get("method_id").asText().contains(method)) {
                return p;
            }
        }
        return null;
    }

    private static String run(Path dir, String call) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        Path classes = Files.createTempDirectory(dir, "classes");
        try (Stream<Path> files = Files.walk(dir)) {
            List<String> args = new ArrayList<>(List.of("--release", "21", "-d", classes.toString(), "-proc:none", "-encoding", "UTF-8"));
            files.filter(f -> f.toString().endsWith(".java")).forEach(f -> args.add(f.toString()));
            assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), "must compile");
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, null)) {
            return String.valueOf(loader.loadClass("Text").getMethod(call, int.class).invoke(null, 12));
        }
    }

    private static final String TEXT = """
            import java.util.ArrayList;
            import java.util.List;

            public class Text {
                public static String common(int n) {
                    List<String> known = new ArrayList<>();
                    for (int i = 0; i < n; i += 3) {
                        known.add("k" + i);
                    }
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < n; i++) {
                        String key = "k" + i;
                        if (known.contains(key)) {
                            out.append(key).append(' ');
                        }
                    }
                    return out.toString();
                }

                public static String viaParameter(int n) {
                    return String.valueOf(count(List.of(1, 5, 7, 9), n));
                }

                static int count(List<Integer> wanted, int n) {
                    int hits = 0;
                    for (int i = 0; i < n; i++) {
                        if (wanted.contains(i)) {
                            hits++;
                        }
                    }
                    return hits;
                }

                public static String mutated(int n) {
                    List<Integer> seen = new ArrayList<>();
                    int dup = 0;
                    for (int i = 0; i < n; i++) {
                        if (seen.contains(i % 4)) {
                            dup++;
                        } else {
                            seen.add(i % 4);
                        }
                    }
                    return String.valueOf(dup);
                }

                static class Point {
                    final int x;
                    Point(int x) { this.x = x; }
                }

                public static String objects(int n) {
                    List<Point> points = new ArrayList<>();
                    points.add(new Point(1));
                    int hits = 0;
                    for (int i = 0; i < n; i++) {
                        if (points.contains(new Point(i))) {
                            hits++;
                        }
                    }
                    return String.valueOf(hits);
                }
            }
            """;

    @Test
    void replacesListScansWithSetLookupsAndKeepsTheOutput(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("in");
        Files.createDirectories(root);
        Files.writeString(root.resolve("Text.java"), TEXT);
        JsonNode common = proposal(root, "Text.common");
        assertNotNull(common);
        assertEquals("applicable", common.get("applicability").asText(), common.toString());
        assertEquals(0, common.get("assumptions").size(), "a local list built with new needs no assumption: " + common.get("assumptions"));

        Path out = dir.resolve("out");
        JsonNode applied = optimize(root, "apply", common.get("id").asText(), Set.of(), out);
        assertEquals("completed", applied.get("status").asText(), applied.toString());
        String rewritten = Files.readString(out.resolve("Text.java"));
        assertTrue(rewritten.contains("Set<String> knownSet = new HashSet<>(known);\n        for (int i = 0; i < n; i++) {"), rewritten);
        assertTrue(rewritten.contains("if (knownSet.contains(key))"), rewritten);
        assertTrue(rewritten.contains("import java.util.Set;") && rewritten.contains("import java.util.HashSet;"), rewritten);
        assertEquals(run(root, "common"), run(out, "common"));

        JsonNode param = proposal(root, "Text.count");
        assertNotNull(param);
        assertEquals("applicable", param.get("applicability").asText(), param.toString());
        assertEquals(2, param.get("assumptions").size(), "a parameter may be aliased and may be null: " + param.get("assumptions"));
        JsonNode refused = optimize(root, "apply", param.get("id").asText(), Set.of(), dir.resolve("o"));
        assertEquals("assumptions_not_acknowledged", refused.at("/error/code").asText());
        Path out2 = dir.resolve("out2");
        JsonNode ok = optimize(root, "apply", param.get("id").asText(), Set.of("list_not_changed_through_alias", "list_not_null_before_loop"), out2);
        assertEquals("completed", ok.get("status").asText(), ok.toString());
        assertEquals(run(root, "viaParameter"), run(out2, "viaParameter"));
    }

    @Test
    void refusesWhatTheSetCannotReproduce(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("in");
        Files.createDirectories(root);
        Files.writeString(root.resolve("Text.java"), TEXT);
        assertFalse(proposal(root, "Text.mutated") != null, "a list that grows inside the loop is not a candidate");
        JsonNode objects = proposal(root, "Text.objects");
        assertNotNull(objects);
        assertEquals("not_applicable", objects.get("applicability").asText());
        assertTrue(objects.get("not_applicable_reasons").toString().contains("hashCode"), objects.toString());
    }
}
