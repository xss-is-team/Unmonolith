package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SemanticTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode analyze(String source) {
        return JSON.valueToTree(Main.parse(source, "Example.java"));
    }

    private static int count(JsonNode root, String severity) {
        int n = 0;
        for (JsonNode d : root.get("diagnostics")) {
            n += d.get("severity").asText().equals(severity) ? 1 : 0;
        }
        return n;
    }

    private static boolean hasDiagnostic(JsonNode root, String messagePart) {
        for (JsonNode d : root.get("diagnostics")) {
            if (d.get("message").asText().contains(messagePart)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void validClassHasMethodAndVariableBindings() {
        JsonNode root = analyze("""
                package demo;
                public class Example {
                    private int count;
                    String name(int a, double b) {
                        long total = a;
                        return "x";
                    }
                }
                """);

        assertEquals("completed", root.get("status").asText());
        assertTrue(root.at("/semantic/bindings").asBoolean());
        assertEquals(0, count(root, "error"));
        assertEquals("demo.Example", root.at("/types/0/qualified_name").asText());
        assertEquals("int", root.at("/types/0/members/0/fragments/0/resolved_type/name").asText());

        JsonNode method = root.at("/types/0/members/1");
        assertEquals("java.lang.String", method.at("/resolved_return_type/name").asText());
        assertEquals("int", method.at("/parameters/0/resolved_type/name").asText());
        assertEquals("double", method.at("/parameters/1/resolved_type/name").asText());
        assertEquals("long", method.at("/body/0/fragments/0/resolved_type/name").asText());
    }

    @Test
    void stringMethodInvocationIsResolved() {
        JsonNode root = analyze("""
                class Example {
                    int f(String s) {
                        int n = s.length();
                        return s.indexOf('a');
                    }
                }
                """);

        JsonNode call = root.at("/types/0/members/0/body/0/calls/0");
        assertEquals("method_call", call.get("kind").asText());
        assertTrue(call.get("resolved").asBoolean());
        assertEquals("length", call.get("name").asText());
        assertEquals("java.lang.String", call.get("declaring_class").asText());
        assertEquals("java.lang.String", call.at("/receiver_type/name").asText());
        assertEquals("length()", call.get("signature").asText());
        assertEquals("int", call.at("/return_type/name").asText());
        assertEquals("indexOf(int)", root.at("/types/0/members/0/body/1/calls/0/signature").asText());
    }

    @Test
    void collectionsAreResolved() {
        JsonNode root = analyze("""
                import java.util.*;
                class Example {
                    void f(String s) {
                        List<String> list = new ArrayList<>();
                        list.add(s);
                        Map<String, Integer> map = new HashMap<>();
                        map.put(s, 1);
                    }
                }
                """);

        JsonNode body = root.at("/types/0/members/0/body");
        assertEquals(0, count(root, "error"));
        assertEquals("java.util.List<java.lang.String>", body.at("/0/fragments/0/resolved_type/name").asText());
        assertEquals("interface", body.at("/0/fragments/0/resolved_type/kind").asText());
        assertEquals("java.util.ArrayList", body.at("/0/calls/0/declaring_class").asText());
        assertEquals("constructor_call", body.at("/0/calls/0/kind").asText());
        assertEquals("java.util.List", body.at("/1/calls/0/declaring_class").asText());
        assertEquals("java.util.List<java.lang.String>", body.at("/1/calls/0/receiver_type/name").asText());
        assertEquals("java.util.Map<java.lang.String,java.lang.Integer>",
                body.at("/2/fragments/0/resolved_type/name").asText());
        assertEquals("java.util.Map", body.at("/3/calls/0/declaring_class").asText());
        assertEquals("put", body.at("/3/calls/0/name").asText());
    }

    @Test
    void primitiveAndWrapperTypesAreDistinguished() {
        JsonNode root = analyze("""
                class Example {
                    void f() {
                        int a = 1;
                        Integer b = a;
                        boolean c = true;
                        Character d = 'x';
                        int[] e = new int[2];
                    }
                }
                """);

        JsonNode body = root.at("/types/0/members/0/body");
        assertEquals(0, count(root, "error"));
        assertEquals("primitive", body.at("/0/fragments/0/resolved_type/kind").asText());
        assertEquals("java.lang.Integer", body.at("/1/fragments/0/resolved_type/name").asText());
        assertEquals("class", body.at("/1/fragments/0/resolved_type/kind").asText());
        assertEquals("boolean", body.at("/2/fragments/0/resolved_type/name").asText());
        assertEquals("java.lang.Character", body.at("/3/fragments/0/resolved_type/name").asText());
        assertEquals("array", body.at("/4/fragments/0/resolved_type/kind").asText());
        assertEquals("int[]", body.at("/4/fragments/0/resolved_type/name").asText());
    }

    @Test
    void typeMismatchIsReported() {
        JsonNode root = analyze("""
                class Example {
                    void f() {
                        int x = "text";
                    }
                }
                """);

        JsonNode d = root.at("/diagnostics/0");
        assertEquals("partial", root.get("status").asText());
        assertEquals("error", d.get("severity").asText());
        assertEquals("semantic", d.get("category").asText());
        assertTrue(d.get("message").asText().contains("Type mismatch"));
        assertEquals(3, d.at("/source_range/line").asInt());
    }

    @Test
    void unresolvedMethodAndVariableAreReported() {
        JsonNode root = analyze("""
                class Example {
                    void f() {
                        undefinedCall();
                        int y = missingVar + 1;
                    }
                }
                """);

        assertTrue(hasDiagnostic(root, "undefinedCall() is undefined"));
        assertTrue(hasDiagnostic(root, "missingVar cannot be resolved to a variable"));
        JsonNode call = root.at("/types/0/members/0/body/0/calls/0");
        assertFalse(call.get("resolved").asBoolean());
        assertFalse(call.has("declaring_class"));
    }

    @Test
    void checkedExceptionsAreTracked() {
        JsonNode root = analyze("""
                import java.io.*;
                class Example {
                    void unhandled() { new FileReader("f"); }
                    void caught() {
                        try { new FileReader("f"); } catch (FileNotFoundException | RuntimeException e) { }
                    }
                    void declared() throws IOException { throw new IOException("x"); }
                }
                """);

        assertTrue(hasDiagnostic(root, "Unhandled exception type FileNotFoundException"));
        assertEquals(1, count(root, "error"));

        JsonNode members = root.at("/types/0/members");
        assertEquals("java.io.FileNotFoundException",
                members.at("/0/body/0/calls/0/thrown_exceptions/0/name").asText());
        JsonNode tryNode = members.at("/1/body/0");
        assertEquals("java.io.FileNotFoundException", tryNode.at("/catches/0/resolved_exception_types/0/name").asText());
        assertEquals("java.lang.RuntimeException", tryNode.at("/catches/0/resolved_exception_types/1/name").asText());
        assertEquals("java.io.IOException", members.at("/2/resolved_throws/0/name").asText());
        assertEquals("java.io.IOException", members.at("/2/body/0/resolved_type/name").asText());
    }

    @Test
    void malformedSourceKeepsPartialAstWithBindingsOn() {
        JsonNode root = analyze("""
                class Example {
                    void ok() { String s = "a"; int n = s.length(); }
                    void broken( {
                        if (
                    }
                }
                """);

        assertTrue(root.at("/semantic/bindings").asBoolean());
        assertEquals("partial", root.get("status").asText());
        assertEquals("syntax", root.at("/diagnostics/0/category").asText());
        assertEquals("length()", root.at("/types/0/members/0/body/1/calls/0/signature").asText());
    }

    @Test
    void unknownTypesDoNotCrash() {
        JsonNode root = analyze("""
                class Example {
                    void f() {
                        Mystery m = new Mystery();
                        m.go();
                    }
                }
                """);

        JsonNode body = root.at("/types/0/members/0/body");
        assertEquals("partial", root.get("status").asText());
        assertTrue(hasDiagnostic(root, "Mystery cannot be resolved to a type"));
        assertEquals("unresolved", body.at("/0/fragments/0/resolved_type/kind").asText());
        assertEquals("go", body.at("/1/calls/0/name").asText());
    }

    @Test
    void publicClassMustMatchPreservedFileName() {
        JsonNode root = JSON.valueToTree(Main.parse("package a.b;\npublic class Anything { }\n", "Stored.java"));

        assertEquals("partial", root.get("status").asText());
        assertTrue(hasDiagnostic(root, "must be defined in its own file"));
    }

    @Test
    void java22SyntaxIsRejectedAtJava21Compliance() {
        JsonNode root = analyze("""
                class Example {
                    void f() {
                        try { throw new Exception(); } catch (Exception _) { }
                    }
                }
                """);

        assertEquals("partial", root.get("status").asText());
        assertTrue(count(root, "error") > 0);
    }

    @Test
    void offsetsAreUtf16CodeUnitsWithCyrillicAndSurrogatePairs() {
        String source = """
                class Example {
                    void f() {
                        String привет = "мир 😀";
                        int n = привет.length();
                        int bad = "ошибка";
                    }
                }
                """;
        JsonNode root = analyze(source);

        JsonNode call = root.at("/types/0/members/0/body/1/calls/0");
        int start = call.at("/source_range/start").asInt();
        int length = call.at("/source_range/length").asInt();
        assertEquals("привет.length()", source.substring(start, start + length));
        assertEquals(source.indexOf("привет.length()"), start);
        assertEquals(4, call.at("/source_range/line").asInt());
        assertEquals("length()", call.get("signature").asText());

        JsonNode d = root.at("/diagnostics/0");
        int dStart = d.at("/source_range/start").asInt();
        assertEquals("\"ошибка\"", source.substring(dStart, dStart + d.at("/source_range/length").asInt()));
        assertEquals(5, d.at("/source_range/line").asInt());
    }
}
