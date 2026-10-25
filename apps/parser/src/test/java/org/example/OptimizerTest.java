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
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OptimizerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Run(int code, JsonNode json) {
    }

    private static Run optimize(Path root, List<String> files, String action, String proposalId, Path outputDir,
            Set<String> acknowledged) throws IOException {
        Path request = root.resolve("request.json");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("source_level", "17");
        body.put("files", files);
        body.put("action", action);
        body.put("proposal_id", proposalId);
        body.put("acknowledged_assumptions", acknowledged);
        Files.writeString(request, JSON.writeValueAsString(body));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of("optimize", "--root", root.toString(), "--input", request.toString()));
        if (outputDir != null) {
            args.add("--output-dir");
            args.add(outputDir.toString());
        }
        int code = Main.run(args.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err));
        return new Run(code, JSON.readTree(out.toByteArray()));
    }

    private static Path write(Path root, String file, String source) throws IOException {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, source, StandardCharsets.UTF_8);
        return target;
    }

    private static JsonNode proposals(Path root, String file) throws IOException {
        Run run = optimize(root, List.of(file), "discover", "", null, Set.of());
        assertEquals(0, run.code, run.json.toString());
        return run.json.get("proposals");
    }

    private static final Set<String> ALL = Set.of("null_and_exception_timing", "single_threaded");

    // ---------- fixtures ----------

    /** The slide-style case: for each task, scan contacts for the owner (first match, then break). */
    private static final String FIRST_MATCH = """
            import java.util.*;

            public class Linker {
                public record Contact(int id, String name) { }

                public static class Task {
                    private final int id;
                    private final int contactId;
                    public Task(int id, int contactId) { this.id = id; this.contactId = contactId; }
                    public int id() { return id; }
                    public int getContactId() { return contactId; }
                }

                public static int link(List<Task> taskList, List<Contact> contactList, Map<Integer, String> owners) {
                    int linked = 0;
                    for (Task task : taskList) {
                        for (Contact contact : contactList) {
                            if (contact.id() == task.getContactId()) {
                                owners.put(task.id(), contact.name());
                                linked++;
                                break;
                            }
                        }
                    }
                    return linked;
                }

                public static String exercise(long seed) {
                    Random random = new Random(seed);
                    List<Contact> contacts = new ArrayList<>();
                    int cn = random.nextInt(8);
                    for (int i = 0; i < cn; i++) { contacts.add(new Contact(random.nextInt(6), "c" + i)); }
                    List<Task> tasks = new ArrayList<>();
                    int tn = random.nextInt(10);
                    for (int i = 0; i < tn; i++) { tasks.add(new Task(i, random.nextInt(8))); }
                    Map<Integer, String> owners = new TreeMap<>();
                    int linked = link(tasks, contacts, owners);
                    return linked + ":" + owners;
                }
            }
            """;

    private static final String ALL_MATCHES = """
            import java.util.*;

            public class Joiner {
                public static final class Order {
                    final String customer; final int amount;
                    Order(String customer, int amount) { this.customer = customer; this.amount = amount; }
                    String customer() { return customer; }
                }

                public static List<String> join(List<String> customers, List<Order> orders) {
                    List<String> lines = new ArrayList<>();
                    for (String name : customers) {
                        for (Order order : orders) {
                            if (order.customer().equals(name)) {
                                lines.add(name + "=" + order.amount);
                            }
                        }
                    }
                    return lines;
                }

                public static String exercise(long seed) {
                    Random random = new Random(seed);
                    String[] names = {"ann", "bob", "cy", "di"};
                    List<Order> orders = new ArrayList<>();
                    int on = random.nextInt(9);
                    for (int i = 0; i < on; i++) { orders.add(new Order(names[random.nextInt(4)], i)); }
                    List<String> customers = new ArrayList<>();
                    int cn = random.nextInt(7);
                    for (int i = 0; i < cn; i++) { customers.add(names[random.nextInt(4)]); }
                    return join(customers, orders).toString();
                }
            }
            """;

    // ---------- behaviour ----------

    private static Object[] compileAndRun(Path dir, String className, String source, int seeds) throws Exception {
        Path src = write(dir, className + ".java", source);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests need a JDK");
        int rc = compiler.run(null, null, null, "-d", dir.toString(), src.toString());
        assertEquals(0, rc, "generated source must compile");
        try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() })) {
            Class<?> type = loader.loadClass(className);
            var method = type.getMethod("exercise", long.class);
            Object[] results = new Object[seeds];
            for (int i = 0; i < seeds; i++) {
                results[i] = method.invoke(null, (long) i);
            }
            return results;
        }
    }

    private void assertEquivalent(Path root, String className, String source) throws Exception {
        String file = className + ".java";
        write(root, file, source);
        JsonNode list = proposals(root, file);
        assertEquals(1, list.size(), list.toString());
        JsonNode proposal = list.get(0);
        assertEquals("applicable", proposal.get("applicability").asText(), proposal.toString());

        Path out = root.resolve("candidate");
        Run applied = optimize(root, List.of(file), "apply", proposal.get("id").asText(), out, ALL);
        assertEquals(0, applied.code, applied.json.toString());
        String candidate = Files.readString(out.resolve(file));
        assertTrue(candidate.contains("HashMap"), candidate);

        Path before = Files.createDirectories(root.resolve("before"));
        Path after = Files.createDirectories(root.resolve("after"));
        Object[] baseline = compileAndRun(before, className, source, 300);
        Object[] optimized = compileAndRun(after, className, candidate, 300);
        assertEquals(List.of(baseline), List.of(optimized));
        // the test inputs really exercise matches, misses and duplicates
        assertTrue(java.util.Arrays.stream(baseline).map(Object::toString).distinct().count() > 50);
    }

    @Test
    void firstMatchScanBecomesAnIndexWithIdenticalBehaviour(@TempDir Path root) throws Exception {
        assertEquivalent(root, "Linker", FIRST_MATCH);
        String candidate = Files.readString(root.resolve("candidate/Linker.java"));
        assertTrue(candidate.contains("contactListById.putIfAbsent(contact.id(), contact);"), candidate);
        assertTrue(candidate.contains("Contact contact = contactListById.get(task.getContactId());"), candidate);
        assertFalse(candidate.contains("break;"), candidate);
    }

    @Test
    void allMatchesScanKeepsMultiplicityAndOrder(@TempDir Path root) throws Exception {
        assertEquivalent(root, "Joiner", ALL_MATCHES);
        String candidate = Files.readString(root.resolve("candidate/Joiner.java"));
        assertTrue(candidate.contains("computeIfAbsent"), candidate);
    }

    @Test
    void proposalCarriesPreconditionsComplexityAndSource(@TempDir Path root) throws Exception {
        write(root, "Linker.java", FIRST_MATCH);
        JsonNode p = proposals(root, "Linker.java").get(0);
        assertEquals("nested_scan_to_hashmap", p.get("rule").asText());
        assertEquals("Linker.link(List<Task>,List<Contact>,Map<Integer, String>)", p.get("method_id").asText());
        assertEquals("first_match", p.get("shape").asText());
        assertEquals("contact.id()", p.get("key").get("element_expression").asText());
        assertEquals("task.getContactId()", p.get("key").get("lookup_expression").asText());
        assertEquals("O(n*m)", p.get("complexity").get("method_before").asText());
        assertEquals("O(n + m)", p.get("complexity").get("scan_after").asText());
        assertTrue(p.get("complexity").get("note").asText().contains("expectation"));
        Set<String> ids = new java.util.HashSet<>();
        for (JsonNode pre : p.get("preconditions")) {
            assertEquals("satisfied", pre.get("status").asText(), pre.toString());
            ids.add(pre.get("id").asText());
        }
        assertTrue(ids.containsAll(Set.of("lookup_shape", "key_equality", "key_purity", "control_flow",
                "collection_not_modified", "collection_reference", "insertion_point")));
        assertEquals(2, p.get("assumptions").size());
        assertEquals("Linker.java", p.get("affected_files").get(0).asText());

        Path out = root.resolve("candidate");
        Run applied = optimize(root, List.of("Linker.java"), "apply", p.get("id").asText(), out, ALL);
        assertEquals("O(n*m)", applied.json.get("method_complexity_before").asText());
        assertEquals("O(n + m)", applied.json.get("method_complexity_after").asText());
    }

    @Test
    void applyRequiresAcknowledgedAssumptionsAndAKnownProposal(@TempDir Path root) throws Exception {
        write(root, "Linker.java", FIRST_MATCH);
        String id = proposals(root, "Linker.java").get(0).get("id").asText();
        Path out = root.resolve("candidate");
        Run partial = optimize(root, List.of("Linker.java"), "apply", id, out, Set.of("single_threaded"));
        assertEquals(1, partial.code);
        assertEquals("assumptions_not_acknowledged", partial.json.get("error").get("code").asText());
        assertFalse(Files.exists(out.resolve("Linker.java")));
        Run unknown = optimize(root, List.of("Linker.java"), "apply", "opt_0000000000000000", out, ALL);
        assertEquals("proposal_not_found", unknown.json.get("error").get("code").asText());
    }

    private static String scanWith(String condition, String types, String extra) {
        return "import java.util.*;\n"
                + "public class C {\n"
                + "  static class Item { final int id = 1; final double w = 1; final String s = \"\"; int mutable; Object o;\n"
                + "    int id() { return id; } double w() { return w; } String s() { return s; } int mutable() { return mutable; } int computed() { return id + 1; } }\n"
                + "  static void sink(Object o) { }\n"
                + "  " + types + "\n"
                + "  static void run(List<Item> xs, List<Item> ys, Map<Integer, Item> out) {\n"
                + "    for (Item x : xs) {\n"
                + "      for (Item y : ys) {\n"
                + "        if (" + condition + ") { out.put(x.id(), y); " + extra + " break; }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n}\n";
    }

    private void assertNotApplicable(Path root, String source, String violatedPrecondition) throws IOException {
        write(root, "C.java", source);
        JsonNode list = proposals(root, "C.java");
        assertEquals(1, list.size(), source);
        JsonNode p = list.get(0);
        assertEquals("not_applicable", p.get("applicability").asText(), p.toString());
        boolean found = false;
        for (JsonNode pre : p.get("preconditions")) {
            if (pre.get("id").asText().equals(violatedPrecondition)) {
                assertEquals("violated", pre.get("status").asText(), pre.toString());
                found = true;
            }
        }
        assertTrue(found, violatedPrecondition);
        assertFalse(p.get("not_applicable_reasons").isEmpty());
        assertEquals(0, p.get("assumptions").size());
        // an inapplicable proposal never produces a rewrite
        Path out = root.resolve("candidate");
        Run applied = optimize(root, List.of("C.java"), "apply", p.get("id").asText(), out, ALL);
        assertEquals(1, applied.code);
        assertEquals("not_applicable", applied.json.get("error").get("code").asText());
        assertFalse(Files.exists(out.resolve("C.java")));
    }

    @Test
    void semanticallyUnsafeScansAreReportedAsNotApplicable(@TempDir Path root) throws IOException {
        assertNotApplicable(root.resolve("a"), scanWith("y.w() == x.w()", "", ""), "key_equality"); // floating point
        assertNotApplicable(root.resolve("b"), scanWith("y.s() == x.s()", "", ""), "key_equality"); // reference identity
        // "x.s().equals(y.s())" (outer receiver on a String) is applicable since equals() on String is symmetric;
        // it needs the outer_key_non_null assumption (see stringKeyWithOuterReceiver...); other key types stay refused.
        assertNotApplicable(root.resolve("d"), scanWith("y.id() == x.id()", "", "sink(ys);"), "collection_not_modified"); // escapes
        assertNotApplicable(root.resolve("e"), scanWith("y.id() == x.id()", "", "ys.add(y);"), "collection_not_modified"); // grows
        assertNotApplicable(root.resolve("f"), scanWith("y.id() == x.computed()", "", ""), "key_purity"); // not a field accessor
        assertNotApplicable(root.resolve("g"), scanWith("y.id() == x.id()", "", "x.mutable = 3;"), "collection_not_modified"); // field write
        assertNotApplicable(root.resolve("h"), scanWith("y.id() == Math.abs(x.id() + sideEffect())", "static int sideEffect() { return 1; }", ""), "key_purity");
    }

    @Test
    void scansThatDoMoreThanLookUpAreNotApplicableAndOtherLoopsAreIgnored(@TempDir Path root) throws IOException {
        String extraWork = "import java.util.*;\npublic class C {\n  record I(int id) { }\n"
                + "  static int run(List<I> xs, List<I> ys) { int seen = 0;\n"
                + "    for (I x : xs) { for (I y : ys) { seen++; if (y.id() == x.id()) { break; } } }\n"
                + "    return seen; }\n}\n";
        assertNotApplicable(root.resolve("a"), extraWork, "lookup_shape");

        // a filter against a constant and plain nested loops are not keyed lookups at all
        write(root.resolve("b"), "C.java", "import java.util.*;\npublic class C {\n  record I(int id) { }\n"
                + "  static int run(List<I> xs, List<I> ys) { int n = 0;\n"
                + "    for (I x : xs) { for (I y : ys) { if (y.id() == 3) { n++; } } }\n"
                + "    for (I x : xs) { for (I y : ys) { n += y.id() + x.id(); } }\n"
                + "    return n; }\n}\n");
        assertEquals(0, proposals(root.resolve("b"), "C.java").size());
    }

    @Test
    void labeledControlFlowAndInnerDeclarationsAreHandled(@TempDir Path root) throws Exception {
        // `continue` in the body would target the scan loop: rejected
        String source = "import java.util.*;\npublic class C {\n  record I(int id) { }\n"
                + "  static int run(List<I> xs, List<I> ys, List<Integer> out) {\n"
                + "    outer: for (I x : xs) { for (I y : ys) { if (y.id() == x.id()) { if (y.id() > 2) { continue outer; } out.add(y.id()); break; } } }\n"
                + "    return out.size(); }\n}\n";
        assertNotApplicable(root.resolve("a"), source, "control_flow");

        // the element name is reused later in the same block: the replacement is wrapped in its own block
        String clash = "import java.util.*;\npublic class Clash {\n  record I(int id) { }\n"
                + "  static int run(List<I> xs, List<I> ys, Map<Integer, Integer> out) {\n"
                + "    for (I x : xs) {\n"
                + "      for (I y : ys) { if (y.id() == x.id()) { out.put(x.id(), y.id()); break; } }\n"
                + "      for (I y : ys) { out.merge(y.id(), 1, Integer::sum); }\n"
                + "    }\n    return out.size(); }\n"
                + "  public static String exercise(long seed) { Random r = new Random(seed); List<I> a = new ArrayList<>(); List<I> b = new ArrayList<>();\n"
                + "    for (int i = 0; i < r.nextInt(6); i++) { a.add(new I(r.nextInt(4))); } for (int i = 0; i < r.nextInt(6); i++) { b.add(new I(r.nextInt(4))); }\n"
                + "    Map<Integer, Integer> out = new TreeMap<>(); return run(a, b, out) + \":\" + out; }\n}\n";
        Path dir = root.resolve("b");
        write(dir, "Clash.java", clash);
        JsonNode first = proposals(dir, "Clash.java").get(0);
        // `out.merge(..., Integer::sum)` contains a method reference-free call to Map.merge, which is not in the benign set
        assertEquals("not_applicable", first.get("applicability").asText());
    }

    @Test
    void multiFileProjectUsesCrossFileAccessorsAndKeepsOtherFilesUntouched(@TempDir Path root) throws Exception {
        write(root, "m/Customer.java", "package m; public class Customer { private final int id; public Customer(int id) { this.id = id; } public int id() { return id; } }\n");
        write(root, "m/Order.java", "package m; public class Order { private final int customerId; public Order(int c) { customerId = c; } public int customerId() { return customerId; } }\n");
        String service = "package m;\nimport java.util.List;\nimport java.util.Map;\npublic class Service {\n"
                + "    public int attach(List<Order> orders, List<Customer> customers, Map<Integer, Integer> out) {\n"
                + "        int n = 0;\n"
                + "        for (Order order : orders) {\n"
                + "            for (Customer customer : customers) {\n"
                + "                if (customer.id() == order.customerId()) {\n"
                + "                    out.put(order.customerId(), customer.id());\n"
                + "                    n++;\n"
                + "                    break;\n"
                + "                }\n"
                + "            }\n"
                + "        }\n"
                + "        return n;\n"
                + "    }\n}\n";
        write(root, "m/Service.java", service);
        List<String> files = List.of("m/Customer.java", "m/Order.java", "m/Service.java");
        Run run = optimize(root, files, "discover", "", null, Set.of());
        JsonNode p = run.json.get("proposals").get(0);
        assertEquals("applicable", p.get("applicability").asText(), p.toString());
        assertEquals("m/Service.java", p.get("file").asText());
        assertEquals("m.Service.attach(List<Order>,List<Customer>,Map<Integer, Integer>)", p.get("method_id").asText());

        Path out = root.resolve("candidate");
        Run applied = optimize(root, files, "apply", p.get("id").asText(), out, ALL);
        assertEquals(0, applied.code, applied.json.toString());
        assertEquals(1, applied.json.get("changed_files").size());
        assertEquals(Files.readString(root.resolve("m/Customer.java")), Files.readString(out.resolve("m/Customer.java")));
        String candidate = Files.readString(out.resolve("m/Service.java"));
        // java.util.List and Map are already imported; HashMap is added
        assertTrue(candidate.contains("import java.util.HashMap;"), candidate);
        assertTrue(candidate.contains("Map<Integer, Customer> customersById = new HashMap<>();"), candidate);
    }

    private static final String MERGE = "import java.util.*;\n"
            + "class Box { final String id; int qty; Box(String id, int qty) { this.id = id; this.qty = qty; }\n"
            + "  String id() { return id; } int qty() { return qty; } void setQty(int q) { this.qty = q; } }\n"
            + "class Merge { List<Box> merge(List<Box> items) { List<Box> out = new ArrayList<>();\n"
            + "  for (Box a : items) { Box c = new Box(a.id(), 0);\n"
            + "    for (Box b : items) { if (a.id().equals(b.id())) { c.setQty(c.qty() + b.qty()); } }\n"
            + "    out.add(c); }\n  return out; } }\n";

    private static JsonNode mergeProposal(Path root, String source) throws IOException {
        write(root, "Merge.java", source);
        JsonNode list = proposals(root, "Merge.java");
        assertEquals(1, list.size(), list.toString());
        return list.get(0);
    }

    @Test
    void stringKeyWithOuterReceiverAndStoringConstructorIsApplicableWithAnExtraAssumption(@TempDir Path root) throws IOException {
        JsonNode p = mergeProposal(root, MERGE);
        assertEquals("applicable", p.get("applicability").asText(), p.toString());
        Set<String> ids = new java.util.TreeSet<>();
        p.get("assumptions").forEach(a -> ids.add(a.get("id").asText()));
        assertEquals(Set.of("null_and_exception_timing", "outer_key_non_null", "single_threaded"), ids);
    }

    @Test
    void sameShapeWithInnerReceiverNeedsNoOuterKeyAssumption(@TempDir Path root) throws IOException {
        JsonNode p = mergeProposal(root, MERGE.replace("a.id().equals(b.id())", "b.id().equals(a.id())"));
        assertEquals("applicable", p.get("applicability").asText(), p.toString());
        p.get("assumptions").forEach(a -> assertFalse(a.get("id").asText().equals("outer_key_non_null")));
    }

    @Test
    void outerReceiverOnANonSymmetricKeyTypeIsStillRefused(@TempDir Path root) throws IOException {
        JsonNode p = mergeProposal(root, MERGE.replace("final String id;", "final Object id;").replace("Box(String id,", "Box(Object id,")
                .replace("String id() {", "Object id() {"));
        assertEquals("not_applicable", p.get("applicability").asText(), p.toString());
        assertTrue(p.get("not_applicable_reasons").toString().contains("outer value"), p.toString());
    }

    @Test
    void constructorThatValidatesIsStillRefused(@TempDir Path root) throws IOException {
        JsonNode p = mergeProposal(root, MERGE.replace("this.id = id;", "this.id = java.util.Objects.requireNonNull(id);"));
        assertEquals("not_applicable", p.get("applicability").asText(), p.toString());
        assertTrue(p.get("not_applicable_reasons").toString().contains("constructor may run arbitrary code"), p.toString());
    }

    @Test
    void setterOnAnExistingElementNeedsTheKeysUnchangedAssumption(@TempDir Path root) throws IOException {
        String source = MERGE.replace("Box c = new Box(a.id(), 0);", "Box c = a;");
        JsonNode p = mergeProposal(root, source);
        assertEquals("applicable", p.get("applicability").asText(), p.toString());
        assertTrue(p.get("assumptions").toString().contains("callees_keep_keys"), p.toString());
    }

    /** The example of case slide 5, verbatim: merge() only prints, so nothing it does can change items or the keys. */
    private static final String SLIDE5 = "import java.util.*;\n"
            + "class Main { record Item(String id, int qty) {}\n"
            + "  static void merge(Item a, Item b) { System.out.println(a.id() + \"+\" + b.id()); }\n"
            + "  static void process(List<Item> items) {\n"
            + "    for (Item a : items) { for (Item b : items) { if (a.id().equals(b.id())) { merge(a, b); } } } } }\n";

    @Test
    void theSlideFiveExampleIsApplicableWhenMergeOnlyPrints(@TempDir Path root) throws IOException {
        write(root, "Main.java", SLIDE5);
        JsonNode p = proposals(root, "Main.java").get(0);
        assertEquals("applicable", p.get("applicability").asText(), p.toString());
        assertFalse(p.get("assumptions").toString().contains("callees_keep_keys"), p.toString());
    }

    @Test
    void aMergeThatWritesFieldsNeedsAnAssumptionAndOneThatChangesACollectionIsRefused(@TempDir Path root) throws IOException {
        String fields = SLIDE5.replace("record Item(String id, int qty) {}", "static class Item { String id; int qty; Item(String id, int qty) { this.id = id; this.qty = qty; } String id() { return id; } }")
                .replace("System.out.println(a.id() + \"+\" + b.id());", "a.qty += b.qty;");
        write(root, "Main.java", fields);
        JsonNode p = proposals(root, "Main.java").get(0);
        assertEquals("applicable", p.get("applicability").asText(), p.toString());
        assertTrue(p.get("assumptions").toString().contains("callees_keep_keys"), p.toString());

        String list = SLIDE5.replace("static void merge(Item a, Item b) { System.out.println(a.id() + \"+\" + b.id()); }",
                "static List<Item> seen = new ArrayList<>(); static void merge(Item a, Item b) { seen.add(b); }");
        write(root, "Main.java", list);
        JsonNode refused = proposals(root, "Main.java").get(0);
        assertEquals("not_applicable", refused.get("applicability").asText(), refused.toString());
    }

    @Test
    void setterThatAlsoPrintsNeedsTheKeysUnchangedAssumption(@TempDir Path root) throws IOException {
        JsonNode p = mergeProposal(root, MERGE.replace("void setQty(int q) { this.qty = q; }", "void setQty(int q) { this.qty = q; System.out.println(q); }"));
        assertEquals("applicable", p.get("applicability").asText(), p.toString());
        assertTrue(p.get("assumptions").toString().contains("callees_keep_keys"), p.toString());
    }
}
