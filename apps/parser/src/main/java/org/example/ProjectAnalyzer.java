package org.example;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;

/**
 * {@code java -jar parser.jar project --root DIR --input request.json}.
 *
 * Analyses every listed Java file together: all files are bound in one JDT environment (with the
 * discovered source roots as source path), so types, overloads and calls resolve across files.
 * The request is {@code {"source_level": "8", "files": ["src/main/java/a/A.java", ...]}} with
 * root-relative paths. Nothing is compiled or executed; imperfect code yields partial results.
 *
 * Source levels: JDT 3.47 clamps compliance to at least 1.8, so levels below 8 cannot be honoured
 * and are rejected rather than silently parsed as Java 8.
 *
 * stdout: exactly one JSON document. Exit: 0 analysed (maybe partial), 1 failed, 2 usage error.
 */
final class ProjectAnalyzer {
    static final List<String> SOURCE_LEVELS = List.of("8", "11", "17", "21");
    static final String DEFAULT_SOURCE_LEVEL = "21";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProjectAnalyzer() {
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String root = null;
        String input = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = i + 1 < args.length ? args[++i] : null;
                case "--input" -> input = i + 1 < args.length ? args[++i] : null;
                default -> {
                    err.println("usage: project --root DIR --input request.json");
                    return 2;
                }
            }
        }
        if (root == null || input == null) {
            err.println("usage: project --root DIR --input request.json");
            return 2;
        }
        try {
            JsonNode request = JSON.readTree(Files.readAllBytes(Path.of(input)));
            String level = request.path("source_level").asText(DEFAULT_SOURCE_LEVEL);
            List<String> files = new ArrayList<>();
            for (JsonNode file : request.path("files")) {
                files.add(file.asText());
            }
            Map<String, Object> result = analyze(Path.of(root), files, level);
            out.println(Json.pretty(result));
            return "failed".equals(result.get("status")) ? 1 : 0;
        } catch (IOException | RuntimeException | StackOverflowError e) {
            e.printStackTrace(err);
            out.println(Json.pretty(failure("parser_internal_error", "Project analysis failed: " + e)));
            return 1;
        }
    }

    /** The bound compilation units behind one analysis, for tools that rewrite the project (see {@link Optimizer}). */
    record Loaded(List<AstJson> jsons, Map<MethodDeclaration, ProjectGraph.Link> links, boolean bindings) {
    }

    static Map<String, Object> analyze(Path root, List<String> files, String level) throws IOException {
        return analyze(root, files, level, null);
    }

    static Map<String, Object> analyze(Path root, List<String> files, String level, java.util.function.Consumer<Loaded> observer)
            throws IOException {
        if (!SOURCE_LEVELS.contains(level)) {
            return failure("unsupported_source_level", "source level " + level + " is not supported; supported: "
                    + String.join(", ", SOURCE_LEVELS)
                    + " (the bundled Eclipse JDT cannot parse below Java 8)");
        }
        if (files.isEmpty()) {
            return failure("no_java_sources", "project has no Java files");
        }
        List<String> paths = new ArrayList<>(new TreeSet<>(files));
        Map<String, String> sources = new LinkedHashMap<>();
        for (String path : paths) {
            sources.put(path, Files.readString(root.resolve(path), StandardCharsets.UTF_8));
        }

        List<Object> diagnostics = new ArrayList<>();
        Map<String, String> packages = new LinkedHashMap<>();
        Map<String, String> sourceRootOf = discoverSourceRoots(sources, level, packages, diagnostics);
        Set<String> roots = new TreeSet<>(sourceRootOf.values());

        Map<String, CompilationUnit> units = bind(root, paths, roots, level, true);
        boolean bindings = units != null;
        if (!bindings) {
            units = bind(root, paths, roots, level, false);
        }

        List<AstJson> jsons = new ArrayList<>();
        List<Object> fileEntries = new ArrayList<>();
        List<Object> methods = new ArrayList<>();
        Map<MethodDeclaration, ProjectGraph.Link> links = new IdentityHashMap<>();
        int index = 0;
        int cfgs = 0;
        boolean anyError = false;
        for (String path : paths) {
            jsons.add(AstJson.forProjectFile(units.get(path), sources.get(path), bindings, path, index++));
        }
        for (AstJson json : jsons) {
            json.setPeers(jsons); // before any conversion, so callees in later files are visible
        }
        for (AstJson json : jsons) {
            String path = json.file();
            Map<String, Object> converted = json.convert();
            links.putAll(json.methodLinks());

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", path);
            entry.put("package", converted.get("package"));
            entry.put("source_root", sourceRootOf.get(path));
            entry.put("status", converted.get("status"));
            entry.put("semantic", converted.get("semantic"));
            entry.put("imports", converted.get("imports"));
            entry.put("types", converted.get("types"));
            fileEntries.add(entry);

            @SuppressWarnings("unchecked")
            List<Object> unitMethods = (List<Object>) converted.get("methods");
            methods.addAll(unitMethods);
            cfgs += (int) countCfgs(converted.get("types"));
            @SuppressWarnings("unchecked")
            List<Object> unitDiagnostics = (List<Object>) converted.get("diagnostics");
            diagnostics.addAll(unitDiagnostics);
            anyError |= "partial".equals(converted.get("status"));
        }

        if (observer != null) {
            observer.accept(new Loaded(jsons, links, bindings));
        }

        Map<String, Object> graph = null;
        try {
            graph = new ProjectGraph(jsons, links).build();
        } catch (RuntimeException | StackOverflowError e) {
            diagnostics.add(diagnostic("project_graph_failed", "analysis", "warning",
                    "Project graph could not be built: " + e, null));
        }

        List<Object> external = externalDependencies(jsons);
        for (Object dependency : external) {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) dependency;
            diagnostics.add(diagnostic("unresolved_external_dependency", "analysis", "info",
                    "Import " + d.get("name") + " cannot be resolved; no classpath is provided, so calls into it are reported as unresolved",
                    ((List<?>) d.get("files")).get(0).toString()));
        }

        Map<String, Object> language = new LinkedHashMap<>();
        language.put("source_level", level);
        language.put("supported_source_levels", SOURCE_LEVELS);
        language.put("runtime_java", System.getProperty("java.specification.version"));
        language.put("api_resolution", "running_jvm_class_library");
        language.put("note", "Syntax follows the source level; JDK API availability is not checked against it.");

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("files", paths.size());
        stats.put("methods", methods.size());
        stats.put("cfgs", cfgs);
        stats.put("source_roots", roots.size());
        stats.put("unresolved_imports", external.size());

        List<Object> rootEntries = new ArrayList<>();
        for (String r : roots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", r);
            m.put("files", sourceRootOf.values().stream().filter(r::equals).count());
            rootEntries.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol_version", 1);
        result.put("kind", "project");
        result.put("status", anyError ? "partial" : "completed");
        result.put("language", language);
        result.put("semantic", Map.of("bindings", bindings));
        result.put("source_roots", rootEntries);
        result.put("files", fileEntries);
        result.put("methods", methods);
        result.put("project_graph", graph);
        result.put("external_dependencies", external);
        result.put("diagnostics", diagnostics);
        result.put("stats", stats);
        return result;
    }

    /**
     * A source root is the file's directory minus its package directories. A file whose directory
     * does not end with its package is its own root and gets a {@code package_directory_mismatch}
     * warning (common in loosely organised legacy code).
     */
    private static Map<String, String> discoverSourceRoots(Map<String, String> sources, String level,
            Map<String, String> packages, List<Object> diagnostics) {
        Map<String, String> roots = new LinkedHashMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String path = source.getKey();
            String pkg = declaredPackage(source.getValue(), level);
            packages.put(path, pkg);
            int slash = path.lastIndexOf('/');
            String directory = slash < 0 ? "" : path.substring(0, slash);
            String packageDirectory = pkg.replace('.', '/');
            String sourceRoot;
            if (packageDirectory.isEmpty()) {
                sourceRoot = directory;
            } else if (directory.equals(packageDirectory)) {
                sourceRoot = "";
            } else if (directory.endsWith("/" + packageDirectory)) {
                sourceRoot = directory.substring(0, directory.length() - packageDirectory.length() - 1);
            } else {
                sourceRoot = directory;
                diagnostics.add(diagnostic("package_directory_mismatch", "analysis", "warning",
                        "Package '" + pkg + "' does not match directory '" + directory + "'; the directory is used as its own source root",
                        path));
            }
            roots.put(path, sourceRoot);
        }
        return roots;
    }

    private static String declaredPackage(String source, String level) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setCompilerOptions(options(level));
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        return cu.getPackage() == null ? "" : cu.getPackage().getName().getFullyQualifiedName();
    }

    private static Map<String, String> options(String level) {
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(level.equals("8") ? JavaCore.VERSION_1_8 : level, options);
        return options;
    }

    /** Binds all files in one environment. Returns null if binding resolution itself threw. */
    private static Map<String, CompilationUnit> bind(Path root, List<String> paths, Set<String> roots, String level,
            boolean bindings) {
        Map<String, CompilationUnit> result = new TreeMap<>();
        Map<String, String> byAbsolute = new LinkedHashMap<>();
        String[] absolute = new String[paths.size()];
        String[] encodings = new String[paths.size()];
        for (int i = 0; i < paths.size(); i++) {
            absolute[i] = root.resolve(paths.get(i)).toAbsolutePath().normalize().toString();
            encodings[i] = "UTF-8";
            byAbsolute.put(absolute[i], paths.get(i));
        }
        String[] sourcepath = roots.stream()
                .map(r -> root.resolve(r).toAbsolutePath().normalize().toString()).toArray(String[]::new);
        String[] sourcepathEncodings = new String[sourcepath.length];
        java.util.Arrays.fill(sourcepathEncodings, "UTF-8");

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(bindings);
        parser.setBindingsRecovery(bindings);
        parser.setStatementsRecovery(true);
        parser.setCompilerOptions(options(level));
        if (bindings) {
            parser.setEnvironment(null, sourcepath, sourcepathEncodings, true);
        }
        try {
            parser.createASTs(absolute, encodings, new String[0], new FileASTRequestor() {
                @Override
                public void acceptAST(String sourceFilePath, CompilationUnit ast) {
                    result.put(byAbsolute.get(sourceFilePath), ast);
                }
            }, null);
        } catch (RuntimeException e) {
            if (bindings) {
                return null;
            }
            throw e;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static long countCfgs(Object value) {
        if (value instanceof Map<?, ?> map) {
            long count = map.get("cfg") instanceof Map ? 1 : 0;
            for (Object child : map.values()) {
                count += countCfgs(child);
            }
            return count;
        }
        if (value instanceof List<?> list) {
            long count = 0;
            for (Object child : list) {
                count += countCfgs(child);
            }
            return count;
        }
        return 0;
    }

    /** Imports that resolve to nothing: no project file and no JDK class declares them. */
    private static List<Object> externalDependencies(List<AstJson> jsons) {
        Map<String, Set<String>> unresolved = new TreeMap<>();
        for (AstJson json : jsons) {
            for (Object o : json.unit().imports()) {
                ImportDeclaration imp = (ImportDeclaration) o;
                org.eclipse.jdt.core.dom.IBinding binding = imp.resolveBinding();
                if (binding == null || binding.isRecovered()) {
                    unresolved.computeIfAbsent(imp.getName().getFullyQualifiedName() + (imp.isOnDemand() ? ".*" : ""),
                            k -> new LinkedHashSet<>()).add(json.file());
                }
            }
        }
        List<Object> list = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : unresolved.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("kind", "import");
            m.put("reason", "unresolved");
            m.put("files", new ArrayList<>(e.getValue()));
            list.add(m);
        }
        return list;
    }

    private static Map<String, Object> diagnostic(String code, String category, String severity, String message,
            String file) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", code);
        d.put("category", category);
        d.put("severity", severity);
        d.put("message", message);
        Map<String, Object> range = new LinkedHashMap<>();
        if (file != null) {
            range.put("file", file);
        }
        range.put("start", 0);
        range.put("length", 0);
        range.put("line", 1);
        d.put("source_range", range);
        return d;
    }

    private static Map<String, Object> failure(String code, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol_version", 1);
        result.put("kind", "project");
        result.put("status", "failed");
        result.put("methods", new ArrayList<>());
        List<Object> diagnostics = new ArrayList<>();
        diagnostics.add(diagnostic(code, "analysis", "error", message, null));
        result.put("diagnostics", diagnostics);
        return result;
    }
}
