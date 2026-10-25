package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class AnalysisTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode parse(Path dir, String source) throws IOException {
        Path file = Files.writeString(dir.resolve("Example.java"), source);
        var out = new ByteArrayOutputStream();
        Main.run(new String[] {file.toString()}, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
        return JSON.readTree(out.toString(StandardCharsets.UTF_8));
    }

    /** The {@code analysis} of the first method of the first type. */
    private static JsonNode analyze(Path dir, String members) throws IOException {
        JsonNode root = parse(dir, "import java.util.*;\npublic class Example {\n" + members + "\n}\n");
        JsonNode analysis = root.at("/types/0/members/0/analysis");
        assertFalse(analysis.isMissingNode(), root.toString());
        assertTrue(analysis.isObject(), "analysis missing: " + root.get("diagnostics"));
        return analysis;
    }

    private static String time(JsonNode analysis) {
        return analysis.at("/complexity/time").asText();
    }

    private static JsonNode finding(JsonNode analysis, String id) {
        for (JsonNode f : analysis.get("findings")) {
            if (id.equals(f.get("id").asText())) {
                return f;
            }
        }
        return null;
    }

    @Test
    void singleCollectionLoopIsLinear(@TempDir Path dir) throws IOException {
        JsonNode a = analyze(dir, "int sum(List<Integer> items) { int s = 0; for (int x : items) { s += x; } return s; }");
        assertEquals("O(n)", time(a));
        assertEquals("high", a.at("/complexity/confidence").asText());
        assertTrue(a.at("/complexity/reason").asText().contains("a loop over items"));
        assertEquals("n = number of elements in items", a.at("/complexity/assumptions/0").asText());
        assertEquals(0, a.get("findings").size());
    }

    @Test
    void sequentialLoopsKeepTheDominantTerm(@TempDir Path dir) throws IOException {
        JsonNode a = analyze(dir, """
                int f(List<Integer> items) {
                    int s = 0;
                    for (int x : items) { s += x; }
                    for (int i = 0; i < items.size(); i++) { s -= items.get(i); }
                    return s;
                }""");
        assertEquals("O(n)", time(a));
        assertTrue(a.at("/complexity/reason").asText().contains("sequential loops are additive"));
    }

    @Test
    void nestedLoopsOverSameCollectionAreQuadratic(@TempDir Path dir) throws IOException {
        JsonNode a = analyze(dir, """
                int f(List<Integer> items) {
                    int s = 0;
                    for (int x : items) { for (int y : items) { s += x * y; } }
                    return s;
                }""");
        assertEquals("O(n^2)", time(a));
        assertTrue(a.at("/complexity/reason").asText().contains("2 nested loops over items"));
        JsonNode f = finding(a, "nested_collection_iteration");
        assertNotNull(f);
        assertEquals("info", f.get("severity").asText());
        assertTrue(f.get("explanation").asText().contains("quadratically"));
    }

    @Test
    void threeNestedLinearLoopsAreCubic(@TempDir Path dir) throws IOException {
        JsonNode a = analyze(dir, """
                int f(int n) {
                    int s = 0;
                    for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) for (int k = 0; k < n; k++) s++;
                    return s;
                }""");
        assertEquals("O(n^3)", time(a));
        assertEquals("medium", a.at("/complexity/confidence").asText());
        assertTrue(a.at("/complexity/assumptions/0").asText().startsWith("n = value of n"));
    }

    @Test
    void fixedBoundNestedLoopsAreConstant(@TempDir Path dir) throws IOException {
        JsonNode a = analyze(dir, """
                int f() {
                    int s = 0;
                    for (int i = 0; i < 10; i++) for (int j = 0; j < 20; j++) s += i * j;
                    return s;
                }""");
        assertEquals("O(1)", time(a));
        assertEquals(0, a.get("findings").size());
    }

    @Test
    void logarithmicLoops(@TempDir Path dir) throws IOException {
        assertEquals("O(log n)", time(analyze(dir,
                "int f(int n) { int c = 0; for (int i = 1; i < n; i *= 2) c++; return c; }")));
        assertEquals("O(log n)", time(analyze(dir,
                "int f(int n) { int c = 0; while (n > 1) { n /= 2; c++; } return c; }")));
        assertEquals("O(n log n)", time(analyze(dir,
                "int f(int n) { int c = 0; for (int j = 0; j < n; j++) for (int i = n; i > 0; i >>= 1) c++; return c; }")));
    }

    @Test
    void unknownBoundsAreNotGuessed(@TempDir Path dir) throws IOException {
        JsonNode a = analyze(dir, "int f(Node x) { int c = 0; while (x != null) { x = x.next; c++; } return c; }\n"
                + "static class Node { Node next; }");
        assertEquals("unknown", time(a));
        assertEquals("low", a.at("/complexity/confidence").asText());
        assertTrue(a.at("/complexity/reason").asText().contains("not a counter compared with a bound"));

        // counter moving away from the bound, and a bound changed inside the loop
        assertEquals("unknown", time(analyze(dir, "void f(int n) { for (int i = 0; i < n; i--) { } }")));
        assertEquals("unknown", time(analyze(dir, "void f(int n) { for (int i = 0; i < n; i++) { n++; } }")));
        assertEquals("unknown", time(analyze(dir, "void f(List<Integer> l) { for (int i = 0; i < l.size(); i++) { l.add(i); } }")));
    }

    @Test
    void differentCollectionsDoNotBecomeNSquared(@TempDir Path dir) throws IOException {
        JsonNode a = analyze(dir, """
                int f(List<Integer> as, List<Integer> bs) {
                    int s = 0;
                    for (int x : as) { for (int y : bs) { s += x * y; } }
                    return s;
                }""");
        assertEquals("O(n*m)", time(a));
        assertEquals("n = number of elements in as", a.at("/complexity/assumptions/0").asText());
        assertEquals("m = number of elements in bs", a.at("/complexity/assumptions/1").asText());
        assertEquals(0, a.get("findings").size(), "a cross product is not reported without a lookup shape");
    }

    private static final String LOOKUP = """
            static class Order { int customerId; int id; }
            static class Customer { int id; String name; }
            int f(List<Order> orders, List<Customer> customers) {
                int found = 0;
                for (Order o : orders) {
                    for (Customer c : customers) {
                        if (c.id == o.customerId) { found++; break; }
                    }
                }
                return found;
            }""";

    private JsonNode analyzeLast(Path dir, String members, String method) throws IOException {
        JsonNode root = parse(dir, "import java.util.*;\npublic class Example {\n" + members + "\n}\n");
        for (JsonNode m : root.at("/types/0/members")) {
            if (method.equals(m.path("name").asText())) {
                return m.get("analysis");
            }
        }
        throw new AssertionError("no method " + method);
    }

    @Test
    void repeatedScanByIdSuggestsAMap(@TempDir Path dir) throws IOException {
        JsonNode a = analyzeLast(dir, LOOKUP, "f");
        assertEquals("O(n*m)", time(a));
        JsonNode f = finding(a, "repeated_collection_scan");
        assertNotNull(f, a.toString());
        assertEquals("warning", f.get("severity").asText());
        assertTrue(f.get("message").asText().contains("'customers'"));
        assertTrue(f.get("explanation").asText().contains("O(n)"));
        String rec = f.get("recommendation").asText();
        assertTrue(rec.contains("Build a Map keyed by the compared value"), rec);
        assertTrue(rec.contains("putIfAbsent keeps the first match"), rec);
        assertFalse(rec.contains("not recommended"));
    }

    @Test
    void allMatchesLoopSuggestsGrouping(@TempDir Path dir) throws IOException {
        JsonNode a = analyzeLast(dir, LOOKUP.replace(" found++; break; ", " found++; "), "f");
        String rec = finding(a, "repeated_collection_scan").get("recommendation").asText();
        assertTrue(rec.contains("Map<Key, List<Element>>"), rec);
    }

    @Test
    void mapIsNotRecommendedWhenUnsafe(@TempDir Path dir) throws IOException {
        // the scanned collection is modified in the outer loop
        JsonNode mod = analyzeLast(dir, LOOKUP.replace("found++; break;", "found++; customers.remove(c); break;"), "f");
        String rec = finding(mod, "repeated_collection_scan").get("recommendation").asText();
        assertTrue(rec.startsWith("A Map index is not recommended here"), rec);
        assertTrue(rec.contains("modified inside the enclosing loop"), rec);

        // reference identity (==) is not hash-key equality
        JsonNode ident = analyzeLast(dir, """
                int f(List<String> as, List<String> bs) {
                    int n = 0;
                    for (String a : as) { for (String b : bs) { if (a == b) { n++; break; } } }
                    return n;
                }""", "f");
        rec = finding(ident, "repeated_collection_scan").get("recommendation").asText();
        assertTrue(rec.contains("reference '==' compares identity"), rec);

        // floating point equality
        JsonNode fp = analyzeLast(dir, """
                int f(List<Double> as, double[] bs) {
                    int n = 0;
                    for (double a : bs) { for (double b : bs) { if (a == b) { n++; break; } } }
                    return n;
                }""", "f");
        assertTrue(finding(fp, "repeated_collection_scan").get("recommendation").asText().contains("floating-point"));

        // range predicate: not a lookup at all, so no scan finding and no Map advice
        JsonNode range = analyzeLast(dir, """
                int f(List<Integer> as, List<Integer> bs) {
                    int n = 0;
                    for (int a : as) { for (int b : bs) { if (b < a) { n++; } } }
                    return n;
                }""", "f");
        assertEquals(0, range.get("findings").size());
    }

    @Test
    void indexLoopWithAliasIsRecognisedAsLookup(@TempDir Path dir) throws IOException {
        JsonNode a = analyzeLast(dir, LOOKUP.replace("for (Customer c : customers) {",
                "for (int j = 0; j < customers.size(); j++) { Customer c = customers.get(j);"), "f");
        assertNotNull(finding(a, "repeated_collection_scan"), a.toString());
        assertEquals("O(n*m)", time(a));
    }

    private static final String SLIDE_LOOP = "void dedupe(List<Item> items) { for (Item a : items) { for (Item b : items) {"
            + " if (a.id() == b.id()) { merge(a, b); } } } }\n";

    @Test
    void slideExampleWithConstantUserMethodIsQuadratic(@TempDir Path dir) throws IOException {
        JsonNode a = analyze(dir, SLIDE_LOOP + "record Item(int id) { }\nvoid merge(Item a, Item b) { }");
        assertEquals("O(n^2)", time(a));
        assertEquals("high", a.at("/complexity/confidence").asText());
        assertEquals("n = number of elements in items", a.at("/complexity/assumptions/0").asText());
        assertTrue(a.at("/complexity/assumptions").toString().contains("Call to Example.merge() is O(1)"));
        assertEquals("O(n^2)", a.at("/fragments/0/time").asText());
        assertNotNull(finding(a, "repeated_collection_scan"), a.toString());
        // same shape with the real (nested-call) bodies from the case: field counter, helper chain
        JsonNode b = analyze(dir, SLIDE_LOOP + "record Item(int id) { }\nint merged;\n"
                + "void merge(Item a, Item b) { merged++; log(a); }\nvoid log(Item a) { merged += a.id(); }");
        assertEquals("O(n^2)", time(b));
    }

    @Test
    void userMethodCostIsOnlyTrustedWhenProvenConstant(@TempDir Path dir) throws IOException {
        // recursion, mutual recursion and an override stay unknown
        assertEquals("unknown", time(analyze(dir, "int f(List<Integer> l) { int s = 0; for (int x : l) { s += g(x); } return s; }\n"
                + "int g(int n) { return n < 2 ? n : g(n - 1) + g(n - 2); }")));
        assertEquals("unknown", time(analyze(dir, "void f(List<Integer> l) { for (int x : l) { a(x); } }\n"
                + "void a(int n) { b(n); }\nvoid b(int n) { a(n); }")));
        // a callee whose cost depends only on its parameters is priced in the argument sizes (medium confidence)
        JsonNode substituted = analyze(dir, "void f(List<Integer> l) { for (int x : l) { h(x); } }\n"
                + "void h(int n) { for (int i = 0; i < n; i++) { } }");
        assertEquals("O(n*m)", time(substituted));
        assertEquals("medium", substituted.at("/complexity/confidence").asText());
        assertTrue(substituted.at("/complexity/assumptions").toString().contains("argument sizes"));
        // ... but not when the cost depends on something the call site cannot name (a field)
        assertEquals("unknown", time(analyze(dir, "void f(List<Integer> l) { for (int x : l) { h(x); } }\n"
                + "int[] data = new int[3];\nvoid h(int n) { for (int i = 0; i < data.length * n; i++) { } }")));
        JsonNode over = analyze(dir, "void f(List<Integer> l, Example e) { for (int x : l) { e.h(x); } }\n"
                + "void h(int n) { }\nstatic class Sub extends Example { @Override void h(int n) { for (int i = 0; i < n; i++) { } } }");
        assertEquals("unknown", time(over));
        assertTrue(over.at("/complexity/reason").asText().contains("Call to Example.h() has no known cost model"));
        // a final or private method cannot be overridden, so the same shape is constant
        assertEquals("O(n)", time(analyze(dir, "void f(List<Integer> l) { for (int x : l) { h(x); } }\nprivate void h(int n) { }")));
    }

    @Test
    void userMethodThatMutatesStateTheLoopDependsOnStaysUnknown(@TempDir Path dir) throws IOException {
        // mutating an argument collection could change the caller's loop bound
        assertEquals("unknown", time(analyze(dir, "void f(List<Integer> l) { for (int i = 0; i < l.size(); i++) { grow(l); } }\n"
                + "void grow(List<Integer> l) { l.add(1); }")));
        // writing a field that appears in a loop header of the caller
        JsonNode field = analyze(dir, "void f() { for (int i = 0; i < limit; i++) { shrink(); } }\nint limit = 10;\nvoid shrink() { limit--; }");
        assertEquals("unknown", time(field));
        assertTrue(field.at("/complexity/reason").asText().contains("may modify 'limit'"), field.toString());
        // a field the caller's loops do not depend on may be written freely
        JsonNode m = analyze(dir, "void f(List<Integer> l) { for (int x : l) { bump(); } }\nint hits;\nvoid bump() { hits++; }");
        assertEquals("O(n)", time(m));
    }

    @Test
    void unknownCallCostIsNotAssumedConstant(@TempDir Path dir) throws IOException {
        JsonNode a = analyze(dir, "int f(List<Integer> items) { int s = 0; for (int x : items) { s += helper(x); } return s; }\n"
                + "int helper(int x) { return new java.util.Random().nextInt(x + 1); }");
        assertEquals("unknown", time(a));
        assertTrue(a.at("/complexity/reason").asText().contains("Call to Example.helper() has no known cost model"));
        // a trivial getter is O(1)
        JsonNode b = analyze(dir, "int f(List<Integer> items) { int s = 0; for (int x : items) { s += get(x); } return s; }\n"
                + "int get(int x) { return x; }");
        assertEquals("O(n)", time(b));
    }

    @Test
    void sourceRangesAndCfgNodeIdsMatch(@TempDir Path dir) throws IOException {
        String source = "import java.util.*;\npublic class Example {\n" + LOOKUP + "\n}\n";
        JsonNode root = parse(dir, source);
        JsonNode method = null;
        for (JsonNode m : root.at("/types/0/members")) {
            if ("f".equals(m.path("name").asText())) {
                method = m;
            }
        }
        JsonNode f = finding(method.get("analysis"), "repeated_collection_scan");
        JsonNode range = f.get("source_range");
        int start = range.get("start").asInt();
        assertTrue(source.startsWith("for (Customer c : customers)", start));
        assertEquals(range.get("line").asInt(), source.substring(0, start).split("\n", -1).length);
        assertTrue(range.get("end_line").asInt() > range.get("line").asInt());

        JsonNode ids = f.get("node_ids");
        assertEquals(2, ids.size());
        for (int i = 0; i < 2; i++) {
            JsonNode node = null;
            for (JsonNode n : method.at("/cfg/nodes")) {
                if (n.get("id").asText().equals(ids.get(i).asText())) {
                    node = n;
                }
            }
            assertNotNull(node, "node id must exist in the same method's CFG");
            assertEquals("foreach", node.get("statement_kind").asText());
        }
        assertEquals("Order o : orders", method.at("/cfg/nodes").findValuesAsText("text").stream()
                .filter(t -> t.startsWith("Order")).findFirst().orElseThrow());
        JsonNode outer = null;
        for (JsonNode n : method.at("/cfg/nodes")) {
            if (n.get("id").asText().equals(ids.get(0).asText())) {
                outer = n;
            }
        }
        assertEquals("Order o : orders", outer.get("text").asText());
        assertEquals("Customer c : customers", findText(method, ids.get(1).asText()));

        // fragments: one per outermost loop, same node as the CFG
        assertEquals(1, method.at("/analysis/fragments").size());
        assertEquals(ids.get(0).asText(), method.at("/analysis/fragments/0/node_ids/0").asText());
    }

    private static String findText(JsonNode method, String id) {
        for (JsonNode n : method.at("/cfg/nodes")) {
            if (n.get("id").asText().equals(id)) {
                return n.get("text").asText();
            }
        }
        return null;
    }

    @Test
    void cyrillicSourceOffsetsAreUtf16(@TempDir Path dir) throws IOException {
        String source = "import java.util.*;\npublic class Example {\n    String имя = \"Привет 😀\";\n"
                + "    int f(List<String> список, List<String> другой) {\n        int n = 0; // комментарий 😀\n"
                + "        for (String а : список) {\n            for (String б : другой) {\n"
                + "                if (а.equals(б)) { n++; break; }\n            }\n        }\n        return n;\n    }\n}\n";
        JsonNode root = parse(dir, source);
        JsonNode method = root.at("/types/0/members/1");
        JsonNode f = finding(method.get("analysis"), "repeated_collection_scan");
        assertNotNull(f);
        int start = f.at("/source_range/start").asInt();
        int length = f.at("/source_range/length").asInt();
        assertTrue(source.startsWith("for (String б : другой)", start));
        assertTrue(source.substring(start, start + length).endsWith("}"));
        assertEquals("O(n*m)", method.at("/analysis/complexity/time").asText());
        assertEquals("n = number of elements in список", method.at("/analysis/complexity/assumptions/0").asText());
        assertEquals(7, f.at("/source_range/line").asInt());
    }

    @Test
    void malformedJavaDoesNotCrash(@TempDir Path dir) throws IOException {
        JsonNode root = parse(dir, """
                import java.util.*;
                public class Example {
                    int f(List<Integer> items) {
                        for (int x : items {
                            while (
                        }
                        return undefined(;
                    }
                    int g(int n) { for (int i = 0; i < n; i++) { } return n; }
                }
                """);
        assertEquals("partial", root.get("status").asText());
        for (JsonNode m : root.at("/types/0/members")) {
            JsonNode analysis = m.get("analysis");
            assertTrue(analysis.isObject() || analysis.isNull());
        }
        assertEquals("O(n)", root.at("/types/0/members/1/analysis/complexity/time").asText());
    }

    @Test
    void existingFieldsAreUnchangedAndOnlyAnalysisIsAdded(@TempDir Path dir) throws IOException {
        JsonNode root = parse(dir, "import java.util.*;\npublic class Example {\n"
                + "    int f(List<Integer> items) { int s = 0; for (int x : items) { s += x; } return s; }\n"
                + "    abstract static class A { abstract void g(); }\n}\n");
        assertEquals(1, root.get("protocol_version").asInt());
        JsonNode m = root.at("/types/0/members/0");
        assertEquals("completed", root.get("status").asText());
        assertTrue(root.at("/semantic/bindings").asBoolean());
        assertEquals("java.util.List<java.lang.Integer>", m.at("/parameters/0/resolved_type/name").asText());
        assertTrue(m.at("/cfg/nodes").size() > 0 && m.at("/cfg/edges").size() > 0);
        assertEquals("foreach", m.at("/body/1/kind").asText());
        assertEquals("Example.f(List<Integer>)", root.at("/methods/0/id").asText());
        assertTrue(root.at("/types/0/members/1/members/0/analysis").isNull(), "abstract method has no analysis");
        // exact top-level key set of a method: previous keys + analysis
        var names = new java.util.ArrayList<String>();
        m.fieldNames().forEachRemaining(names::add);
        assertTrue(names.containsAll(java.util.List.of("kind", "id", "name", "parameters", "body", "cfg", "analysis")));
    }

    private static final String BOX = "static class Box { final String id; int qty; Box(String id, int qty) { this.id = id; this.qty = qty; } }\n";

    @Test
    void constructorThatOnlyStoresItsArgumentsIsConstant(@TempDir Path dir) throws IOException {
        JsonNode a = analyze(dir, "List<Box> copy(List<Box> items) { List<Box> out = new ArrayList<>(); for (Box b : items) { out.add(new Box(b.id, b.qty)); } return out; }\n" + BOX);
        assertEquals("O(n)", time(a), a.toString());
        assertTrue(a.toString().contains("only stores its arguments into fields"), a.toString());
    }

    @Test
    void constructorWithConstantValidationOrCallsIsAnalyzedButAnInitializerCallStaysUnknown(@TempDir Path dir) throws IOException {
        String validating = "static class Box { final String id; Box(String id) { if (id == null) { throw new IllegalArgumentException(); } this.id = id; } }\n";
        JsonNode checked = analyze(dir, "void f(List<String> l) { for (String s : l) { new Box(s); } }\n" + validating);
        assertEquals("O(n)", time(checked));
        assertTrue(checked.toString().contains("Constructor of Box was analyzed"), checked.toString());
        String calling = "static class Box { final String id; Box(String id) { this.id = id.trim(); } }\n";
        assertEquals("O(n)", time(analyze(dir, "void f(List<String> l) { for (String s : l) { new Box(s); } }\n" + calling)));
        String looping = "static class Box { int total; Box(List<String> parts) { for (String p : parts) { total += p.length(); } } }\n";
        assertEquals("unknown", time(analyze(dir, "void f(List<String> l) { for (String s : l) { new Box(l); } }\n" + looping)));
        String initializer = "static class Box { final long t = System.nanoTime(); Box(String id) { } }\n";
        assertEquals("unknown", time(analyze(dir, "void f(List<String> l) { for (String s : l) { new Box(s); } }\n" + initializer)));
        String subclass = "static class Base { Base() { System.out.println(); } } static class Box extends Base { Box(String id) { } }\n";
        assertEquals("unknown", time(analyze(dir, "void f(List<String> l) { for (String s : l) { new Box(s); } }\n" + subclass)));
    }

    @Test
    void stringConcatenationInALoopIsReportedAndNotClaimedWithHighConfidence(@TempDir Path dir) throws IOException {
        for (String body : new String[] {"s += x;", "s = s + x;"}) {
            JsonNode a = analyze(dir, "String join(List<String> l) { String s = \"\"; for (String x : l) { " + body + " } return s; }");
            assertEquals("O(n)", time(a));
            assertEquals("medium", a.at("/complexity/confidence").asText(), body);
            JsonNode f = finding(a, "string_concat_in_loop");
            assertNotNull(f, body + " " + a);
            assertEquals("warning", f.get("severity").asText());
            assertTrue(f.get("recommendation").asText().contains("StringBuilder"));
            assertFalse(f.get("node_ids").isEmpty());
        }
    }

    @Test
    void stringBuilderAndPerIterationStringsAreNotReported(@TempDir Path dir) throws IOException {
        JsonNode builder = analyze(dir, "String join(List<String> l) { StringBuilder s = new StringBuilder(); for (String x : l) { s.append(x); } return s.toString(); }");
        assertNull(finding(builder, "string_concat_in_loop"));
        JsonNode local = analyze(dir, "int f(List<String> l) { int n = 0; for (String x : l) { String s = \"a\"; s += x; n += s.length(); } return n; }");
        assertNull(finding(local, "string_concat_in_loop"));
    }

    @Test
    void euclidsAlgorithmIsLogarithmicInBothTextbookForms(@TempDir Path dir) throws IOException {
        assertEquals("O(log n)", time(analyze(dir, "static int g(int a, int b) { while (b != 0) { int t = a % b; a = b; b = t; } return a; }")));
        assertEquals("O(log n)", time(analyze(dir, "static int g(int a, int b) { while (b > 0) { int t = b; b = a % b; a = t; } return a; }")));
        JsonNode composed = analyze(dir, "static int g(int a, int b) { while (b != 0) { int t = a % b; a = b; b = t; } return a; }\n"
                + "static int s(List<Integer> l, int m) { int r = 0; for (int x : l) { r += g(x, m); } return r; }");
        assertEquals("O(log n)", time(composed));
    }

    @Test
    void loopsThatOnlyLookLikeEuclidStayUnknown(@TempDir Path dir) throws IOException {
        assertEquals("unknown", time(analyze(dir, "static int g(int a, int b) { while (b != 0) { int t = a % b; a = b + 1; b = t; } return a; }")));
        assertEquals("unknown", time(analyze(dir, "static int g(int a, int b) { while (b != 0) { int t = a % b; a = b; b = t + 1; } return a; }")));
        assertEquals("unknown", time(analyze(dir, "static int g(int a, int b) { while (b != 0) { int t = a / b; a = b; b = t; } return a; }")));
    }

    @Test
    void groupingIntoAMapWithAnEmptyCollectionFactoryIsConstantPerElement(@TempDir Path dir) throws IOException {
        JsonNode a = analyze(dir, "Map<String, List<String>> group(List<String> l) { Map<String, List<String>> m = new HashMap<>();"
                + " for (String x : l) { m.computeIfAbsent(x, k -> new ArrayList<>()).add(x); } return m; }");
        assertEquals("O(n)", time(a), a.toString());
        JsonNode custom = analyze(dir, "Map<String, Object> f(List<String> l) { Map<String, Object> m = new HashMap<>();"
                + " for (String x : l) { m.computeIfAbsent(x, k -> expensive(k)); } return m; }\nstatic Object expensive(String k) { return k; }");
        assertEquals("unknown", time(custom), "an arbitrary mapping function is not priced");
    }

    @Test
    void aCallThroughAnInterfaceIsConstantWhenEveryImplementationIs(@TempDir Path dir) throws IOException {
        String types = "interface Named { String name(); }\n"
                + "static class Person implements Named { final String n; Person(String n) { this.n = n; } public String name() { return n; } }\n"
                + "record Pet(String name) implements Named {}\n";
        JsonNode a = analyze(dir, "int f(List<Named> l) { int t = 0; for (Named x : l) { t += x.name().length(); } return t; }\n" + types);
        assertEquals("O(n)", time(a), a.toString());
        assertTrue(a.toString().contains("2 implementations"), a.toString());

        String slow = types.replace("public String name() { return n; }",
                "public String name() { String r = \"\"; for (char c : n.toCharArray()) { r = r + c; } return r; }");
        assertEquals("unknown", time(analyze(dir, "int f(List<Named> l) { int t = 0; for (Named x : l) { t += x.name().length(); } return t; }\n" + slow)));
    }
}
