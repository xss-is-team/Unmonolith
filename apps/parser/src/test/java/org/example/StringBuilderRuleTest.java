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

/** The string_concat_to_builder rule: s += x in a loop becomes StringBuilder appends, or is refused with a reason. */
class StringBuilderRuleTest {
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
            if (p.get("rule").asText().equals(StringBuilderRule.RULE) && p.get("method_id").asText().contains(method)) {
                return p;
            }
        }
        throw new AssertionError("no string_concat_to_builder proposal for " + method);
    }

    private static List<JsonNode> builderProposals(Path root) throws IOException {
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode p : optimize(root, "discover", "", Set.of(), null).get("proposals")) {
            if (p.get("rule").asText().equals(StringBuilderRule.RULE)) {
                list.add(p);
            }
        }
        return list;
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
            Object value = loader.loadClass("Text").getMethod(call, int.class).invoke(null, 7);
            return String.valueOf(value);
        }
    }

    private static final String TEXT = """
            import java.util.*;

            public class Text {
                public static String commas(int n) {
                    String out = "";
                    for (int i = 0; i < n; i++) {
                        out += i + ",";
                    }
                    return out;
                }

                public static String grid(int n) {
                    String s = "[";
                    for (int i = 0; i < n; i++) {
                        for (int j = 0; j < i; j++) {
                            if ((i + j) % 2 == 0) {
                                s = s + i + ':' + j + ' ';
                            } else {
                                s += (j > 1 ? "x" : "y");
                            }
                        }
                    }
                    return s + "]";
                }

                public static String peek(int n) {
                    String s = "";
                    for (int i = 0; i < n; i++) {
                        s += i;
                        if (s.length() > 3) {
                            break;
                        }
                    }
                    return s;
                }

                public static String guarded(int n) {
                    String s = "a";
                    try {
                        for (int i = 0; i < n; i++) {
                            s += 10 / (3 - i);
                        }
                    } catch (ArithmeticException e) {
                        return "failed after " + s;
                    }
                    return s;
                }

                public static String chars(int n) {
                    String s = "";
                    char[] c = {'a', 'b'};
                    for (int i = 0; i < n; i++) {
                        s += c;
                    }
                    return s.isEmpty() ? "" : "chars";
                }

                public static String maybeNull(int n) {
                    String s = n > 100 ? null : "start";
                    for (int i = 0; i < n; i++) {
                        s += "-" + i;
                    }
                    return s;
                }

                public static String unbraced(int n) {
                    String s = "";
                    if (n > 0)
                        for (int i = 0; i < n; i++) s += i;
                    return s;
                }
            }
            """;

    @Test
    void rewritesAppendOnlyLoopsAndKeepsTheOutput(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("in");
        Files.createDirectories(root);
        Files.writeString(root.resolve("Text.java"), TEXT);
        JsonNode commas = proposal(root, "Text.commas");
        assertEquals("applicable", commas.get("applicability").asText(), commas.toString());
        assertEquals(0, commas.get("assumptions").size(), "a string initialised with a literal needs no assumption");
        assertTrue(commas.get("id").asText().startsWith("opt_"));

        Path out = dir.resolve("out");
        JsonNode applied = optimize(root, "apply", commas.get("id").asText(), Set.of(), out);
        assertEquals("completed", applied.get("status").asText(), applied.toString());
        String rewritten = Files.readString(out.resolve("Text.java"));
        assertTrue(rewritten.contains("StringBuilder outBuilder = new StringBuilder(out);\n        for (int i = 0; i < n; i++) {\n            outBuilder.append(i + \",\");\n        }\n        out = outBuilder.toString();"), rewritten);
        assertEquals(run(root, "commas"), run(out, "commas"));

        JsonNode grid = proposal(root, "Text.grid");
        assertEquals("applicable", grid.get("applicability").asText(), grid.toString());
        Path out2 = dir.resolve("out2");
        assertEquals("completed", optimize(root, "apply", grid.get("id").asText(), Set.of(), out2).get("status").asText());
        String nested = Files.readString(out2.resolve("Text.java"));
        assertTrue(nested.contains("StringBuilder sBuilder = new StringBuilder(s);\n        for (int i = 0; i < n; i++) {"), "the builder sits before the OUTER loop: " + nested);
        assertTrue(nested.contains("sBuilder.append(i).append(':').append(j).append(' ');"), nested);
        assertTrue(nested.contains("sBuilder.append((j > 1 ? \"x\" : \"y\"));"), nested);
        assertEquals(run(root, "grid"), run(out2, "grid"));
    }

    @Test
    void refusesWhatCouldBeObservedOrWouldChangeTheText(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("in");
        Files.createDirectories(root);
        Files.writeString(root.resolve("Text.java"), TEXT);
        JsonNode all = JSON.valueToTree(builderProposals(root));
        List<String> methods = new ArrayList<>();
        all.forEach(p -> methods.add(p.get("method_id").asText()));
        assertFalse(methods.stream().anyMatch(m -> m.contains("Text.peek")), "a string read inside the loop is not a candidate: " + methods);

        JsonNode guarded = proposal(root, "Text.guarded");
        assertEquals("not_applicable", guarded.get("applicability").asText());
        assertTrue(guarded.get("not_applicable_reasons").toString().contains("try with catch"), guarded.toString());
        JsonNode chars = proposal(root, "Text.chars");
        assertEquals("not_applicable", chars.get("applicability").asText());
        assertTrue(chars.get("not_applicable_reasons").toString().contains("char[]"), chars.toString());
        JsonNode unbraced = proposal(root, "Text.unbraced");
        assertEquals("not_applicable", unbraced.get("applicability").asText());
        assertTrue(unbraced.get("not_applicable_reasons").toString().contains("unbraced body"), unbraced.toString());

        JsonNode maybeNull = proposal(root, "Text.maybeNull");
        assertEquals("applicable", maybeNull.get("applicability").asText());
        assertEquals("string_never_null", maybeNull.get("assumptions").get(0).get("id").asText());
        JsonNode refused = optimize(root, "apply", maybeNull.get("id").asText(), Set.of(), dir.resolve("o"));
        assertEquals("assumptions_not_acknowledged", refused.at("/error/code").asText());
        JsonNode ok = optimize(root, "apply", maybeNull.get("id").asText(), Set.of("string_never_null"), dir.resolve("o2"));
        assertEquals("completed", ok.get("status").asText(), ok.toString());
        assertEquals(run(root, "maybeNull"), run(dir.resolve("o2"), "maybeNull"));
        assertEquals("proposal_not_found", optimize(root, "apply", "opt_0000000000000000", Set.of(), dir.resolve("o3")).at("/error/code").asText());
    }
}
