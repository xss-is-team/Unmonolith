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
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Method groups: a class that is a pile of static methods is split by responsibility. */
class MethodGroupsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REPORT = "import java.util.*;\n\npublic class Report {\n"
            + "    private static final String SEP = \", \";\n\n"
            + "    static class Row {\n        final String name;\n        final int qty;\n        Row(String name, int qty) { this.name = name; this.qty = qty; }\n    }\n\n"
            + "    /** Reads one line `name:qty`. */\n"
            + "    static Row parseRow(String line) {\n        String[] p = line.split(\":\");\n        return new Row(p[0].trim(), toQty(p[1]));\n    }\n\n"
            + "    private static int toQty(String s) {\n        return Integer.parseInt(s.trim());\n    }\n\n"
            + "    static List<Row> parseAll(List<String> lines) {\n        List<Row> rows = new ArrayList<>();\n        for (String l : lines) {\n            rows.add(parseRow(l));\n        }\n        return rows;\n    }\n\n"
            + "    static boolean validateRow(Row r) {\n        return r.qty > 0 && !r.name.isEmpty();\n    }\n\n"
            + "    static List<Row> validateAll(List<Row> rows) {\n        List<Row> ok = new ArrayList<>();\n        for (Row r : rows) {\n            if (validateRow(r)) {\n                ok.add(r);\n            }\n        }\n        return ok;\n    }\n\n"
            + "    static String formatRow(Row r) {\n        return r.name + \"=\" + r.qty;\n    }\n\n"
            + "    static String formatAll(List<Row> rows) {\n        StringBuilder sb = new StringBuilder();\n        for (Row r : rows) {\n            if (sb.length() > 0) {\n                sb.append(SEP);\n            }\n            sb.append(formatRow(r));\n        }\n        return sb.toString();\n    }\n\n"
            + "    public static void main(String[] args) {\n        List<String> lines = List.of(\"a: 1\", \"b: 0\", \"c: 3\");\n"
            + "        List<Row> rows = Report.validateAll(parseAll(lines));\n        System.out.println(formatAll(rows));\n"
            + "        java.util.function.Function<Row, String> f = Report::formatRow;\n        System.out.println(f.apply(rows.get(0)));\n    }\n}\n";

    private static JsonNode run(Path root, List<String> files, String action, List<Map<String, Object>> groups, Path out)
            throws IOException {
        Path request = root.resolve("request.json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_level", "21");
        body.put("files", files);
        body.put("action", action);
        body.put("groups", groups);
        Files.writeString(request, JSON.writeValueAsString(body));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of("decompose", "--root", root.toString(), "--input", request.toString()));
        if (out != null) {
            args.add("--output-dir");
            args.add(out.toString());
        }
        Main.run(args.toArray(String[]::new), new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        return JSON.readTree(stdout.toByteArray());
    }

    private static void write(Path root, String file, String source) throws IOException {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, source, StandardCharsets.UTF_8);
    }

    private static Path compile(Path dir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests need a JDK");
        Path classes = Files.createTempDirectory(dir, "classes");
        try (Stream<Path> files = Files.walk(dir)) {
            List<String> args = new ArrayList<>(List.of("--release", "21", "-d", classes.toString(), "-proc:none", "-encoding", "UTF-8"));
            files.filter(f -> f.toString().endsWith(".java")).forEach(f -> args.add(f.toString()));
            assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), "the project must compile");
        }
        return classes;
    }

    private static String output(Path classes, String mainClass) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, null)) {
            loader.loadClass(mainClass).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static JsonNode group(JsonNode discovered, String name) {
        for (JsonNode g : discovered.get("method_groups")) {
            if (g.get("name").asText().equals(name)) {
                return g;
            }
        }
        throw new AssertionError("no group " + name + " in " + discovered.get("method_groups"));
    }

    @Test
    void suggestsGroupsByResponsibilityAndMovesThemWithTheirPrivateHelpers(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("in");
        write(root, "Report.java", REPORT);
        JsonNode found = run(root, List.of("Report.java"), "discover", List.of(), null);
        assertEquals("completed", found.get("status").asText(), found.toString());
        assertEquals(3, found.get("method_groups").size(), found.get("method_groups").toString());
        JsonNode parsing = group(found, "Parsing");
        assertTrue(parsing.get("extractable").asBoolean(), parsing.toString());
        assertEquals("Parsing.java", parsing.get("new_file").asText());
        assertEquals(2, parsing.get("methods").size());
        assertEquals("toQty", parsing.get("moves_along").get(0).get("name").asText(), "the private helper travels with the group");
        assertTrue(group(found, "Formatting").get("moves_along").toString().contains("SEP"), "the private constant travels too");

        Path out = dir.resolve("out");
        JsonNode applied = run(root, List.of("Report.java"), "apply",
                List.of(Map.of("id", "Report#Parsing"), Map.of("id", "Report#Validation"), Map.of("id", "Report#Formatting")), out);
        assertEquals("completed", applied.get("status").asText(), applied.toString());
        assertEquals(3, applied.get("added_files").size());

        String host = Files.readString(out.resolve("Report.java"));
        assertFalse(host.contains("static Row parseRow") || host.contains("formatAll(List<Row> rows) {"), host);
        assertTrue(host.contains("Formatting.formatAll(Validation.validateAll(Parsing.parseAll(lines)))")
                || host.contains("Formatting.formatAll(rows)"), host);
        assertTrue(host.contains("Formatting::formatRow"), "method references are re-pointed: " + host);
        String parsing2 = Files.readString(out.resolve("Parsing.java"));
        assertTrue(parsing2.contains("final class Parsing {") && parsing2.contains("List<Report.Row> parseAll"), parsing2);
        assertTrue(parsing2.contains("/** Reads one line `name:qty`. */"), "javadoc moves with the method: " + parsing2);
        assertTrue(parsing2.contains("import java.util."), parsing2);
        assertEquals(output(compile(root), "Report"), output(compile(out), "Report"), "the split project prints the same");
    }

    @Test
    void refusesAGroupThatNeedsStateTheRestOfTheClassAlsoUses(@TempDir Path dir) throws Exception {
        String source = REPORT.replace("System.out.println(f.apply(rows.get(0)));", "System.out.println(f.apply(rows.get(0)) + SEP);");
        Path root = dir.resolve("in");
        write(root, "Report.java", source);
        JsonNode found = run(root, List.of("Report.java"), "discover", List.of(), null);
        JsonNode formatting = group(found, "Formatting");
        assertFalse(formatting.get("extractable").asBoolean(), formatting.toString());
        assertTrue(formatting.get("not_extractable_reasons").toString().contains("SEP"), formatting.toString());
        assertTrue(group(found, "Parsing").get("extractable").asBoolean());

        JsonNode refused = run(root, List.of("Report.java"), "apply", List.of(Map.of("id", "Report#Formatting")), dir.resolve("o"));
        assertEquals("failed", refused.get("status").asText());
        assertEquals("not_extractable", refused.at("/error/code").asText());
        assertFalse(Files.exists(dir.resolve("o/Formatting.java")), "nothing is written for a refused request");
    }

    @Test
    void anExplicitMethodListWithAnOwnNameBuildsACustomGroup(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("in");
        write(root, "Report.java", REPORT);
        Path out = dir.resolve("out");
        JsonNode applied = run(root, List.of("Report.java"), "apply", List.of(Map.of("name", "RowChecks",
                "methods", List.of("Report.validateRow(Row)", "Report.validateAll(List<Row>)"))), out);
        assertEquals("completed", applied.get("status").asText(), applied.toString());
        assertTrue(Files.readString(out.resolve("RowChecks.java")).contains("static boolean validateRow"), "the named class holds the methods");
        assertEquals(output(compile(root), "Report"), output(compile(out), "Report"));

        JsonNode missing = run(root, List.of("Report.java"), "apply", List.of(Map.of("name", "X", "methods", List.of("Report.nope()"))), dir.resolve("o2"));
        assertEquals("method_not_found", missing.at("/error/code").asText());
        JsonNode taken = run(root, List.of("Report.java"), "apply", List.of(Map.of("name", "Report", "methods", List.of("Report.validateRow(Row)"))), dir.resolve("o3"));
        assertEquals("not_extractable", taken.at("/error/code").asText());
    }
}
