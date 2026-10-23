package org.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * Usage: {@code java -jar parser.jar [file.java]} — prints the AST as JSON to stdout.
 * Without arguments a built-in sample is parsed.
 */
public class Main {
    private static final String SAMPLE = """
            public class Main {
                static void main() {
                    int a = 10;
                    int b = 10;
                    if (a == b) {
                        System.out.println("ok!");
                    } else if (a > b) {
                        System.out.println("gt");
                    } else {
                        for (int i = 0; i < a; i++) {
                            while (b > 0) { b--; }
                        }
                    }
                    try {
                        switch (a) {
                            case 1 -> System.out.println("one");
                            default -> throw new IllegalStateException();
                        }
                    } catch (IllegalStateException | IllegalArgumentException e) {
                        return;
                    } finally {
                        a = 0;
                    }
                }
            }
            """;

    public static void main(String[] args) throws IOException {
        String source = args.length > 0
                ? Files.readString(Path.of(args[0]), StandardCharsets.UTF_8)
                : SAMPLE;

        System.out.println(Json.pretty(parse(source)));
    }

    static Map<String, Object> parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setResolveBindings(false);

        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);

        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        return new AstJson(cu, source).convert();
    }
}
