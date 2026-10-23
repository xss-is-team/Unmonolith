package org.example;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * Usage: {@code java -jar parser.jar <file.java>}.
 * Exactly one JSON document goes to stdout; human-readable errors go to stderr.
 * Exit codes: 0 parsed (possibly with diagnostics), 1 input/internal failure, 2 usage error.
 */
public class Main {
    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.exit(run(args, out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.println("usage: java -jar parser.jar <file.java>");
            return 2;
        }

        String source;
        try {
            source = new String(Files.readAllBytes(Path.of(args[0])), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            err.println("cannot read " + args[0] + ": " + e);
            out.println(Json.pretty(failed("input_unreadable", "Cannot read input file")));
            return 1;
        }

        try {
            out.println(Json.pretty(parse(source, Path.of(args[0]).getFileName().toString())));
            return 0;
        } catch (RuntimeException | StackOverflowError e) {
            e.printStackTrace(err);
            out.println(Json.pretty(failed("parser_internal_error", "Parser failed: " + e)));
            return 1;
        }
    }

    /**
     * Parses with bindings against the running JDK's classes (single file: no project classpath).
     * If binding resolution itself throws, falls back to a syntax-only parse.
     */
    static Map<String, Object> parse(String source, String unitName) {
        try {
            return new AstJson(createAst(source, unitName, true), source, true).convert();
        } catch (RuntimeException e) {
            return new AstJson(createAst(source, unitName, false), source, false).convert();
        }
    }

    private static CompilationUnit createAst(String source, String unitName, boolean bindings) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setUnitName(unitName);
        parser.setResolveBindings(bindings);
        parser.setBindingsRecovery(bindings);
        parser.setStatementsRecovery(true);
        if (bindings) {
            parser.setEnvironment(null, null, null, true);
        }

        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);

        return (CompilationUnit) parser.createAST(null);
    }

    private static Map<String, Object> failed(String code, String message) {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("code", code);
        diagnostic.put("severity", "error");
        diagnostic.put("message", message);
        diagnostic.put("source_range", Map.of("start", 0, "length", 0));

        List<Object> diagnostics = new ArrayList<>();
        diagnostics.add(diagnostic);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol_version", 1);
        result.put("status", "failed");
        result.put("methods", new ArrayList<>());
        result.put("diagnostics", diagnostics);
        return result;
    }
}
