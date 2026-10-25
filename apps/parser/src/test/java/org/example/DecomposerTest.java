package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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

class DecomposerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Run(int code, JsonNode json) {
    }

    private static Run decompose(Path root, List<String> files, String action, List<String> classes, Path out) throws IOException {
        Path request = root.resolve("request.json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_level", "21");
        body.put("files", files);
        body.put("action", action);
        body.put("classes", classes);
        Files.writeString(request, JSON.writeValueAsString(body));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of("decompose", "--root", root.toString(), "--input", request.toString()));
        if (out != null) {
            args.add("--output-dir");
            args.add(out.toString());
        }
        int code = Main.run(args.toArray(String[]::new), new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
        return new Run(code, JSON.readTree(stdout.toByteArray()));
    }

    private static Path write(Path root, String file, String source) throws IOException {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, source, StandardCharsets.UTF_8);
        return target;
    }

    private static JsonNode candidate(JsonNode discovered, String id) {
        for (JsonNode c : discovered.get("candidates")) {
            if (c.get("id").asText().equals(id)) {
                return c;
            }
        }
        throw new AssertionError("no candidate " + id + " in " + discovered);
    }

    private static boolean compiles(Path dir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests need a JDK");
        Path classes = Files.createTempDirectory(dir, "classes");
        try (Stream<Path> files = Files.walk(dir)) {
            List<String> args = new ArrayList<>(List.of("--release", "21", "-d", classes.toString(), "-proc:none", "-encoding", "UTF-8"));
            files.filter(f -> f.toString().endsWith(".java")).forEach(f -> args.add(f.toString()));
            return compiler.run(null, null, null, args.toArray(String[]::new)) == 0;
        }
    }

    private static final String SHOP = "import java.util.*;\n\n/** The monolith. */\npublic class Shop {\n"
            + "    static final int LIMIT = 3;\n\n"
            + "    /** A product. */\n    public static class Item {\n        final String id;\n        Item(String id) { this.id = id; }\n"
            + "        String id() { return id; }\n    }\n\n"
            + "    static class Catalog {\n        private final List<Item> items = new ArrayList<>();\n"
            + "        void add(String id) { items.add(new Shop.Item(id)); }\n        int size() { return items.size(); }\n    }\n\n"
            + "    public static void main(String[] args) {\n        Catalog c = new Catalog();\n        c.add(\"a\");\n"
            + "        Shop.Item first = new Shop.Item(\"b\");\n        System.out.println(c.size() + first.id() + LIMIT);\n    }\n}\n";

    @Test
    void extractsEveryMemberTypeIntoItsOwnFileAndTheResultStillCompiles(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("in");
        write(root, "Shop.java", SHOP);
        JsonNode found = decompose(root, List.of("Shop.java"), "discover", List.of(), null).json();
        assertEquals("completed", found.get("status").asText());
        assertTrue(candidate(found, "Shop.Item").get("extractable").asBoolean());
        assertEquals("Item.java", candidate(found, "Shop.Item").get("new_file").asText());
        assertEquals("Shop.Item", candidate(found, "Shop.Catalog").get("depends_on").get(0).asText());

        Path out = dir.resolve("out");
        Run run = decompose(root, List.of("Shop.java"), "apply", List.of(), out);
        assertEquals(0, run.code(), run.json().toString());
        assertEquals(List.of("Shop.java"), toList(run.json().get("changed_files")));
        assertEquals(List.of("Item.java", "Catalog.java"), toList(run.json().get("added_files")));

        String shop = Files.readString(out.resolve("Shop.java"));
        assertFalse(shop.contains("class Item"), shop);
        assertFalse(shop.contains("class Catalog"), shop);
        assertTrue(shop.contains("Item first = new Item(\"b\");"), "Shop.Item is rewritten to Item: " + shop);
        assertTrue(shop.contains("/** The monolith. */") && shop.contains("LIMIT"), shop);

        String item = Files.readString(out.resolve("Item.java"));
        assertTrue(item.startsWith("/** A product. */\npublic class Item {"), "javadoc kept, `static` dropped, dedented: " + item);
        assertFalse(item.contains("import "), "unused imports are not copied: " + item);
        String catalog = Files.readString(out.resolve("Catalog.java"));
        assertTrue(catalog.startsWith("import java.util.*;\n\nclass Catalog {"), catalog);
        assertTrue(catalog.contains("new Item(id)"), "Shop.Item is rewritten inside the moved type too: " + catalog);
        assertTrue(compiles(out), "the split project must compile");
    }

    @Test
    void aSubsetMustBeClosedUnderItsDependencies(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("in");
        write(root, "Shop.java", SHOP);
        Run lonely = decompose(root, List.of("Shop.java"), "apply", List.of("Shop.Catalog"), dir.resolve("o1"));
        assertEquals(1, lonely.code());
        assertEquals("dependency_not_selected", lonely.json().at("/error/code").asText());
        assertFalse(Files.exists(dir.resolve("o1/Catalog.java")), "nothing is written for a refused request");

        Run itemOnly = decompose(root, List.of("Shop.java"), "apply", List.of("Shop.Item"), dir.resolve("o2"));
        assertEquals(0, itemOnly.code(), itemOnly.json().toString());
        assertTrue(Files.exists(dir.resolve("o2/Item.java")));
        assertTrue(Files.readString(dir.resolve("o2/Shop.java")).contains("class Catalog"), "the other type stays");
        assertTrue(compiles(dir.resolve("o2")));
    }

    @Test
    void refusesWhatWouldNotSurviveTheMove(@TempDir Path dir) throws IOException {
        String source = "public class Host {\n    static int counter = 0;\n    private static int secret = 1;\n\n"
                + "    private static class Hidden { }\n\n"
                + "    static class UsesHost { int next() { return counter++; } }\n\n"
                + "    static class UsesPrivateHost { int peek() { return Host.secret; } }\n\n"
                + "    static class Owner { private int pin = 4; }\n\n"
                + "    static class Reader { int read(Owner o) { return o.pin; } }\n\n"
                + "    static class Fine { int id() { return Host.counter; } }\n\n"
                + "    public static void main(String[] a) { System.out.println(new Hidden() + \"\" + new UsesHost().next()); }\n}\n";
        Path root = dir.resolve("in");
        write(root, "Host.java", source);
        JsonNode found = decompose(root, List.of("Host.java"), "discover", List.of(), null).json();
        assertFalse(candidate(found, "Host.Hidden").get("extractable").asBoolean());
        assertTrue(candidate(found, "Host.Hidden").get("not_extractable_reasons").toString().contains("private member type"));

        JsonNode usesHost = candidate(found, "Host.UsesHost");
        assertFalse(usesHost.get("extractable").asBoolean());
        assertTrue(usesHost.get("not_extractable_reasons").toString().contains("'counter'"), usesHost.toString());

        JsonNode usesPrivate = candidate(found, "Host.UsesPrivateHost");
        assertFalse(usesPrivate.get("extractable").asBoolean());
        assertTrue(usesPrivate.get("not_extractable_reasons").toString().contains("private member 'secret'"), usesPrivate.toString());

        // Reader touches Owner.pin (private): Owner cannot move, and neither can Reader which depends on it
        JsonNode owner = candidate(found, "Host.Owner");
        assertFalse(owner.get("extractable").asBoolean());
        assertTrue(owner.get("not_extractable_reasons").toString().contains("private member 'pin'"), owner.toString());
        assertFalse(candidate(found, "Host.Reader").get("extractable").asBoolean());

        // qualified use of a package-visible member of the host is fine
        assertTrue(candidate(found, "Host.Fine").get("extractable").asBoolean(), candidate(found, "Host.Fine").toString());

        Run bad = decompose(root, List.of("Host.java"), "apply", List.of("Host.Hidden"), dir.resolve("out"));
        assertEquals(1, bad.code());
        assertEquals("not_extractable", bad.json().at("/error/code").asText());
        Run unknown = decompose(root, List.of("Host.java"), "apply", List.of("Host.Nope"), dir.resolve("out2"));
        assertEquals("class_not_found", unknown.json().at("/error/code").asText());

        // the default selection takes exactly the extractable ones
        Path out = dir.resolve("all");
        Run all = decompose(root, List.of("Host.java"), "apply", List.of(), out);
        assertEquals(0, all.code(), all.json().toString());
        assertEquals(List.of("Fine.java"), toList(all.json().get("added_files")));
        assertTrue(compiles(out));
    }

    @Test
    void refusesNameClashesAndReferencesFromOtherFiles(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("in");
        write(root, "Host.java", "public class Host { static class Shared { } static class Alone { } public static void main(String[] a) { } }\n");
        write(root, "Alone.java", "class Alone { }\n");
        write(root, "User.java", "class User { Object o = new Host.Shared(); }\n");
        JsonNode found = decompose(root, List.of("Host.java", "Alone.java", "User.java"), "discover", List.of(), null).json();
        JsonNode alone = candidate(found, "Host.Alone");
        assertFalse(alone.get("extractable").asBoolean());
        assertTrue(alone.get("not_extractable_reasons").toString().contains("already exists"), alone.toString());
        JsonNode shared = candidate(found, "Host.Shared");
        assertFalse(shared.get("extractable").asBoolean());
        assertTrue(shared.get("not_extractable_reasons").toString().contains("User.java"), shared.toString());
    }

    @Test
    void keepsPackageAndCopiesOnlyTheImportsTheMovedTypeUses(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("in");
        write(root, "app/Main.java", "package app;\n\nimport java.util.List;\nimport java.util.Map;\nimport java.io.*;\n\n"
                + "public class Main {\n    static class Lists { List<String> all() { return null; } }\n"
                + "    public static void main(String[] a) throws IOException { Map<String, String> m = null; new Lists().all(); }\n}\n");
        Path out = dir.resolve("out");
        Run run = decompose(root, List.of("app/Main.java"), "apply", List.of(), out);
        assertEquals(0, run.code(), run.json().toString());
        assertEquals(List.of("app/Lists.java"), toList(run.json().get("added_files")));
        String lists = Files.readString(out.resolve("app/Lists.java"));
        assertEquals("package app;\n\nimport java.util.List;\n\nclass Lists { List<String> all() { return null; } }\n", lists);
        assertTrue(compiles(out));
    }

    @Test
    void splitsTheDemoMonolithIntoSixteenFilesThatCompile(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("in");
        Files.createDirectories(root);
        Files.copy(Path.of("src/test/resources/acceptance/Main.java"), root.resolve("Main.java"));
        Path out = dir.resolve("out");
        Run run = decompose(root, List.of("Main.java"), "apply", List.of(), out);
        assertEquals(0, run.code(), run.json().toString());
        assertEquals(16, run.json().get("added_files").size());
        JsonNode lines = run.json().at("/host_lines/Main.java");
        assertEquals(801, lines.get("before").asInt());
        assertTrue(lines.get("after").asInt() < 260, lines.toString());
        assertTrue(Files.exists(out.resolve("DataOptimizer.java")));
        assertTrue(compiles(out), "the split monolith must compile");
    }

    private static List<String> toList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
