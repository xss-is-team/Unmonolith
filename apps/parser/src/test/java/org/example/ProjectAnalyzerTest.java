package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectAnalyzerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A small Java 8 project with cross-file calls, overloads, inheritance and one missing library. */
    private static Map<String, String> java8Project() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/com/acme/model/Entity.java", """
                package com.acme.model;
                public interface Entity { int id(); }
                """);
        files.put("src/main/java/com/acme/model/Customer.java", """
                package com.acme.model;
                public class Customer implements Entity {
                    private final int id;
                    private final String name;
                    public Customer(int id, String name) { this.id = id; this.name = name; }
                    @Override public int id() { return id; }
                    public String name() { return name; }
                }
                """);
        files.put("src/main/java/com/acme/service/Repo.java", """
                package com.acme.service;
                import com.acme.model.Customer;
                import java.util.ArrayList;
                import java.util.List;
                public class Repo {
                    private final List<Customer> items = new ArrayList<Customer>();
                    public void add(Customer c) { items.add(c); }
                    public Customer find(int id) {
                        for (Customer c : items) { if (c.id() == id) { return c; } }
                        return null;
                    }
                    public Customer find(String name) {
                        for (Customer c : items) { if (c.name().equals(name)) { return c; } }
                        return null;
                    }
                }
                """);
        files.put("src/main/java/com/acme/service/Service.java", """
                package com.acme.service;
                import com.acme.model.Customer;
                public class Service {
                    private final Repo repo = new Repo();
                    public Customer lookup(int id) { return repo.find(id); }
                    public Customer lookup(String name) { return repo.find(name); }
                    public void register(int id, String name) { repo.add(new Customer(id, name)); }
                    public Runnable task() { return () -> register(1, "x"); }
                }
                """);
        files.put("src/main/java/com/acme/Main.java", """
                package com.acme;
                import com.acme.service.Service;
                import org.apache.commons.lang3.StringUtils;
                public class Main {
                    public static void main(String[] args) {
                        Service s = new Service();
                        s.register(1, "ann");
                        System.out.println(s.lookup("ann") != null);
                    }
                }
                """);
        return files;
    }

    private static JsonNode analyze(Path root, Map<String, String> files, String level) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
        }
        return JSON.valueToTree(ProjectAnalyzer.analyze(root, new ArrayList<>(files.keySet()), level));
    }

    private static JsonNode nodeByMethodId(JsonNode graph, String methodId) {
        for (JsonNode n : graph.get("nodes")) {
            if (n.has("method_id") && methodId.equals(n.get("method_id").asText())) {
                return n;
            }
        }
        throw new AssertionError("no graph node for " + methodId);
    }

    private static boolean hasEdge(JsonNode graph, String from, String to, String kind) {
        for (JsonNode e : graph.get("edges")) {
            if (e.get("from").asText().equals(from) && e.get("to").asText().equals(to)
                    && e.get("kind").asText().equals(kind)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void java8ProjectExposesFilesPackagesRootsAndNavigableCfgs(@TempDir Path root) throws IOException {
        JsonNode result = analyze(root, java8Project(), "8");

        assertEquals("project", result.get("kind").asText());
        assertEquals("8", result.get("language").get("source_level").asText());
        assertEquals(5, result.get("files").size());
        assertEquals(1, result.get("source_roots").size());
        assertEquals("src/main/java", result.get("source_roots").get(0).get("path").asText());
        JsonNode customer = null;
        for (JsonNode f : result.get("files")) {
            if (f.get("path").asText().endsWith("model/Customer.java")) {
                customer = f;
            }
        }
        assertNotNull(customer);
        assertEquals("com.acme.model", customer.get("package").asText());

        // Method ids are package-qualified, unique and carry their file; every method with a body has a CFG.
        List<String> ids = new ArrayList<>();
        for (JsonNode m : result.get("methods")) {
            ids.add(m.get("id").asText());
            assertNotNull(m.get("source_range").get("file"));
        }
        assertEquals(ids.size(), ids.stream().distinct().count());
        assertTrue(ids.contains("com.acme.service.Repo.find(int)"));
        assertTrue(ids.contains("com.acme.service.Repo.find(String)"));
        assertTrue(result.get("stats").get("cfgs").asInt() >= 9);

        // CFG node ids are unique across files.
        List<String> nodeIds = new ArrayList<>();
        collectCfgNodeIds(result.get("files"), nodeIds);
        assertEquals(nodeIds.size(), nodeIds.stream().distinct().count());
    }

    private static void collectCfgNodeIds(JsonNode value, List<String> out) {
        if (value.isObject()) {
            if (value.has("cfg") && value.get("cfg").isObject()) {
                for (JsonNode n : value.get("cfg").get("nodes")) {
                    out.add(n.get("id").asText());
                }
            }
            value.forEach(child -> collectCfgNodeIds(child, out));
        } else if (value.isArray()) {
            value.forEach(child -> collectCfgNodeIds(child, out));
        }
    }

    @Test
    void crossFileCallGraphResolvesOverloadsAndInheritance(@TempDir Path root) throws IOException {
        JsonNode graph = analyze(root, java8Project(), "8").get("project_graph");

        assertEquals("project", graph.get("scope").asText());
        String lookupInt = nodeByMethodId(graph, "com.acme.service.Service.lookup(int)").get("id").asText();
        String lookupString = nodeByMethodId(graph, "com.acme.service.Service.lookup(String)").get("id").asText();
        String findInt = nodeByMethodId(graph, "com.acme.service.Repo.find(int)").get("id").asText();
        String findString = nodeByMethodId(graph, "com.acme.service.Repo.find(String)").get("id").asText();
        assertTrue(hasEdge(graph, lookupInt, findInt, "calls"));
        assertTrue(hasEdge(graph, lookupString, findString, "calls"));
        assertTrue(!hasEdge(graph, lookupInt, findString, "calls"), "overload must not be confused");

        // Main (file 5) -> Service.register / lookup(String), across packages.
        String main = nodeByMethodId(graph, "com.acme.Main.main(String[])").get("id").asText();
        String register = nodeByMethodId(graph, "com.acme.service.Service.register(int,String)").get("id").asText();
        assertTrue(hasEdge(graph, main, register, "calls"));
        assertTrue(hasEdge(graph, main, lookupString, "calls"));

        // Inheritance: Customer implements Entity (different file).
        String entity = null;
        String customerType = null;
        for (JsonNode n : graph.get("nodes")) {
            if (n.has("qualified_name") && "com.acme.model.Entity".equals(n.get("qualified_name").asText())) {
                entity = n.get("id").asText();
            }
            if (n.has("qualified_name") && "com.acme.model.Customer".equals(n.get("qualified_name").asText())) {
                customerType = n.get("id").asText();
                assertEquals("src/main/java/com/acme/model/Customer.java", n.get("file").asText());
            }
        }
        assertTrue(hasEdge(graph, customerType, entity, "implements"));
        // The lambda inside Service.task() still attributes its call to the enclosing method.
        String task = nodeByMethodId(graph, "com.acme.service.Service.task()").get("id").asText();
        assertTrue(hasEdge(graph, task, register, "calls"));
    }

    @Test
    void reportsUnresolvedExternalDependenciesWithoutFailing(@TempDir Path root) throws IOException {
        JsonNode result = analyze(root, java8Project(), "8");

        assertEquals("partial", result.get("status").asText());
        assertEquals(1, result.get("external_dependencies").size());
        JsonNode dependency = result.get("external_dependencies").get(0);
        assertEquals("org.apache.commons.lang3.StringUtils", dependency.get("name").asText());
        assertEquals("src/main/java/com/acme/Main.java", dependency.get("files").get(0).asText());
        // The rest of the project is still analysed.
        assertTrue(result.get("methods").size() >= 9);
        boolean ranged = false;
        for (JsonNode d : result.get("diagnostics")) {
            if (d.get("code").asText().equals("unresolved_external_dependency")) {
                ranged = d.get("source_range").get("file").asText().endsWith("Main.java");
            }
        }
        assertTrue(ranged);
    }

    @Test
    void rejectsUnsupportedSourceLevelsExplicitly(@TempDir Path root) throws IOException {
        for (String level : new String[] { "1.4", "1.2", "7", "99" }) {
            JsonNode result = analyze(root, java8Project(), level);
            assertEquals("failed", result.get("status").asText(), level);
            assertEquals("unsupported_source_level", result.get("diagnostics").get(0).get("code").asText());
        }
    }

    @Test
    void sourceLevelControlsSyntaxAcceptance(@TempDir Path root) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("A.java", "public class A { int f() { var x = 1; return x; } }\n");
        assertEquals("partial", analyze(root, files, "8").get("status").asText());
        assertEquals("completed", analyze(root.resolve("v17"), files, "17").get("status").asText());
    }

    @Test
    void legacyCodeWithMismatchedPackageStillYieldsResults(@TempDir Path root) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("misc/A.java", "package legacy.a; public class A { public int one() { return B.two(); } }\n");
        files.put("misc/B.java", "package legacy.a; class B { static int two() { return 2; } }\n");
        JsonNode result = analyze(root, files, "8");

        boolean warned = false;
        for (JsonNode d : result.get("diagnostics")) {
            warned |= d.get("code").asText().equals("package_directory_mismatch");
        }
        assertTrue(warned);
        // Same-directory source root still resolves A -> B.
        JsonNode graph = result.get("project_graph");
        String one = nodeByMethodId(graph, "legacy.a.A.one()").get("id").asText();
        String two = nodeByMethodId(graph, "legacy.a.B.two()").get("id").asText();
        assertTrue(hasEdge(graph, one, two, "calls"));
    }

    @Test
    void syntaxErrorInOneFileKeepsOtherFilesAnalysed(@TempDir Path root) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("A.java", "public class A { int ok() { return 1; } }\n");
        files.put("B.java", "public class B { int bad( { return ; } \n");
        JsonNode result = analyze(root, files, "8");
        assertEquals("partial", result.get("status").asText());
        boolean okMethod = false;
        for (JsonNode m : result.get("methods")) {
            okMethod |= m.get("id").asText().equals("A.ok()");
        }
        assertTrue(okMethod);
    }

    @Test
    void cliProjectSubcommandEmitsOneJsonDocument(@TempDir Path root) throws IOException {
        for (Map.Entry<String, String> file : java8Project().entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        Path request = root.resolve("request.json");
        Files.writeString(request, JSON.writeValueAsString(Map.of("source_level", "17", "files", java8Project().keySet())));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code = Main.run(new String[] { "project", "--root", root.toString(), "--input", request.toString() },
                new java.io.PrintStream(out, true, StandardCharsets.UTF_8), new java.io.PrintStream(err));
        assertEquals(0, code);
        JsonNode result = JSON.readTree(out.toByteArray());
        assertEquals("17", result.get("language").get("source_level").asText());
        assertEquals(0, err.size());
    }
}
