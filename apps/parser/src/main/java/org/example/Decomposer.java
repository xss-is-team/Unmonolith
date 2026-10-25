package org.example;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;

/**
 * Splits a monolith into one file per responsibility: moves static member types of a top-level class into top-level
 * types of their own files (same package, same directory). It works on source text, so formatting and comments are
 * kept; JDT bindings decide whether a move is safe.
 *
 * <pre>
 *   discover  -> every static member type of every top-level class with its preconditions
 *   apply     -> writes the complete candidate tree to --output-dir (host rewritten, new files added)
 * </pre>
 *
 * A type is extractable only if nothing outside it touches its private members, it touches nothing of its enclosing
 * class that would need qualification, no other file refers to it, its name is free, and every sibling it depends on
 * is extracted too. The result is re-analysed and rejected if it introduces any new error.
 *
 * Request: {@code {"source_level","files":[...],"action":"discover"|"apply","classes":[ids],"preview":false}};
 * an empty {@code classes} list selects every extractable type.
 */
final class Decomposer {
    static final String RULE = "extract_static_member_types";
    private static final ObjectMapper JSON = new ObjectMapper();

    private Decomposer() {
    }

    // ---------- CLI ----------

    static int run(String[] args, PrintStream out, PrintStream err) {
        String root = null;
        String input = null;
        String outputDir = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = i + 1 < args.length ? args[++i] : null;
                case "--input" -> input = i + 1 < args.length ? args[++i] : null;
                case "--output-dir" -> outputDir = i + 1 < args.length ? args[++i] : null;
                default -> {
                    err.println("usage: decompose --root DIR --input request.json [--output-dir OUT]");
                    return 2;
                }
            }
        }
        if (root == null || input == null) {
            err.println("usage: decompose --root DIR --input request.json [--output-dir OUT]");
            return 2;
        }
        try {
            JsonNode request = JSON.readTree(Files.readAllBytes(Path.of(input)));
            String level = request.path("source_level").asText(ProjectAnalyzer.DEFAULT_SOURCE_LEVEL);
            List<String> files = new ArrayList<>();
            request.path("files").forEach(f -> files.add(f.asText()));
            List<String> classes = new ArrayList<>();
            request.path("classes").forEach(c -> classes.add(c.asText()));
            List<MethodGroups.Spec> groups = new ArrayList<>();
            request.path("groups").forEach(g -> {
                List<String> methods = new ArrayList<>();
                g.path("methods").forEach(m -> methods.add(m.asText()));
                groups.add(new MethodGroups.Spec(g.path("id").asText(null), g.path("name").asText(null), methods));
            });
            String action = request.path("action").asText("discover");
            Map<String, Object> result;
            if (action.equals("discover")) {
                result = discover(Path.of(root), files, level);
            } else if (action.equals("apply")) {
                if (outputDir == null) {
                    err.println("apply requires --output-dir");
                    return 2;
                }
                result = apply(Path.of(root), files, level, classes, groups, Path.of(outputDir));
            } else {
                return fail(out, "invalid_request", "Unknown action: " + action);
            }
            out.println(Json.pretty(result));
            return "completed".equals(result.get("status")) ? 0 : 1;
        } catch (IOException | RuntimeException | StackOverflowError e) {
            e.printStackTrace(err);
            return fail(out, "internal_error", "Decomposer failed: " + e);
        }
    }

    private static int fail(PrintStream out, String code, String message) {
        out.println(Json.pretty(failure(code, message)));
        return 1;
    }

    static Map<String, Object> failure(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "failed");
        result.put("error", error);
        return result;
    }

    // ---------- model ----------

    record Precondition(String id, boolean satisfied, String detail) {
    }

    record Edit(int start, int end, String replacement) {
    }

    private static final class Cand {
        String id;
        String name;
        AstJson json;
        TypeDeclaration host;
        AbstractTypeDeclaration type;
        ITypeBinding binding;
        String newFile;
        final List<Precondition> preconditions = new ArrayList<>();
        final Set<String> dependsOn = new TreeSet<>();
        final Set<String> violations = new LinkedHashSet<>();
        boolean extractable;

        int start() {
            return type.getStartPosition();
        }

        int end() {
            return type.getStartPosition() + type.getLength();
        }

        boolean contains(ASTNode n) {
            return n.getRoot() == type.getRoot() && n.getStartPosition() >= start() && n.getStartPosition() < end();
        }
    }

    private static final class Scan {
        Map<String, Object> failure;
        Map<String, Object> analysis;
        ProjectAnalyzer.Loaded loaded;
        final List<Cand> candidates = new ArrayList<>();
        final Map<String, Cand> byId = new LinkedHashMap<>();
    }

    // ---------- discovery ----------

    private static Map<String, Object> discover(Path root, List<String> files, String level) throws IOException {
        Scan scan = scan(root, files, level);
        if (scan.failure != null) {
            return scan.failure;
        }
        List<Object> list = new ArrayList<>();
        for (Cand c : scan.candidates) {
            list.add(describe(c, scan));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "completed");
        result.put("rule", RULE);
        result.put("candidates", list);
        result.put("method_groups", MethodGroups.discover(scan.loaded));
        return result;
    }

    private static Scan scan(Path root, List<String> files, String level) throws IOException {
        Scan s = new Scan();
        s.analysis = ProjectAnalyzer.analyze(root, files, level, loaded -> s.loaded = loaded);
        if ("failed".equals(s.analysis.get("status")) || s.loaded == null || !s.loaded.bindings()) {
            s.failure = "failed".equals(s.analysis.get("status")) ? s.analysis
                    : failure("analysis_unavailable", "Types could not be resolved, so moves cannot be checked.");
            return s;
        }
        for (AstJson json : s.loaded.jsons()) {
            for (Object o : json.unit().types()) {
                if (!(o instanceof TypeDeclaration host) || host.isInterface() || host.resolveBinding() == null) {
                    continue;
                }
                for (Object member : host.bodyDeclarations()) {
                    if (member instanceof AbstractTypeDeclaration t && !(t instanceof AnnotationTypeDeclaration)
                            && t.resolveBinding() != null) {
                        Cand c = new Cand();
                        c.json = json;
                        c.host = host;
                        c.type = t;
                        c.binding = t.resolveBinding();
                        c.name = t.getName().getIdentifier();
                        c.id = c.binding.getQualifiedName();
                        String file = json.file();
                        int slash = file.lastIndexOf('/');
                        c.newFile = (slash < 0 ? "" : file.substring(0, slash + 1)) + c.name + ".java";
                        s.candidates.add(c);
                        s.byId.put(c.id, c);
                    }
                }
            }
        }
        for (Cand c : s.candidates) {
            check(s, c);
        }
        // a type that needs a sibling that cannot move cannot move either
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Cand c : s.candidates) {
                if (!c.extractable) {
                    continue;
                }
                for (String dep : c.dependsOn) {
                    Cand d = s.byId.get(dep);
                    if (d == null || !d.extractable) {
                        c.extractable = false;
                        replace(c, "siblings_extractable", false, "It uses " + dep
                                + ", which cannot be extracted, and a nested type is no longer visible by its simple name after the move.");
                        changed = true;
                        break;
                    }
                }
            }
        }
        return s;
    }

    private static void replace(Cand c, String id, boolean ok, String detail) {
        c.preconditions.removeIf(p -> p.id().equals(id));
        c.preconditions.add(new Precondition(id, ok, detail));
    }

    private static boolean within(ITypeBinding declared, ITypeBinding container) {
        for (ITypeBinding t = declared; t != null; t = t.getDeclaringClass()) {
            if (t.getTypeDeclaration().isEqualTo(container.getTypeDeclaration())) {
                return true;
            }
        }
        return false;
    }

    /** The class that declares {@code b}, or the type itself when {@code b} is a member type; null for locals. */
    private static ITypeBinding owner(IBinding b) {
        if (b instanceof IVariableBinding v) {
            return v.getDeclaringClass();
        }
        if (b instanceof IMethodBinding m) {
            return m.getDeclaringClass();
        }
        if (b instanceof ITypeBinding t) {
            return t;
        }
        return null;
    }

    static boolean qualified(SimpleName n) {
        ASTNode p = n.getParent();
        return p instanceof QualifiedName q && q.getName() == n
                || p instanceof FieldAccess f && f.getName() == n
                || p instanceof MethodInvocation m && m.getName() == n && m.getExpression() != null;
    }

    private static void check(Scan scan, Cand c) {
        ITypeBinding hostBinding = c.host.resolveBinding();
        String file = c.json.file();

        // 1. the type itself can be a top-level type
        boolean privateType = Modifier.isPrivate(c.type.getModifiers());
        c.preconditions.add(new Precondition("not_private", !privateType,
                privateType ? "A private member type is only visible inside its class and cannot become a top-level type."
                        : "The type is visible to its package, so it can become a top-level type."));

        // 2. the name is free in the package and the file does not exist
        String pkg = c.json.unit().getPackage() == null ? "" : c.json.unit().getPackage().getName().getFullyQualifiedName();
        String clash = null;
        for (AstJson other : scan.loaded.jsons()) {
            String otherPkg = other.unit().getPackage() == null ? "" : other.unit().getPackage().getName().getFullyQualifiedName();
            for (Object o : other.unit().types()) {
                if (otherPkg.equals(pkg) && ((AbstractTypeDeclaration) o).getName().getIdentifier().equals(c.name)) {
                    clash = "a top-level type named " + c.name + " already exists in " + other.file();
                }
            }
            if (other.file().equals(c.newFile)) {
                clash = "the file " + c.newFile + " already exists";
            }
        }
        c.preconditions.add(new Precondition("name_free", clash == null,
                clash == null ? "No top-level type or file is called " + c.name + " in this package." : "Cannot create " + c.newFile + ": " + clash + "."));

        // 3-5. every name in every file, classified against this type
        Set<String> privateUse = new LinkedHashSet<>();
        Set<String> enclosingUse = new LinkedHashSet<>();
        Set<String> otherFiles = new LinkedHashSet<>();
        for (AstJson json : scan.loaded.jsons()) {
            boolean sameUnit = json.unit() == c.type.getRoot();
            json.unit().accept(new ASTVisitor() {
                @Override
                public boolean visit(ImportDeclaration n) {
                    return true;
                }

                @Override
                public boolean visit(ClassInstanceCreation n) {
                    IMethodBinding ctor = n.resolveConstructorBinding();
                    if (ctor != null && !c.contains(n) && Modifier.isPrivate(ctor.getModifiers())
                            && within(ctor.getDeclaringClass(), c.binding)) {
                        privateUse.add("the private constructor of " + c.name + " (" + json.file() + ")");
                    }
                    return true;
                }

                @Override
                public boolean visit(SimpleName n) {
                    if (n.isDeclaration()) {
                        return true;
                    }
                    IBinding b = n.resolveBinding();
                    ITypeBinding owner = owner(b);
                    if (b == null || owner == null) {
                        return true;
                    }
                    boolean inside = sameUnit && c.contains(n);
                    boolean targetsType = within(owner, c.binding);
                    if (!inside && targetsType) {
                        if (!sameUnit) {
                            otherFiles.add(json.file());
                        } else if (!(b instanceof ITypeBinding t && t.getTypeDeclaration().isEqualTo(c.binding.getTypeDeclaration()))
                                && Modifier.isPrivate(b.getModifiers())) {
                            privateUse.add("the private member '" + n.getIdentifier() + "' of " + c.name + " (line "
                                    + c.json.unit().getLineNumber(n.getStartPosition()) + ")");
                        }
                    } else if (inside && !targetsType) {
                        boolean inHost = within(owner, hostBinding);
                        if (!inHost) {
                            return true;
                        }
                        if (b instanceof ITypeBinding t) {
                            if (!t.getTypeDeclaration().isEqualTo(hostBinding.getTypeDeclaration()) && !t.isEqualTo(c.binding)) {
                                c.dependsOn.add(t.getTypeDeclaration().getQualifiedName());
                            }
                        } else if (!owner.getTypeDeclaration().isEqualTo(hostBinding.getTypeDeclaration())) {
                            // a member of a sibling type (reached through a variable or its simple type name)
                            if (Modifier.isPrivate(b.getModifiers())) {
                                enclosingUse.add("the private member '" + n.getIdentifier() + "' of " + owner.getName());
                            }
                        } else if (Modifier.isPrivate(b.getModifiers())) {
                            enclosingUse.add("the private member '" + n.getIdentifier() + "' of " + owner.getName());
                        } else if (!qualified(n) && !(b instanceof IVariableBinding v && v.isEnumConstant())) {
                            enclosingUse.add("'" + n.getIdentifier() + "' of " + owner.getName() + " by its simple name (line "
                                    + c.json.unit().getLineNumber(n.getStartPosition()) + ")");
                        }
                    }
                    return true;
                }
            });
        }
        c.preconditions.add(new Precondition("no_private_access_from_outside", privateUse.isEmpty(),
                privateUse.isEmpty() ? "Nothing outside " + c.name + " uses its private members."
                        : "Code outside " + c.name + " uses " + String.join("; ", privateUse) + ", which would not be visible after the move."));
        c.preconditions.add(new Precondition("self_contained", enclosingUse.isEmpty(),
                enclosingUse.isEmpty() ? c.name + " only uses its own members, types it will sit next to, and the enclosing class by qualified name."
                        : c.name + " uses " + String.join("; ", enclosingUse) + ", which stops resolving once it is no longer nested in " + c.host.getName() + "."));
        c.preconditions.add(new Precondition("referenced_only_in_this_file", otherFiles.isEmpty(),
                otherFiles.isEmpty() ? "No other file refers to " + c.name + "."
                        : "Other files refer to " + c.name + " (" + String.join(", ", otherFiles) + "); their references would need rewriting."));
        c.extractable = c.preconditions.stream().allMatch(Precondition::satisfied);
        if (!c.dependsOn.isEmpty()) {
            c.preconditions.add(new Precondition("siblings_extractable", true,
                    c.name + " uses " + String.join(", ", c.dependsOn) + "; those must be extracted together with it."));
        }
    }

    private static Map<String, Object> describe(Cand c, Scan scan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("name", c.name);
        m.put("kind", c.type instanceof TypeDeclaration t ? (t.isInterface() ? "interface" : "class")
                : c.type instanceof org.eclipse.jdt.core.dom.EnumDeclaration ? "enum" : "record");
        m.put("host", c.host.resolveBinding().getQualifiedName());
        m.put("file", c.json.file());
        m.put("new_file", c.newFile);
        m.put("source_range", c.json.range(c.type));
        m.put("lines", c.json.unit().getLineNumber(c.end() - 1) - c.json.unit().getLineNumber(c.start()) + 1);
        m.put("extractable", c.extractable);
        m.put("depends_on", new ArrayList<>(c.dependsOn));
        List<Object> pre = new ArrayList<>();
        for (Precondition p : c.preconditions) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.id());
            pm.put("status", p.satisfied() ? "satisfied" : "violated");
            pm.put("detail", p.detail());
            pre.add(pm);
        }
        m.put("preconditions", pre);
        m.put("not_extractable_reasons", c.preconditions.stream().filter(p -> !p.satisfied()).map(Precondition::detail).toList());
        return m;
    }

    // ---------- apply ----------

    private static Map<String, Object> apply(Path root, List<String> files, String level, List<String> requested,
            List<MethodGroups.Spec> groups, Path outputDir) throws IOException {
        Scan scan = scan(root, files, level);
        if (scan.failure != null) {
            return scan.failure;
        }
        if (!groups.isEmpty()) {
            if (!requested.isEmpty()) {
                return failure("invalid_request", "Select member types (classes) or method groups, not both in one step.");
            }
            return MethodGroups.apply(scan.loaded, scan.analysis, root, files, level, groups, outputDir);
        }
        Set<String> chosen = new LinkedHashSet<>();
        if (requested.isEmpty()) {
            scan.candidates.stream().filter(c -> c.extractable).forEach(c -> chosen.add(c.id));
            if (chosen.isEmpty()) {
                return failure("nothing_to_extract", "No static member type can be extracted from this project.");
            }
        } else {
            for (String id : requested) {
                Cand c = scan.byId.get(id);
                if (c == null) {
                    return failure("class_not_found", "No extractable member type with id " + id + " exists in this revision.");
                }
                if (!c.extractable) {
                    return failure("not_extractable", c.id + " cannot be extracted: "
                            + String.join(" ", c.preconditions.stream().filter(p -> !p.satisfied()).map(Precondition::detail).toList()));
                }
                chosen.add(id);
            }
            for (String id : new ArrayList<>(chosen)) {
                for (String dep : scan.byId.get(id).dependsOn) {
                    if (!chosen.contains(dep)) {
                        return failure("dependency_not_selected", id + " uses " + dep + ", which must be extracted together with it.");
                    }
                }
            }
        }
        List<Cand> selected = new ArrayList<>();
        chosen.forEach(id -> selected.add(scan.byId.get(id)));

        // group by host file; build edits against each original text
        Map<String, String> outputs = new LinkedHashMap<>();
        Map<String, String> added = new LinkedHashMap<>();
        Map<String, Object> hostLines = new LinkedHashMap<>();
        for (AstJson json : scan.loaded.jsons()) {
            List<Cand> here = selected.stream().filter(c -> c.json == json).toList();
            if (here.isEmpty()) {
                continue;
            }
            String source = json.sourceText();
            List<Edit> rewrites = qualifiedNameEdits(json, here);
            // new files first (they read the original text)
            for (Cand c : here) {
                added.put(c.newFile, newFileText(json, source, c, rewrites));
            }
            // then the host: remove the moved ranges, apply the name rewrites elsewhere
            List<Edit> all = new ArrayList<>();
            for (Cand c : here) {
                all.add(removal(source, c));
            }
            for (Edit r : rewrites) {
                if (here.stream().noneMatch(c -> r.start() >= c.start() && r.end() <= c.end())) {
                    all.add(r);
                }
            }
            String rewritten = applyEdits(source, all);
            outputs.put(json.file(), rewritten);
            hostLines.put(json.file(), Map.of("before", lineCount(source), "after", lineCount(rewritten)));
        }

        List<String> paths = new ArrayList<>(new TreeSet<>(files));
        for (String path : paths) {
            Path target = outputDir.resolve(path);
            Files.createDirectories(target.getParent());
            if (outputs.containsKey(path)) {
                Files.writeString(target, outputs.get(path), StandardCharsets.UTF_8);
            } else {
                Files.copy(root.resolve(path), target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (Map.Entry<String, String> e : added.entrySet()) {
            Path target = outputDir.resolve(e.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
            paths.add(e.getKey());
        }

        Map<String, Object> candidateAnalysis = ProjectAnalyzer.analyze(outputDir, paths, level);
        List<String> introduced = Optimizer.newErrors(scan.analysis, candidateAnalysis);
        if ("failed".equals(candidateAnalysis.get("status")) || !introduced.isEmpty()) {
            return failure("transformation_invalid", "The split project does not analyse cleanly: "
                    + (introduced.isEmpty() ? "analysis failed" : String.join("; ", introduced)));
        }

        List<Object> extracted = new ArrayList<>();
        for (Cand c : selected) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id);
            m.put("name", c.name);
            m.put("from", c.json.file());
            m.put("new_file", c.newFile);
            m.put("lines", lineCount(added.get(c.newFile)));
            extracted.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "completed");
        result.put("rule", RULE);
        result.put("extracted", extracted);
        result.put("changed_files", new ArrayList<>(outputs.keySet()));
        result.put("added_files", new ArrayList<>(added.keySet()));
        result.put("host_lines", hostLines);
        result.put("candidate_status", candidateAnalysis.get("status"));
        return result;
    }

    static int lineCount(String text) {
        return (int) text.chars().filter(ch -> ch == '\n').count() + (text.endsWith("\n") ? 0 : 1);
    }

    /** {@code Host.Moved} -> {@code Moved} wherever the qualifier is the host class itself. */
    private static List<Edit> qualifiedNameEdits(AstJson json, List<Cand> here) {
        List<Edit> edits = new ArrayList<>();
        json.unit().accept(new ASTVisitor() {
            @Override
            public boolean visit(QualifiedName q) {
                IBinding name = q.getName().resolveBinding();
                IBinding qualifier = q.getQualifier().resolveBinding();
                if (name instanceof ITypeBinding t && qualifier instanceof ITypeBinding qt
                        && here.stream().anyMatch(c -> c.binding.isEqualTo(t.getTypeDeclaration()) && c.host.resolveBinding().isEqualTo(qt.getTypeDeclaration()))) {
                    edits.add(new Edit(q.getStartPosition(), q.getStartPosition() + q.getLength(), q.getName().getIdentifier()));
                    return false;
                }
                return true;
            }
        });
        return edits;
    }

    /** The member's lines, including its indentation, any javadoc, and one blank line after it. */
    private static Edit removal(String source, Cand c) {
        return removal(source, c.start(), c.end());
    }

    static String packageOf(AstJson json) {
        return json.unit().getPackage() == null ? "" : json.unit().getPackage().getName().getFullyQualifiedName();
    }

    static Edit removal(String source, int start, int end) {
        int lineStart = start;
        while (lineStart > 0 && (source.charAt(lineStart - 1) == ' ' || source.charAt(lineStart - 1) == '\t')) {
            lineStart--;
        }
        if (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart = start; // something else precedes it on the line: remove only the declaration
        }
        int cursor = end;
        while (cursor < source.length() && (source.charAt(cursor) == ' ' || source.charAt(cursor) == '\t')) {
            cursor++;
        }
        if (cursor < source.length() && source.charAt(cursor) == '\r') {
            cursor++;
        }
        if (cursor < source.length() && source.charAt(cursor) == '\n') {
            end = cursor + 1;
            int next = end;
            while (next < source.length() && (source.charAt(next) == ' ' || source.charAt(next) == '\t' || source.charAt(next) == '\r')) {
                next++;
            }
            if (next < source.length() && source.charAt(next) == '\n') {
                end = next + 1; // one blank line that separated it from the next member
            }
        }
        return new Edit(lineStart, end, "");
    }

    private static String newFileText(AstJson json, String source, Cand c, List<Edit> rewrites) {
        List<Edit> inside = new ArrayList<>();
        for (Edit r : rewrites) {
            if (r.start() >= c.start() && r.end() <= c.end()) {
                inside.add(new Edit(r.start() - c.start(), r.end() - c.start(), r.replacement()));
            }
        }
        // drop `static` / `protected`: not allowed on a top-level type
        for (Object o : c.type.modifiers()) {
            if (o instanceof Modifier m && (m.isStatic() || m.isProtected())) {
                int s = m.getStartPosition() - c.start();
                int e = s + m.getLength();
                String text = source.substring(c.start(), c.end());
                while (e < text.length() && text.charAt(e) == ' ') {
                    e++;
                }
                inside.add(new Edit(s, e, ""));
            }
        }
        String text = applyEdits(source.substring(c.start(), c.end()), inside);

        // dedent by the indentation of the line the member started on
        int lineStart = c.start();
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        String indent = source.substring(lineStart, c.start());
        if (!indent.isBlank()) {
            indent = "";
        }
        StringBuilder body = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            body.append(i > 0 && !indent.isEmpty() && line.startsWith(indent) ? line.substring(indent.length()) : line);
            if (i < lines.length - 1) {
                body.append('\n');
            }
        }

        StringBuilder out = new StringBuilder();
        CompilationUnit unit = json.unit();
        if (unit.getPackage() != null) {
            out.append(source, unit.getPackage().getStartPosition(), unit.getPackage().getStartPosition() + unit.getPackage().getLength()).append("\n\n");
        }
        Set<String> usedPackages = new LinkedHashSet<>();
        c.type.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName n) {
                if (n.resolveBinding() instanceof ITypeBinding t && t.getPackage() != null) {
                    usedPackages.add(t.getPackage().getName());
                }
                return true;
            }
        });
        boolean any = false;
        for (Object o : unit.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            String name = imp.getName().getFullyQualifiedName();
            String simple = name.substring(name.lastIndexOf('.') + 1);
            boolean keep = imp.isOnDemand()
                    ? imp.isStatic() || usedPackages.contains(name)
                    : Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(body).find();
            if (keep) {
                out.append(source, imp.getStartPosition(), imp.getStartPosition() + imp.getLength()).append('\n');
                any = true;
            }
        }
        if (any) {
            out.append('\n');
        }
        out.append(body).append('\n');
        return out.toString();
    }

    static String applyEdits(String text, List<Edit> edits) {
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder sb = new StringBuilder(text);
        int lastStart = Integer.MAX_VALUE;
        for (Edit e : sorted) {
            if (e.end() > lastStart) {
                continue; // overlapping edits cannot both apply; the outer one wins
            }
            sb.replace(e.start(), e.end(), e.replacement());
            lastStart = e.start();
        }
        return sb.toString();
    }
}
