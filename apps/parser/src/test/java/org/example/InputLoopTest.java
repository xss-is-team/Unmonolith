package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** Loops driven by standard input are bounded by the input size instead of being unknown. */
class InputLoopTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String time(Path dir, String body) throws IOException {
        Path file = Files.writeString(dir.resolve("Example.java"), "import java.util.*;\nimport java.io.*;\npublic class Example {\n"
                + "  static void run(Scanner in, BufferedReader reader) throws IOException {\n" + body + "\n  }\n}\n");
        var out = new ByteArrayOutputStream();
        Main.run(new String[] {file.toString()}, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        JsonNode root = JSON.readTree(out.toString(StandardCharsets.UTF_8));
        return root.at("/types/0/members/0/analysis/complexity/time").asText();
    }

    @Test
    void scannerAndReaderLoopsAreLinearInTheInput(@TempDir Path dir) throws IOException {
        assertEquals("O(n)", time(dir, "    while (in.hasNextLine()) { System.out.println(in.nextLine()); }"));
        assertEquals("O(n)", time(dir, "    String line; while ((line = reader.readLine()) != null) { System.out.println(line); }"));
        assertEquals("O(n)", time(dir, "    while (true) { String cmd = in.nextLine(); if (cmd.equals(\"exit\")) { break; } System.out.println(cmd); }"));
        assertEquals("O(n)", time(dir, "    boolean running = true;\n    while (running) { String cmd = in.next();\n"
                + "      switch (cmd) { case \"exit\" -> running = false; default -> System.out.println(cmd); } }"));
    }

    @Test
    void aLoopThatCanSkipTheReadStaysUnknown(@TempDir Path dir) throws IOException {
        String time = time(dir, "    int i = 0; while (true) { if (i++ % 2 == 0) { continue; } String cmd = in.nextLine(); if (cmd.isEmpty()) { break; } }");
        assertTrue(time.equals("unknown"), time);
    }
}
