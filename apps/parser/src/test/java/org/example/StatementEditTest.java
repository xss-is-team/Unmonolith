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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** insert_statement and delete_statement: block edits on the CFG, validated by re-analysis. */
class StatementEditTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCE = "public class Main {\n  static int f(int a) {\n    int x = a + 1;\n    int y = x * 2;\n    return y;\n  }\n}\n";
    private static final String F = "Main.f(int)";

    private record Run(int code, JsonNode json) {
    }

    private static String nodeId(String source, String text) {
        JsonNode analysis = JSON.valueToTree(Main.parse(source, "Main.java"));
        for (JsonNode n : analysis.findValues("nodes").stream().flatMap(a -> {
            List<JsonNode> list = new ArrayList<>();
            a.forEach(list::add);
            return list.stream();
        }).toList()) {
            if (n.has("text") && n.get("text").asText().equals(text)) {
                return n.get("id").asText();
            }
        }
        throw new AssertionError("no CFG node '" + text + "'");
    }

    private static Run edit(Path root, Map<String, Object> operation, Path out) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("Main.java"), SOURCE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "single");
        body.put("source_level", "17");
        body.put("files", List.of("Main.java"));
        body.put("method_id", F);
        body.put("operation", operation);
        Path request = root.resolve("edit.json");
        Files.writeString(request, JSON.writeValueAsString(body));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"edit", "--root", root.toString(), "--input", request.toString(), "--output-dir", out.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        return new Run(code, JSON.readTree(stdout.toByteArray()));
    }

    private static Map<String, Object> insert(String target, String position, String code) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("type", "insert_statement");
        op.put("target_node_id", nodeId(SOURCE, target));
        op.put("position", position);
        op.put("code", code);
        return op;
    }

    private static Map<String, Object> delete(String node) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("type", "delete_statement");
        op.put("node_id", nodeId(SOURCE, node));
        return op;
    }

    @Test
    void insertsSeveralStatementsInOrderAndKeepsTheRestOfTheFileAsItWas(@TempDir Path dir) throws Exception {
        Run run = edit(dir.resolve("in"), insert("int y = x * 2;", "before", "int z = x + 10;\n    System.out.println(z);"), dir.resolve("out"));
        assertEquals(0, run.code, run.json.toString());
        String result = Files.readString(dir.resolve("out/Main.java"));
        assertTrue(result.indexOf("int x = a + 1;") < result.indexOf("int z = x + 10;")
                && result.indexOf("int z = x + 10;") < result.indexOf("System.out.println(z);")
                && result.indexOf("System.out.println(z);") < result.indexOf("int y = x * 2;"), result);
        assertEquals(2, run.json.get("operation").get("inserted").size());
        assertTrue(run.json.get("node_mapping").size() >= 4, "old CFG nodes are mapped onto the new graph");

        Run after = edit(dir.resolve("in2"), insert("int y = x * 2;", "after", "y += 1;"), dir.resolve("out2"));
        assertEquals(0, after.code, after.json.toString());
        String text = Files.readString(dir.resolve("out2/Main.java"));
        assertTrue(text.indexOf("int y = x * 2;") < text.indexOf("y += 1;") && text.indexOf("y += 1;") < text.indexOf("return y;"), text);
    }

    @Test
    void deletesAStatementAndRefusesWhatWouldNotCompile(@TempDir Path dir) throws Exception {
        Run run = edit(dir.resolve("in"), delete("int y = x * 2;"), dir.resolve("out"));
        assertEquals(1, run.code, "return y needs the deleted declaration: " + run.json);
        assertEquals("transformation_invalid", run.json.at("/error/code").asText());

        Run ok = edit(dir.resolve("in2"), delete("return y;"), dir.resolve("out2"));
        assertEquals(1, ok.code, "a method that must return a value cannot lose its return: " + ok.json);

        Run fine = edit(dir.resolve("in3"), delete("int x = a + 1;"), dir.resolve("out3"));
        assertEquals(1, fine.code, "y = x * 2 still needs x: " + fine.json);
    }

    @Test
    void rejectsBrokenOrEmptyCodeAndUnknownNodes(@TempDir Path dir) throws Exception {
        Run broken = edit(dir.resolve("in"), insert("int x = a + 1;", "after", "int = ;"), dir.resolve("out"));
        assertEquals("invalid_operation", broken.json.at("/error/code").asText());
        Run empty = edit(dir.resolve("in2"), insert("int x = a + 1;", "after", "   "), dir.resolve("out2"));
        assertEquals("invalid_operation", empty.json.at("/error/code").asText());
        Run typeError = edit(dir.resolve("in3"), insert("int x = a + 1;", "after", "String s = x;"), dir.resolve("out3"));
        assertEquals("transformation_invalid", typeError.json.at("/error/code").asText(), typeError.json.toString());
        Map<String, Object> bad = insert("int x = a + 1;", "after", "int q = 1;");
        bad.put("target_node_id", "nope");
        assertEquals("unsupported_node", edit(dir.resolve("in4"), bad, dir.resolve("out4")).json.at("/error/code").asText());
    }

    @Test
    void aDeletedStatementWithNoLaterUseCompilesAndDisappears(@TempDir Path dir) throws Exception {
        Run insertThenCheck = edit(dir.resolve("in"), insert("int x = a + 1;", "after", "int unused = 5;"), dir.resolve("out"));
        assertEquals(0, insertThenCheck.code, insertThenCheck.json.toString());
        String source = Files.readString(dir.resolve("out/Main.java"));
        // delete the statement that was just inserted (it has no later use)
        Path second = dir.resolve("second");
        Files.createDirectories(second);
        Files.writeString(second.resolve("Main.java"), source);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("type", "delete_statement");
        op.put("node_id", nodeId(source, "int unused = 5;"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "single");
        body.put("source_level", "17");
        body.put("files", List.of("Main.java"));
        body.put("method_id", F);
        body.put("operation", op);
        Files.writeString(second.resolve("edit.json"), JSON.writeValueAsString(body));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"edit", "--root", second.toString(), "--input", second.resolve("edit.json").toString(),
                "--output-dir", dir.resolve("out2").toString()}, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, code, stdout.toString(StandardCharsets.UTF_8));
        assertEquals(SOURCE, Files.readString(dir.resolve("out2/Main.java")), "insert then delete restores the original text");
    }

    @Test
    void replacesAStatementWithEditedCodeAndRefusesWhatBreaksTheMethod(@TempDir Path dir) throws Exception {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("type", "replace_statement");
        op.put("node_id", nodeId(SOURCE, "int x = a + 1;"));
        op.put("code", "int x = a + 100;");
        Run run = edit(dir.resolve("in"), op, dir.resolve("out"));
        assertEquals(0, run.code, run.json.toString());
        String result = Files.readString(dir.resolve("out/Main.java"));
        assertTrue(result.contains("int x = a + 100;") && !result.contains("int x = a + 1;"), result);
        assertEquals(1, run.json.get("operation").get("with").size());

        op.put("code", "int renamed = a;");
        Run broken = edit(dir.resolve("in2"), op, dir.resolve("out2"));
        assertEquals("transformation_invalid", broken.json.at("/error/code").asText(), "y = x * 2 still needs x: " + broken.json);
        op.put("code", "int = ;");
        assertEquals("invalid_operation", edit(dir.resolve("in3"), op, dir.resolve("out3")).json.at("/error/code").asText());
    }
}
