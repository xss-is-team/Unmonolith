package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Maintainability findings of the case mockup: long method, deep nesting, many parameters, duplicated logic. */
class CodeSmellsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<String> findingIds(Path dir, String source, String method) throws IOException {
        Path file = Files.writeString(dir.resolve("Example.java"), source);
        var out = new ByteArrayOutputStream();
        Main.run(new String[] {file.toString()}, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        JsonNode root = JSON.readTree(out.toString(StandardCharsets.UTF_8));
        List<String> ids = new ArrayList<>();
        for (JsonNode member : root.at("/types/0/members")) {
            if (method.equals(member.path("name").asText())) {
                for (JsonNode f : member.at("/analysis/findings")) {
                    ids.add(f.get("id").asText() + ":" + f.get("category").asText());
                }
            }
        }
        return ids;
    }

    @Test
    void longDeepAndWideMethodsAreReportedAsMaintainability(@TempDir Path dir) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 62; i++) {
            body.append("    total += ").append(i).append(";\n");
        }
        String source = "class Example {\n  static int big(int a, int b, int c, int d, int e, int f) {\n    int total = 0;\n"
                + "    if (a > 0) { for (int i = 0; i < b; i++) { while (c > 0) { if (d > i) { total++; } c--; } } }\n"
                + body + "    return total;\n  }\n  static int small(int a) { return a + 1; }\n}\n";
        List<String> ids = findingIds(dir, source, "big");
        assertTrue(ids.contains("long_method:maintainability"), ids.toString());
        assertTrue(ids.contains("deep_nesting:maintainability"), ids.toString());
        assertTrue(ids.contains("too_many_parameters:maintainability"), ids.toString());
        assertEquals(List.of(), findingIds(dir, source, "small"));
    }

    @Test
    void statementsRepeatedInAnotherMethodAreDuplicatedLogic(@TempDir Path dir) throws IOException {
        String repeated = "    int base = price * quantity;\n    int discount = base > 1000 ? base / 10 : 0;\n"
                + "    int taxed = (base - discount) * 120 / 100;\n    System.out.println(\"total=\" + taxed + \" discount=\" + discount);\n";
        String source = "class Example {\n  static void first(int price, int quantity) {\n" + repeated + "  }\n"
                + "  static void second(int price, int quantity) {\n    System.out.println(\"second\");\n" + repeated + "  }\n"
                + "  static void other(int price) {\n    System.out.println(price);\n  }\n}\n";
        assertTrue(findingIds(dir, source, "first").contains("duplicate_code:maintainability"));
        assertEquals(List.of(), findingIds(dir, source, "other"));
    }
}
