package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end acceptance of parser, semantics, CFG, analysis and replace_condition against a
 * realistic ~1000-line single-file "organizer" program (src/test/resources/acceptance).
 */
class AcceptanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static String source;
    private static JsonNode root;

    @BeforeAll
    static void load() throws IOException {
        source = Files.readString(Path.of("src/test/resources/acceptance/Organizer.java"), StandardCharsets.UTF_8);
        root = analyze(source);
    }

    // ---------- helpers ----------

    private static JsonNode analyze(String text) throws IOException {
        return JSON.valueToTree(Main.parse(text, "Organizer.java"));
    }

    private static List<JsonNode> methods(JsonNode doc) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode t : doc.get("types")) {
            collect(t.get("members"), out);
        }
        return out;
    }

    private static void collect(JsonNode members, List<JsonNode> out) {
        for (JsonNode m : members) {
            String kind = m.path("kind").asText();
            if (kind.equals("method") || kind.equals("constructor")) {
                out.add(m);
            }
            if (m.has("members")) {
                collect(m.get("members"), out);
            }
        }
    }

    private static JsonNode method(JsonNode doc, String idPrefix) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode m : methods(doc)) {
            if (m.get("id").asText().startsWith(idPrefix)) {
                found.add(m);
            }
        }
        assertEquals(1, found.size(), "method " + idPrefix);
        return found.get(0);
    }

    private static Set<String> kinds(JsonNode node) {
        Set<String> out = new HashSet<>();
        walk(node, out);
        return out;
    }

    private static void walk(JsonNode n, Set<String> out) {
        if (n.isObject()) {
            if (n.has("kind")) {
                out.add(n.get("kind").asText());
            }
            n.fields().forEachRemaining(e -> walk(e.getValue(), out));
        } else if (n.isArray()) {
            n.forEach(c -> walk(c, out));
        }
    }

    private static List<String> compileErrors(String text) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests need a JDK");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Organizer.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
        Path out;
        try {
            out = Files.createTempDirectory("acceptance-classes");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // compile only: nothing is loaded or executed
        boolean ok = compiler.getTask(null, null, diagnostics, List.of("--release", "21", "-d", out.toString(), "-proc:none"),
                null, List.of(file)).call();
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(d.getLineNumber() + ": " + d.getMessage(null));
            }
        }
        assertEquals(ok, errors.isEmpty());
        return errors;
    }

    private record Cli(int exit, String stdout) {
        JsonNode json() throws IOException {
            return JSON.readTree(stdout);
        }
    }

    private static Cli run(String... args) {
        var out = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
        return new Cli(exit, out.toString(StandardCharsets.UTF_8));
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    // ---------- fixture ----------

    @Test
    void fixtureIsRealisticAndCompilesUnderJava21() {
        assertTrue(source.lines().count() >= 950, "fixture should be ~1000 lines");
        assertTrue(source.contains("Органайзер") && source.contains("😀"));
        assertEquals(List.of(), compileErrors(source));
    }

    // ---------- parsing and semantics ----------

    @Test
    void parsesCleanlyWithSemanticBindings() {
        assertEquals(1, root.get("protocol_version").asInt());
        assertEquals("completed", root.get("status").asText());
        assertTrue(root.at("/semantic/bindings").asBoolean());
        assertEquals(0, root.get("diagnostics").size(), root.get("diagnostics").toString());
        assertEquals(1, root.get("types").size());
        assertEquals("Organizer", root.at("/types/0/name").asText());
        assertTrue(root.get("imports").size() >= 15);
        // members of local classes live inside a method body ("local_type"): only the flat list has them
        long local = 0;
        for (JsonNode m : root.get("methods")) {
            local += m.get("id").asText().startsWith("local.") ? 1 : 0;
        }
        assertEquals(1, local);
        assertEquals(methods(root).size() + local, root.get("methods").size(), "flat list matches nested members");
    }

    @Test
    void coversMandatoryLanguageConstructs() {
        Set<String> k = kinds(root.get("types"));
        for (String expected : new String[] {"class", "interface", "enum", "enum_constant", "record", "constructor",
                "method", "field", "initializer", "if", "for", "foreach", "while", "do_while", "switch", "case", "try",
                "catch", "throw", "return", "break", "continue", "labeled", "local_type", "variable_declaration",
                "expression", "constructor_call", "method_call", "super_call"}) {
            assertTrue(k.contains(expected), "missing kind " + expected + " in " + k);
        }
        assertFalse(k.contains("unknown"), "no construct of the fixture may degrade to 'unknown'");
    }

    @Test
    void methodIdsAndSignaturesAreExact() {
        Set<String> ids = new HashSet<>();
        root.get("methods").forEach(m -> ids.add(m.get("id").asText()));
        assertEquals(root.get("methods").size(), ids.size(), "method ids are unique");
        for (String id : new String[] {"Organizer.addTask(int,String,Priority,int)", "Organizer.tag(int,String...)",
                "Organizer.main(String[])", "Organizer.Task.compareTo(Task)", "Organizer.maxOf(List<T>)",
                "Organizer.linkTasksToContacts(List<Task>,List<Contact>,Map<Integer, String>)",
                "Organizer.CommandLine.run(BufferedReader)", "Organizer.Counter.next()"}) {
            assertTrue(ids.contains(id), "missing " + id);
        }
        // compact record constructor reports the record components, not an empty parameter list
        JsonNode compact = method(root, "Organizer.Contact.Contact(");
        assertEquals("Organizer.Contact.Contact(int,String,String,String)", compact.get("id").asText());
        assertEquals(4, compact.get("parameters").size());
    }

    @Test
    void resolvesTypesCallsAndThrows() {
        JsonNode addTask = method(root, "Organizer.addTask(");
        assertEquals("Organizer$OrganizerException", addTask.at("/resolved_throws/0/name").asText().replace('.', '$'));
        assertEquals("int", addTask.at("/resolved_return_type/name").asText());
        assertEquals("java.util.List<Organizer.Task>", method(root, "Organizer.search(").at("/resolved_return_type/name")
                .asText().replace("Organizer.Task", "Organizer.Task"));
        boolean resolvedCall = false;
        for (JsonNode stmt : addTask.get("body")) {
            for (JsonNode call : stmt.path("calls")) {
                resolvedCall |= call.path("resolved").asBoolean() && call.path("name").asText().equals("save");
            }
        }
        assertTrue(resolvedCall, "tasks.save(...) must resolve");
    }

    @Test
    void reportsSemanticAndSyntaxErrorsWithoutLosingTheRest() throws IOException {
        String unhandled = source.replace("public int addContact(String name, String email, String city) throws ValidationException {",
                "public int addContact(String name, String email, String city) {");
        JsonNode doc = analyze(unhandled);
        assertEquals("partial", doc.get("status").asText());
        JsonNode d = doc.get("diagnostics").get(0);
        assertEquals("semantic", d.get("category").asText());
        assertEquals("error", d.get("severity").asText());
        assertTrue(d.get("message").asText().contains("Unhandled exception type Organizer.ValidationException"), d.toString());
        assertEquals(unhandled.substring(0, d.at("/source_range/start").asInt()).split("\n", -1).length,
                d.at("/source_range/line").asInt());
        assertNotNull(method(doc, "Organizer.addTask(").get("cfg"));

        JsonNode mismatch = analyze(source.replace("int total = 0;\n        for (Task task : tasks.all())", "int total = \"x\";\n        for (Task task : tasks.all())"));
        assertTrue(mismatch.get("diagnostics").toString().contains("Type mismatch"));

        JsonNode unresolved = analyze(source.replace("return Math.abs(a);", "return Math.absolute(a);"));
        assertEquals("partial", unresolved.get("status").asText());
        assertTrue(unresolved.get("diagnostics").toString().contains("absolute"));

        // syntax error in one method: the others are still fully analysed
        JsonNode broken = analyze(source.replace("int steps = 0;\n        while (n > 1) {", "int steps = 0;\n        while (n > 1 {"));
        assertEquals("partial", broken.get("status").asText());
        assertEquals("syntax", broken.get("diagnostics").get(0).get("category").asText());
        assertEquals("O(n)", method(broken, "Organizer.fibonacci(").at("/analysis/complexity/time").asText());
        assertEquals(methods(root).size(), methods(broken).size());
    }

    // ---------- CFG ----------

    @Test
    void everyCfgSatisfiesStructuralInvariants() {
        int checked = 0;
        for (JsonNode m : methods(root)) {
            JsonNode cfg = m.get("cfg");
            if (cfg.isNull()) {
                assertTrue(m.get("body").isNull(), m.get("id") + " has a body but no cfg");
                continue;
            }
            checked++;
            String id = m.get("id").asText();
            Map<String, JsonNode> nodes = new HashMap<>();
            for (JsonNode n : cfg.get("nodes")) {
                assertTrue(nodes.put(n.get("id").asText(), n) == null, id + ": duplicate node id");
            }
            Map<String, List<String>> out = new HashMap<>();
            Set<String> hasIncoming = new HashSet<>();
            for (JsonNode e : cfg.get("edges")) {
                String from = e.get("from").asText();
                String to = e.get("to").asText();
                assertTrue(nodes.containsKey(from) && nodes.containsKey(to), id + ": dangling edge");
                out.computeIfAbsent(from, x -> new ArrayList<>()).add(to);
                hasIncoming.add(to);
            }
            String entry = cfg.get("entry").asText();
            String exit = cfg.get("exit").asText();
            assertFalse(hasIncoming.contains(entry), id + ": entry has predecessors");
            assertFalse(out.containsKey(exit), id + ": exit has successors");

            Set<String> reachable = new HashSet<>(Set.of(entry));
            Deque<String> work = new ArrayDeque<>(List.of(entry));
            while (!work.isEmpty()) {
                for (String next : out.getOrDefault(work.pop(), List.of())) {
                    if (reachable.add(next)) {
                        work.push(next);
                    }
                }
            }
            for (JsonNode n : nodes.values()) {
                boolean flaggedUnreachable = n.has("reachable") && !n.get("reachable").asBoolean();
                assertEquals(!reachable.contains(n.get("id").asText()), flaggedUnreachable,
                        id + ": reachability flag of " + n.get("id"));
            }
            assertTrue(reachable.contains(exit) || id.contains("main"), id + ": exit unreachable");

            // source ranges: inside the file, text equals the source slice (UTF-16 offsets)
            for (JsonNode n : nodes.values()) {
                if (!n.has("text") || n.get("text").asText().startsWith("catch (")) {
                    continue; // a catch node's range covers the exception declaration only
                }
                int start = n.at("/source_range/start").asInt();
                int length = n.at("/source_range/length").asInt();
                assertTrue(start >= 0 && start + length <= source.length(), id + ": range outside file");
                assertEquals(normalize(source.substring(start, start + length)), normalize(n.get("text").asText()),
                        id + ": " + n.get("id"));
                assertEquals(source.substring(0, start).split("\n", -1).length, n.at("/source_range/line").asInt());
            }
        }
        assertTrue(checked > 90, "checked " + checked);
    }

    @Test
    void labeledJumpsAndFinallyAreWiredCorrectly() {
        JsonNode cfg = method(root, "Organizer.findFirstNegative(").get("cfg");
        Map<String, String> text = new HashMap<>();
        cfg.get("nodes").forEach(n -> text.put(n.get("id").asText(), n.path("text").asText()));
        String breakTarget = null;
        String continueTarget = null;
        for (JsonNode e : cfg.get("edges")) {
            if (text.get(e.get("from").asText()).startsWith("break outer")) {
                assertEquals("break", e.get("kind").asText());
                breakTarget = text.get(e.get("to").asText());
            }
            if (text.get(e.get("from").asText()).startsWith("continue outer")) {
                assertEquals("continue", e.get("kind").asText());
                continueTarget = text.get(e.get("to").asText());
            }
        }
        assertEquals("return found;", breakTarget, "labeled break leaves both loops");
        assertEquals("r++", continueTarget, "labeled continue goes to the outer update");

        // the finally block is duplicated on each exit path of parseOrDefault
        int copies = 0;
        for (JsonNode n : method(root, "Organizer.parseOrDefault(").get("cfg").get("nodes")) {
            copies += n.path("text").asText().startsWith("fallbackCounter++") ? 1 : 0;
        }
        assertTrue(copies >= 3, "finally copies: " + copies);

        boolean back = false;
        for (JsonNode e : method(root, "Organizer.gcd(").get("cfg").get("edges")) {
            back |= e.path("back").asBoolean();
        }
        assertTrue(back, "while loop has a back edge");

        for (JsonNode n : method(root, "Organizer.binarySearch(").get("cfg").get("nodes")) {
            if (n.path("kind").asText().equals("branch")) {
                int labels = 0;
                for (JsonNode e : method(root, "Organizer.binarySearch(").get("cfg").get("edges")) {
                    if (e.get("from").asText().equals(n.get("id").asText())
                            && (e.get("kind").asText().equals("true") || e.get("kind").asText().equals("false"))) {
                        labels++;
                    }
                }
                assertEquals(2, labels, "branch " + n.get("text") + " has true and false edges");
            }
        }
    }

    // ---------- complexity and findings ----------

    @Test
    void classifiesComplexityOfTheFixtureMethods() {
        Map<String, String> expected = new HashMap<>();
        expected.put("Organizer.fibonacci(", "O(n)");
        expected.put("Organizer.multiply(", "O(n^3)");
        expected.put("Organizer.halvings(", "O(log n)");
        expected.put("Organizer.digitSum(", "O(log n)");
        expected.put("Organizer.fixedGrid(", "O(1)");
        expected.put("Organizer.bubbleSort(", "O(n^2)");
        expected.put("Organizer.linkTasksIndexed(", "O(n + m)");
        expected.put("Organizer.linkTasksToContacts(", "O(n*m)");
        expected.put("Organizer.gcd(", "unknown");
        expected.put("Organizer.binarySearch(", "unknown");
        expected.put("Organizer.countWhere(", "unknown");
        expected.put("Organizer.clamp(", "O(1)");
        expected.forEach((id, time) -> assertEquals(time, method(root, id).at("/analysis/complexity/time").asText(), id));
        assertEquals("low", method(root, "Organizer.gcd(").at("/analysis/complexity/confidence").asText());
    }

    @Test
    void reportsTheNestedRepeatedScanWithEvidence() {
        JsonNode m = method(root, "Organizer.linkTasksToContacts(");
        JsonNode findings = m.at("/analysis/findings");
        assertEquals(1, findings.size());
        JsonNode f = findings.get(0);
        assertEquals("repeated_collection_scan", f.get("id").asText());
        assertEquals("warning", f.get("severity").asText());
        assertTrue(f.get("message").asText().contains("contactList"));
        assertTrue(f.get("explanation").asText().contains("once per iteration"));
        assertTrue(f.get("recommendation").asText().contains("Build a Map keyed by the compared value"));
        assertEquals("n = number of elements in taskList", m.at("/analysis/complexity/assumptions/0").asText());
        assertEquals("m = number of elements in contactList", m.at("/analysis/complexity/assumptions/1").asText());

        int start = f.at("/source_range/start").asInt();
        assertTrue(source.startsWith("for (Contact contact : contactList) {", start));
        assertEquals(source.substring(0, start).split("\n", -1).length, f.at("/source_range/line").asInt());
        JsonNode ids = f.get("node_ids");
        Map<String, String> text = new HashMap<>();
        m.at("/cfg/nodes").forEach(n -> text.put(n.get("id").asText(), n.path("text").asText()));
        assertEquals("Task task : taskList", text.get(ids.get(0).asText()));
        assertEquals("Contact contact : contactList", text.get(ids.get(1).asText()));

        // the indexed version, the constant grid and the false-positive guard produce no findings
        for (String id : new String[] {"Organizer.linkTasksIndexed(", "Organizer.fixedGrid(", "Organizer.findFirstNegative(",
                "Organizer.multiply("}) {
            assertEquals(0, method(root, id).at("/analysis/findings").size(), id);
        }
        assertEquals("repeated_collection_scan", method(root, "Organizer.findDuplicateTitles(")
                .at("/analysis/findings/0/id").asText());
    }

    // ---------- replace_condition ----------

    private static String conditionNode(JsonNode doc, String methodPrefix, String statementKind, String conditionText) {
        JsonNode m = method(doc, methodPrefix);
        for (JsonNode n : m.at("/cfg/nodes")) {
            if (n.path("statement_kind").asText().equals(statementKind) && n.path("text").asText().equals(conditionText)) {
                return n.get("id").asText();
            }
        }
        throw new AssertionError("no " + statementKind + " node '" + conditionText + "' in " + methodPrefix);
    }

    private Cli transform(Path dir, String methodPrefix, String nodeId, String expression) throws IOException {
        Files.writeString(dir.resolve("Organizer.java"), source, StandardCharsets.UTF_8);
        var op = JSON.createObjectNode();
        op.put("method_id", method(root, methodPrefix).get("id").asText());
        op.put("node_id", nodeId);
        op.putObject("operation").put("type", "replace_condition").put("expression", expression);
        Files.writeString(dir.resolve("op.json"), JSON.writeValueAsString(op), StandardCharsets.UTF_8);
        return run("transform", "--input", dir.resolve("Organizer.java").toString(),
                "--operation", dir.resolve("op.json").toString(), "--output", dir.resolve("Out.java").toString());
    }

    private void assertReplaced(Path dir, String methodPrefix, String kind, String oldCondition, String newCondition,
                                String oldLine, String newLine) throws IOException {
        Cli cli = transform(dir, methodPrefix, conditionNode(root, methodPrefix, kind, oldCondition), newCondition);
        assertEquals(0, cli.exit(), cli.stdout());
        assertEquals("completed", cli.json().get("status").asText());
        String out = Files.readString(dir.resolve("Out.java"), StandardCharsets.UTF_8);
        assertEquals(1, source.split(java.util.regex.Pattern.quote(oldLine), -1).length - 1, "unique anchor: " + oldLine);
        assertEquals(source.replace(oldLine, newLine), out, "only the condition changes");
        assertEquals(List.of(), compileErrors(out));

        JsonNode again = analyze(out);
        assertEquals("completed", again.get("status").asText());
        assertEquals(0, again.get("diagnostics").size(), again.get("diagnostics").toString());
        assertNotNull(conditionNode(again, methodPrefix, kind, newCondition));
        assertEquals(methods(root).size(), methods(again).size());
        Files.delete(dir.resolve("Out.java"));
    }

    @Test
    void replacesEachConditionKindInTheFixture(@TempDir Path dir) throws IOException {
        // clamp() sits below a line with an emoji: offsets after it must still be exact (UTF-16)
        assertReplaced(dir, "Organizer.clamp(", "if", "value < low", "value < low && low <= high",
                "if (value < low) {", "if (value < low && low <= high) {");
        assertReplaced(dir, "Organizer.gcd(", "while", "b != 0", "b > 0 && a > 0",
                "while (b != 0) {", "while (b > 0 && a > 0) {");
        assertReplaced(dir, "Organizer.digitSum(", "do_while", "rest > 0", "rest != 0",
                "} while (rest > 0);", "} while (rest != 0);");
        assertReplaced(dir, "Organizer.fibonacci(", "for", "i <= n", "i < n + 1",
                "for (int i = 2; i <= n; i++) {", "for (int i = 2; i < n + 1; i++) {");
    }

    @Test
    void replacementWithCyrillicTextKeepsEverythingElse(@TempDir Path dir) throws IOException {
        assertReplaced(dir, "Organizer.isPalindrome(", "while", "left < right", "left < right && !\"Привет 😀\".isEmpty()",
                "while (left < right) {", "while (left < right && !\"Привет 😀\".isEmpty()) {");
    }

    @Test
    void rejectsBadTransformationsAndWritesNothing(@TempDir Path dir) throws IOException {
        String node = conditionNode(root, "Organizer.clamp(", "if", "value < low");
        Object[][] cases = {
                {node, "value <", "invalid_expression"},
                {node, "value + 1", "validation_failed"},
                {node, "unknownName > 1", "validation_failed"},
                {"m0.n999", "true", "unknown_node"},
        };
        for (Object[] c : cases) {
            Cli cli = transform(dir, "Organizer.clamp(", (String) c[0], (String) c[1]);
            assertEquals(1, cli.exit());
            assertEquals(c[2], cli.json().at("/error/code").asText(), cli.stdout());
            assertFalse(Files.exists(dir.resolve("Out.java")));
        }
        // the CFG entry node is not a condition
        Cli entry = transform(dir, "Organizer.clamp(", method(root, "Organizer.clamp(").at("/cfg/entry").asText(), "true");
        assertEquals("unsupported_node", entry.json().at("/error/code").asText());
        assertFalse(Files.exists(dir.resolve("Out.java")));
    }
}
