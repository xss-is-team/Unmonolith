package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProjectGraphTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode parse(String source) {
        return JSON.valueToTree(Main.parse(source, "Demo.java"));
    }

    private static JsonNode node(JsonNode graph, String methodId) {
        for (JsonNode n : graph.get("nodes")) {
            if (n.has("method_id") && methodId.equals(n.get("method_id").asText())) {
                return n;
            }
        }
        throw new AssertionError("no node for " + methodId);
    }

    private static JsonNode typeNode(JsonNode graph, String qualifiedName) {
        for (JsonNode n : graph.get("nodes")) {
            if (n.has("qualified_name") && qualifiedName.equals(n.get("qualified_name").asText())) {
                return n;
            }
        }
        throw new AssertionError("no type " + qualifiedName);
    }

    private static JsonNode edge(JsonNode graph, String from, String to, String kind) {
        for (JsonNode e : graph.get("edges")) {
            if (e.get("from").asText().equals(from) && e.get("to").asText().equals(to) && e.get("kind").asText().equals(kind)) {
                return e;
            }
        }
        return null;
    }

    @Test
    void recursionOverloadsConstructorsAndUnresolved() {
        String source = """
                package demo;
                import java.util.*;
                public class Demo {
                    interface Shape { double area(); }
                    static class Sq implements Shape, Missing {
                        public double area() { return 1; }
                    }
                    static class Base { Base(int x) {} }
                    static class Derived extends Base {
                        Derived() { super(1); }
                        Derived(String s) { this(); }
                    }
                    int fact(int n) { return n <= 1 ? 1 : n * fact(n - 1); }
                    int f(int a) { return a; }
                    int f(String a) { return f(a.length()); }
                    void go() {
                        new Sq().area();
                        new Derived("x");
                        new Plain();
                        Unknown.run();
                        new Ghost(1);
                        System.out.println(f(2));
                        List<Integer> l = new ArrayList<>();
                    }
                    static class Plain {}
                }
                """;
        JsonNode doc = parse(source);
        JsonNode g = doc.get("project_graph");
        assertEquals(1, g.get("version").asInt());

        JsonNode fact = node(g, "Demo.fact(int)");
        assertNotNull(edge(g, fact.get("id").asText(), fact.get("id").asText(), "calls"), "recursion self-edge");
        assertTrue(fact.get("cfg_entry").asText().endsWith(".n0"));

        JsonNode fInt = node(g, "Demo.f(int)");
        JsonNode fStr = node(g, "Demo.f(String)");
        assertNotNull(edge(g, fStr.get("id").asText(), fInt.get("id").asText(), "calls"), "overload resolved to f(int)");
        assertEquals(null, edge(g, fStr.get("id").asText(), fStr.get("id").asText(), "calls"));
        JsonNode go = node(g, "Demo.go()");
        assertEquals(1, edge(g, go.get("id").asText(), fInt.get("id").asText(), "calls").get("count").asInt());

        String goId = go.get("id").asText();
        assertNotNull(edge(g, goId, node(g, "Demo.Sq.area()").get("id").asText(), "calls"));
        assertNotNull(edge(g, goId, node(g, "Demo.Derived.Derived(String)").get("id").asText(), "constructs"));
        // this(...) and super(...) chains
        String dString = node(g, "Demo.Derived.Derived(String)").get("id").asText();
        String dEmpty = node(g, "Demo.Derived.Derived()").get("id").asText();
        assertNotNull(edge(g, dString, dEmpty, "constructs"));
        assertNotNull(edge(g, dEmpty, node(g, "Demo.Base.Base(int)").get("id").asText(), "constructs"));
        // implicit default constructor ends at the class
        assertNotNull(edge(g, goId, typeNode(g, "demo.Demo.Plain").get("id").asText(), "constructs"));

        // inheritance and ownership
        String sq = typeNode(g, "demo.Demo.Sq").get("id").asText();
        assertNotNull(edge(g, sq, typeNode(g, "demo.Demo.Shape").get("id").asText(), "implements"));
        assertNotNull(edge(g, typeNode(g, "demo.Demo.Derived").get("id").asText(), typeNode(g, "demo.Demo.Base").get("id").asText(), "extends"));
        assertNotNull(edge(g, typeNode(g, "demo.Demo").get("id").asText(), sq, "contains"));
        assertNotNull(edge(g, typeNode(g, "demo.Demo").get("id").asText(), typeNode(g, "demo.Demo.Plain").get("id").asText(), "depends_on"));
        JsonNode ext = typeNode(g, "demo.Demo.Sq").get("external_supertypes");
        assertEquals("Missing", ext.get(0).get("name").asText());
        assertEquals(false, ext.get(0).get("resolved").asBoolean());

        // unresolved calls are listed, never edges; JDK calls are external and not listed
        Set<String> unresolved = new HashSet<>();
        for (JsonNode u : g.get("unresolved_calls")) {
            assertEquals(goId, u.get("from").asText());
            unresolved.add(u.get("expression").asText());
        }
        assertEquals(Set.of("Unknown.run()", "new Ghost(1)"), unresolved);
        assertTrue(g.get("stats").get("external_calls").asInt() >= 2);
        for (JsonNode e : g.get("edges")) {
            assertTrue(e.get("to").asText().matches("[tc]\\d+"));
        }
        // every method graph node links to an existing methods[] entry
        Set<String> methodIds = new HashSet<>();
        for (JsonNode m : doc.get("methods")) {
            methodIds.add(m.get("id").asText());
        }
        for (JsonNode n : g.get("nodes")) {
            if (n.has("method_id")) {
                assertTrue(methodIds.contains(n.get("method_id").asText()));
            }
        }
    }

    @Test
    void organizerFixture() throws IOException {
        String source = Files.readString(Path.of("src/test/resources/acceptance/Organizer.java"), StandardCharsets.UTF_8);
        JsonNode doc = parse(source);
        JsonNode g = doc.get("project_graph");
        assertEquals(0, g.get("stats").get("unresolved_calls").asInt());

        // deterministic: the same source gives the same graph
        assertEquals(g, parse(source).get("project_graph"));

        // nesting, inheritance, implementation
        String organizer = typeNode(g, "Organizer").get("id").asText();
        String task = typeNode(g, "Organizer.Task").get("id").asText();
        String popup = typeNode(g, "Organizer.PopupReminder").get("id").asText();
        assertNotNull(edge(g, organizer, task, "contains"));
        assertNotNull(edge(g, task, typeNode(g, "Organizer.Identifiable").get("id").asText(), "implements"));
        assertNotNull(edge(g, typeNode(g, "Organizer.UrgentReminder").get("id").asText(), popup, "extends"));
        assertNotNull(edge(g, typeNode(g, "Organizer.ValidationException").get("id").asText(),
                typeNode(g, "Organizer.OrganizerException").get("id").asText(), "extends"));
        // JDK supertypes stay external
        assertEquals("java.lang.Exception", typeNode(g, "Organizer.OrganizerException").get("external_supertypes").get(0).get("name").asText());
        // local class method and inner class method are present and linked to the CFG
        assertTrue(node(g, "local.Best.offer(String)").has("cfg_entry"));
        assertEquals("Organizer.Counter.next()", node(g, "Organizer.Counter.next()").get("method_id").asText());
        // type dependencies between user classes
        assertNotNull(edge(g, organizer, task, "depends_on"));
        assertNotNull(edge(g, task, typeNode(g, "Organizer.Priority").get("id").asText(), "depends_on"));

        // every callable node is an existing method with an optional CFG, and ids are unique
        Set<String> ids = new HashSet<>();
        int callables = 0;
        for (JsonNode n : g.get("nodes")) {
            assertTrue(ids.add(n.get("id").asText()));
            if (n.has("method_id")) {
                callables++;
            }
        }
        assertEquals(doc.get("methods").size(), callables);
        for (JsonNode e : g.get("edges")) {
            assertTrue(ids.contains(e.get("from").asText()) && ids.contains(e.get("to").asText()));
        }
        // source ranges are UTF-16 offsets into the source
        for (JsonNode n : g.get("nodes")) {
            JsonNode r = n.get("source_range");
            assertTrue(r.get("start").asInt() + r.get("length").asInt() <= source.length());
        }
    }
}
