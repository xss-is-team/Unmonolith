package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComplexityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void countsCyclomaticAndCognitiveComplexityByMethod(@TempDir Path dir) throws IOException {
        Path sourceFile = Files.writeString(dir.resolve("Example.java"), """
                class Example {
                    int analyze(boolean a, boolean b, boolean c) {
                        if (a && b || c) {
                            while (a) {
                                if (b) return 1;
                            }
                        } else if (b) return 2;
                        else return 3;
                        return 0;
                    }
                    int plain() { return 0; }
                }
                """);

        JsonNode root = JSON.valueToTree(Main.parse(Files.readString(sourceFile), "Example.java"));
        JsonNode metrics = method(root, "Example.analyze(boolean,boolean,boolean)").get("complexity");

        assertEquals(7, metrics.get("cyclomatic_complexity").asInt());
        assertEquals(10, metrics.get("cognitive_complexity").asInt());
        assertEquals(3, metrics.get("max_nesting_depth").asInt());
        assertTrue(metrics.get("hotspots").size() >= 6);
        assertEquals(1, method(root, "Example.plain()").at("/complexity/cyclomatic_complexity").asInt());
        assertEquals(0, method(root, "Example.plain()").at("/complexity/cognitive_complexity").asInt());
    }

    @Test
    void cognitiveComplexityMatchesSonarLogicalSequenceExample(@TempDir Path dir) throws IOException {
        Path sourceFile = Files.writeString(dir.resolve("Example.java"), """
                class Example {
                    boolean check(boolean a, boolean b, boolean c) {
                        if (a && b || c) return true;
                        return false;
                    }
                }
                """);

        JsonNode root = JSON.valueToTree(Main.parse(Files.readString(sourceFile), "Example.java"));
        JsonNode metrics = method(root, "Example.check(boolean,boolean,boolean)").get("complexity");

        assertEquals(4, metrics.get("cyclomatic_complexity").asInt());
        assertEquals(3, metrics.get("cognitive_complexity").asInt());
    }

    @Test
    void countsExtendedLogicalOperandsForCyclomaticButOneCognitiveSequence(@TempDir Path dir) throws IOException {
        Path sourceFile = Files.writeString(dir.resolve("Example.java"), """
                class Example {
                    boolean check(boolean a, boolean b, boolean c) {
                        if (a && b && c) return true;
                        return false;
                    }
                }
                """);

        JsonNode root = JSON.valueToTree(Main.parse(Files.readString(sourceFile), "Example.java"));
        JsonNode metrics = method(root, "Example.check(boolean,boolean,boolean)").get("complexity");

        assertEquals(4, metrics.get("cyclomatic_complexity").asInt());
        assertEquals(2, metrics.get("cognitive_complexity").asInt());
    }

    @Test
    void countsSwitchCatchTernaryAndLoopNesting(@TempDir Path dir) throws IOException {
        Path sourceFile = Files.writeString(dir.resolve("Example.java"), """
                class Example {
                    int analyze(int x, boolean a, boolean b) {
                        for (int i = 0; i < x; i++) {
                            try {
                                if (a ? b : !b) {
                                    switch (i) {
                                        case 1: x++; break;
                                        case 2: x--; break;
                                        default: break;
                                    }
                                } else {
                                    while (i > 0) i--;
                                }
                            } catch (IllegalArgumentException error) {
                                x++;
                            }
                        }
                        return x;
                    }
                    void labeled(int[] values) {
                        outer: for (int value : values) {
                            if (value < 0) continue outer;
                            break outer;
                        }
                    }
                }
                """);

        JsonNode root = JSON.valueToTree(Main.parse(Files.readString(sourceFile), "Example.java"));
        JsonNode control = method(root, "Example.analyze(int,boolean,boolean)").get("complexity");
        JsonNode labeled = method(root, "Example.labeled(int[])").get("complexity");

        assertEquals(8, control.get("cyclomatic_complexity").asInt());
        assertEquals(14, control.get("cognitive_complexity").asInt());
        assertEquals(3, control.get("max_nesting_depth").asInt());
        assertEquals(3, labeled.get("cyclomatic_complexity").asInt());
        assertEquals(5, labeled.get("cognitive_complexity").asInt());
        assertEquals(2, labeled.get("max_nesting_depth").asInt());
    }

    @Test
    void reportsJavaNcssReferenceValuesForCommonsMathPair() throws IOException {
        Path sourceFile = Path.of("..", "..", "legacy", "commons-math-3.6.1", "src", "main", "java",
                "org", "apache", "commons", "math3", "util", "Pair.java").normalize();
        assertTrue(Files.isRegularFile(sourceFile), "Commons Math Pair.java fixture must be present");

        JsonNode root = JSON.valueToTree(Main.parse(Files.readString(sourceFile), "Pair.java"));
        Map<String, Integer> reference = Map.of(
                "Pair.hashCode()", 3,
                "Pair.getKey()", 1,
                "Pair.getValue()", 1,
                "Pair.getFirst()", 1,
                "Pair.getSecond()", 1,
                "Pair.toString()", 1);

        reference.forEach((id, expected) -> assertEquals(expected,
                method(root, id).at("/complexity/cyclomatic_complexity").asInt(), id));

        // JavaNCSS 33.54 reports 8 for equals(Object), while the AST score is 6. The two extra
        // points match the '?' wildcard tokens in Pair<?, ?>; this is a recorded tool variance.
        assertEquals(6, method(root, "Pair.equals(Object)").at("/complexity/cyclomatic_complexity").asInt());
    }

    @Test
    void complexityDiagnosticsUseDocumentedThresholds(@TempDir Path dir) throws IOException {
        StringBuilder branches = new StringBuilder("class Example { int f(int x) {");
        for (int index = 0; index < 11; index++) branches.append("if (x == ").append(index).append(") x++;");
        branches.append("return x; } }");
        Path sourceFile = Files.writeString(dir.resolve("Example.java"), branches);

        JsonNode root = JSON.valueToTree(Main.parse(Files.readString(sourceFile), "Example.java"));
        List<String> codes = root.get("diagnostics").findValuesAsText("code");

        assertTrue(codes.contains("high_complexity"));

        StringBuilder veryComplex = new StringBuilder("class VeryComplex { int f(int x) {");
        for (int index = 0; index < 20; index++) veryComplex.append("if (x == ").append(index).append(") x++;");
        veryComplex.append("return x; } }");
        Path veryComplexFile = Files.writeString(dir.resolve("VeryComplex.java"), veryComplex);
        JsonNode veryComplexRoot = JSON.valueToTree(Main.parse(Files.readString(veryComplexFile), "VeryComplex.java"));
        assertEquals("very_high_complexity",
                veryComplexRoot.get("diagnostics").get(0).get("code").asText());
    }

    @Test
    void nestedTypesDoNotInflateEnclosingMethodMetrics(@TempDir Path dir) throws IOException {
        Path sourceFile = Files.writeString(dir.resolve("Example.java"), """
                class Example {
                    void enclosing() {
                        class Local { void nested() { if (true) { while (false) { } } } }
                        Object anonymous = new Object() {
                            void nested() { if (true) { while (false) { } } }
                        };
                    }
                }
                """);

        JsonNode root = JSON.valueToTree(Main.parse(Files.readString(sourceFile), "Example.java"));
        JsonNode metrics = method(root, "Example.enclosing()").get("complexity");

        assertEquals(1, metrics.get("cyclomatic_complexity").asInt());
        assertEquals(0, metrics.get("cognitive_complexity").asInt());
    }

    private static JsonNode method(JsonNode root, String id) {
        for (JsonNode method : root.get("methods")) {
            if (id.equals(method.get("id").asText())) return method;
        }
        throw new AssertionError("Method not found: " + id);
    }
}
