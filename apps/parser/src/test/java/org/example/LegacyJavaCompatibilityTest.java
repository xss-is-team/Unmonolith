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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real JDT analysis of language features per source level: syntax newer than the requested level must be
 * reported as an error, never silently accepted; levels JDT cannot honour are refused.
 */
class LegacyJavaCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String LEGACY = """
            package legacy;
            import java.util.*;
            public class X {
                interface Shape { double area(); }
                private final List<Shape> shapes = new ArrayList<Shape>();
                public double total() {
                    double sum = 0;
                    for (Shape s : shapes) { sum += s.area(); }
                    return sum;
                }
                public Comparator<String> byLength() {
                    return new Comparator<String>() {
                        @Override public int compare(String a, String b) { return a.length() - b.length(); }
                    };
                }
                public Runnable task() { return () -> System.out.println("lambda"); }
            }
            """;

    private static String status(Path dir, String source, String level) throws IOException {
        Path file = dir.resolve("src/X.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        Map<String, Object> result = ProjectAnalyzer.analyze(dir, List.of("src/X.java"), level);
        return (String) result.get("status");
    }

    @ParameterizedTest
    @ValueSource(strings = { "8", "11", "17", "21" })
    void java8SyntaxIsAcceptedAtEverySupportedLevel(String level, @TempDir Path dir) throws IOException {
        assertEquals("completed", status(dir, LEGACY, level));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "public class X { int f() { var v = 1; return v; } }|8|partial",
            "public class X { int f() { var v = 1; return v; } }|11|completed",
            "public record X(int a) { }|8|partial",
            "public record X(int a) { }|11|partial",
            "public record X(int a) { }|17|completed",
            "public class X { int f(int d) { return switch (d) { case 1 -> 2; default -> 0; }; } }|11|partial",
            "public class X { int f(int d) { return switch (d) { case 1 -> 2; default -> 0; }; } }|17|completed",
            "public class X { Object o = 1; boolean f() { return o instanceof Integer i && i > 0; } }|11|partial",
            "public class X { Object o = 1; boolean f() { return o instanceof Integer i && i > 0; } }|17|completed",
    })
    void newerSyntaxIsRejectedBelowItsLevel(String source, String level, String expected, @TempDir Path dir) throws IOException {
        assertEquals(expected, status(dir, source, level), level + ": " + source);
    }

    @ParameterizedTest
    @ValueSource(strings = { "1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "1.7", "5", "6", "7", "9", "99", "", "latest" })
    void levelsTheBundledJdtCannotHonourAreRefused(String level, @TempDir Path dir) throws IOException {
        Path file = dir.resolve("src/X.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, LEGACY);
        Map<String, Object> result = ProjectAnalyzer.analyze(dir, List.of("src/X.java"), level);
        assertEquals("failed", result.get("status"));
        JsonNode diagnostic = JSON.valueToTree(result).get("diagnostics").get(0);
        assertEquals("unsupported_source_level", diagnostic.get("code").asText());
        assertTrue(diagnostic.get("message").asText().contains("Java 8"), diagnostic.toString());
    }

    @org.junit.jupiter.api.Test
    void javaRuntimeVersionIsReportedSeparatelyFromTheSourceLevel(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("src/X.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, LEGACY);
        JsonNode result = JSON.valueToTree(ProjectAnalyzer.analyze(dir, List.of("src/X.java"), "8"));
        JsonNode language = result.get("language");
        assertEquals("8", language.get("source_level").asText());
        assertEquals(System.getProperty("java.specification.version"), language.get("runtime_java").asText());
        assertEquals("running_jvm_class_library", language.get("api_resolution").asText());
    }
}
