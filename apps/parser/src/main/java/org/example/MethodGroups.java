package org.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/**
 * Splits a class that is a pile of static methods: groups of related static methods move into a class of their own
 * (same package, own file). Works on source text like {@link Decomposer}; JDT bindings decide what is safe.
 *
 * <p>A group is a set of static methods of one top-level class. Private static helpers and constants used only by the
 * group move with it; calls into the group are re-pointed at the new class (also from other files); references from the
 * moved code to the remaining members of the original class are qualified with its name. The group is refused when it
 * needs a private member that other code also uses, an inherited member, a private nested type, or when a method is
 * referenced in a way that cannot be re-pointed (static import). Suggested groups are formed from the first word of the
 * method names.
 */
final class MethodGroups {
    static final String RULE = "extract_static_methods";

    private static final Set<String> GENERIC_STEMS = Set.of("get", "set", "is", "has", "to", "do", "run", "main", "test",
            "of", "new", "on", "as", "with", "put", "make", "handle");

    private MethodGroups() {
    }

    // ---------- model ----------

    private static final class Ref {
        final AstJson json;
        final SimpleName name;

        Ref(AstJson json, SimpleName name) {
            this.json = json;
            this.name = name;
        }
    }

    private static final class Member {
        final String key;
        final String id;
        final String name;
        final boolean method;
        final BodyDeclaration node;
        final AstJson json;
        final TypeDeclaration host;

        Member(String key, String id, String name, boolean method, BodyDeclaration node, AstJson json, TypeDeclaration host) {
            this.key = key;
            this.id = id;
            this.name = name;
            this.method = method;
            this.node = node;
            this.json = json;
            this.host = host;
        }
    }

    static final class Group {
        String id;
        String name;
        AstJson json;
        TypeDeclaration host;
        String newFile;
        final List<Member> requested = new ArrayList<>();
        final LinkedHashMap<String, Member> members = new LinkedHashMap<>();
        final List<String> violations = new ArrayList<>();
        final List<Decomposer.Precondition> preconditions = new ArrayList<>();
        boolean extractable;
    }

    private static final class Ctx {
        final ProjectAnalyzer.Loaded loaded;
        final Map<String, Member> all = new LinkedHashMap<>();
        final Map<String, List<Ref>> refs = new LinkedHashMap<>();
        final Map<TypeDeclaration, List<Member>> staticMethods = new LinkedHashMap<>();

        Ctx(ProjectAnalyzer.Loaded loaded) {
            this.loaded = loaded;
        }
    }

    /** A request group: {@code id} of a suggested group, or an explicit {@code methods} list; {@code name} optional. */
    record Spec(String id, String name, List<String> methods) {
    }

    // ---------- scanning ----------

    private static String key(IBinding b) {
        if (b instanceof IMethodBinding m) {
            return "M:" + m.getMethodDeclaration().getKey();
        }
        if (b instanceof IVariableBinding v && v.isField()) {
            return "F:" + v.getVariableDeclaration().getKey();
        }
        return null;
    }

    private static Ctx context(ProjectAnalyzer.Loaded loaded) {
        Ctx ctx = new Ctx(loaded);
        for (AstJson json : loaded.jsons()) {
            for (Object o : json.unit().types()) {
                if (!(o instanceof TypeDeclaration host) || host.isInterface() || host.resolveBinding() == null) {
                    continue;
                }
                for (MethodDeclaration md : host.getMethods()) {
                    ProjectGraph.Link link = loaded.links().get(md);
                    IMethodBinding mb = md.resolveBinding();
                    if (link == null || mb == null || md.isConstructor() || !Modifier.isStatic(md.getModifiers())) {
                        continue;
                    }
                    Member m = new Member(key(mb), link.methodId(), md.getName().getIdentifier(), true, md, json, host);
                    ctx.all.put(m.key, m);
                    if (!md.getName().getIdentifier().equals("main")) {
                        ctx.staticMethods.computeIfAbsent(host, h -> new ArrayList<>()).add(m);
                    }
                }
                for (FieldDeclaration fd : host.getFields()) {
                    if (!Modifier.isStatic(fd.getModifiers()) || fd.fragments().size() != 1) {
                        continue;
                    }
                    VariableDeclarationFragment frag = (VariableDeclarationFragment) fd.fragments().get(0);
                    IVariableBinding vb = frag.resolveBinding();
                    if (vb != null) {
                        Member m = new Member(key(vb), host.resolveBinding().getQualifiedName() + "." + frag.getName(),
                                frag.getName().getIdentifier(), false, fd, json, host);
                        ctx.all.put(m.key, m);
                    }
                }
            }
            json.unit().accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName n) {
                    if (!n.isDeclaration()) {
                        String k = key(n.resolveBinding());
                        if (k != null) {
                            ctx.refs.computeIfAbsent(k, x -> new ArrayList<>()).add(new Ref(json, n));
                        }
                    }
                    return true;
                }
            });
        }
        return ctx;
    }

    private static boolean inside(ASTNode n, Set<ASTNode> nodes) {
        for (ASTNode p = n; p != null; p = p.getParent()) {
            if (nodes.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaredIn(IBinding b, TypeDeclaration host) {
        ITypeBinding owner = b instanceof IMethodBinding m ? m.getDeclaringClass()
                : b instanceof IVariableBinding v ? v.getDeclaringClass() : null;
        return owner != null && owner.getTypeDeclaration().isEqualTo(host.resolveBinding().getTypeDeclaration());
    }

    // ---------- groups ----------

    private static String stem(String methodName) {
        int i = 1;
        while (i < methodName.length() && !Character.isUpperCase(methodName.charAt(i))) {
            i++;
        }
        return methodName.substring(0, i).toLowerCase();
    }

    private static String suggestedName(String stem) {
        return switch (stem) {
            case "parse", "tokenize", "scan", "read", "decode" -> "Parsing";
            case "validate", "check", "verify", "assert", "ensure" -> "Validation";
            case "format", "print", "render", "show", "display", "describe", "dump", "write", "encode" -> "Formatting";
            case "calc", "calculate", "compute", "sum", "total", "average", "count", "max", "min" -> "Calculations";
            case "find", "search", "lookup", "filter", "select", "match" -> "Search";
            case "sort", "order", "merge", "group", "reduce", "deduplicate" -> "Ordering";
            case "load", "save", "store", "export", "import", "persist" -> "Persistence";
            case "build", "create", "make", "generate", "init", "initialize" -> "Factory";
            case "process", "apply", "transform", "convert", "normalize", "map" -> "Processing";
            default -> Character.toUpperCase(stem.charAt(0)) + stem.substring(1) + "Support";
        };
    }

    private static List<Group> suggest(Ctx ctx) {
        List<Group> groups = new ArrayList<>();
        Set<String> names = new TreeSet<>();
        ctx.loaded.jsons().forEach(j -> j.unit().types().forEach(t -> names.add(((AbstractTypeDeclaration) t).getName().getIdentifier())));
        for (Map.Entry<TypeDeclaration, List<Member>> host : ctx.staticMethods.entrySet()) {
            Map<String, List<Member>> byStem = new LinkedHashMap<>();
            for (Member m : host.getValue()) {
                String s = stem(m.name);
                if (!GENERIC_STEMS.contains(s) && s.length() > 1) {
                    byStem.computeIfAbsent(s, x -> new ArrayList<>()).add(m);
                }
            }
            Map<String, List<Member>> byClass = new LinkedHashMap<>();
            for (Map.Entry<String, List<Member>> e : byStem.entrySet()) {
                byClass.computeIfAbsent(suggestedName(e.getKey()), x -> new ArrayList<>()).addAll(e.getValue());
            }
            for (Map.Entry<String, List<Member>> e : byClass.entrySet()) {
                if (e.getValue().size() < 2) {
                    continue;
                }
                Group g = newGroup(ctx, e.getValue().get(0), e.getKey(), e.getValue());
                String base = g.name;
                for (int i = 2; names.contains(g.name) || groups.stream().anyMatch(o -> o.name.equals(g.name) && o.host == g.host); i++) {
                    g.name = base + i;
                }
                g.newFile = fileOf(g.json, g.name);
                g.id = g.host.resolveBinding().getQualifiedName() + "#" + g.name;
                groups.add(g);
            }
        }
        for (Group g : groups) {
            plan(ctx, g);
        }
        return groups;
    }

    private static String fileOf(AstJson json, String name) {
        String file = json.file();
        int slash = file.lastIndexOf('/');
        return (slash < 0 ? "" : file.substring(0, slash + 1)) + name + ".java";
    }

    private static Group newGroup(Ctx ctx, Member first, String name, List<Member> requested) {
        Group g = new Group();
        g.host = first.host;
        g.json = first.json;
        g.name = name;
        g.id = first.host.resolveBinding().getQualifiedName() + "#" + name;
        g.newFile = fileOf(first.json, name);
        g.requested.addAll(requested);
        return g;
    }

    private static boolean freeName(Ctx ctx, Group g, List<String> violations) {
        if (!g.name.matches("[A-Za-z_][A-Za-z0-9_]*") || g.name.equals(g.host.getName().getIdentifier())) {
            violations.add("'" + g.name + "' is not a usable class name.");
            return false;
        }
        for (AstJson other : ctx.loaded.jsons()) {
            for (Object o : other.unit().types()) {
                if (((AbstractTypeDeclaration) o).getName().getIdentifier().equals(g.name)
                        && Decomposer.packageOf(other).equals(Decomposer.packageOf(g.json))) {
                    violations.add("a top-level type named " + g.name + " already exists in " + other.file() + ".");
                    return false;
                }
            }
            if (other.file().equals(g.newFile)) {
                violations.add("the file " + g.newFile + " already exists.");
                return false;
            }
        }
        return true;
    }

    /** Fills members (requested + dependent private helpers/constants) and the violations of a group. */
    private static void plan(Ctx ctx, Group g) {
        List<String> violations = new ArrayList<>();
        boolean nameFree = freeName(ctx, g, violations);
        g.preconditions.add(new Decomposer.Precondition("name_free", nameFree,
                nameFree ? "No top-level type or file is called " + g.name + " in this package." : violations.get(0)));

        for (Member m : g.requested) {
            g.members.put(m.key, m);
        }
        Set<ASTNode> nodes = new java.util.HashSet<>();
        boolean grew = true;
        List<String> selfContained = new ArrayList<>();
        List<String> privateUse = new ArrayList<>();
        while (grew) {
            grew = false;
            nodes.clear();
            g.members.values().forEach(m -> nodes.add(m.node));
            for (Member member : new ArrayList<>(g.members.values())) {
                List<SimpleName> used = new ArrayList<>();
                member.node.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(SimpleName n) {
                        if (!n.isDeclaration() && n.resolveBinding() != null) {
                            used.add(n);
                        }
                        return true;
                    }
                });
                for (SimpleName n : used) {
                    IBinding b = n.resolveBinding();
                    String k = key(b);
                    if (k != null && declaredIn(b, g.host) && !g.members.containsKey(k)) {
                        Member target = ctx.all.get(k);
                        boolean isStatic = Modifier.isStatic(b.getModifiers());
                        if (!isStatic) {
                            // reached through an object: fine unless it is private to the original class
                            if (Modifier.isPrivate(b.getModifiers())) {
                                selfContained.add("the private instance member '" + n.getIdentifier() + "' of " + g.host.getName());
                            }
                        } else if (Modifier.isPrivate(b.getModifiers())) {
                            boolean onlyHere = ctx.refs.getOrDefault(k, List.of()).stream().allMatch(r -> {
                                if (inside(r.name, nodes)) {
                                    return true;
                                }
                                return target != null && inside(r.name, Set.of(target.node));
                            });
                            if (onlyHere && target != null && movable(target)) {
                                g.members.put(k, target);
                                grew = true;
                                nodes.add(target.node);
                            } else {
                                privateUse.add("the private member '" + n.getIdentifier() + "', which other code uses too");
                            }
                        }
                    } else if (b instanceof ITypeBinding t && t.isMember() && t.getDeclaringClass() != null
                            && t.getDeclaringClass().getTypeDeclaration().isEqualTo(g.host.resolveBinding().getTypeDeclaration())
                            && Modifier.isPrivate(t.getModifiers())) {
                        privateUse.add("the private nested type '" + t.getName() + "'");
                    } else if (k != null && !declaredIn(b, g.host) && Modifier.isStatic(b.getModifiers())) {
                        ITypeBinding owner = b instanceof IMethodBinding m ? m.getDeclaringClass() : ((IVariableBinding) b).getDeclaringClass();
                        if (owner != null && !Decomposer.qualified(n) && g.host.resolveBinding().isSubTypeCompatible(owner)) {
                            selfContained.add("the inherited member '" + n.getIdentifier() + "'");
                        }
                    }
                }
            }
        }
        // synchronized static methods lock the class object: moving them changes what they lock
        for (Member m : g.members.values()) {
            if (m.method && Modifier.isSynchronized(((MethodDeclaration) m.node).getModifiers())) {
                selfContained.add("'" + m.name + "' is synchronized and would lock a different class");
            }
        }
        // how the group is referenced from outside must be re-pointable
        List<String> awkward = new ArrayList<>();
        for (Member m : g.members.values()) {
            if (!m.method) {
                continue;
            }
            for (Ref r : ctx.refs.getOrDefault(m.key, List.of())) {
                if (inside(r.name, nodes)) {
                    continue;
                }
                if (!repointable(r.name, g.host)) {
                    awkward.add("'" + m.name + "' is referenced in " + r.json.file() + " in a form that cannot be re-pointed (static import?)");
                    break;
                }
            }
        }
        g.preconditions.add(new Decomposer.Precondition("no_shared_private_state", privateUse.isEmpty() && selfContained.isEmpty(),
                privateUse.isEmpty() && selfContained.isEmpty()
                        ? "The group only needs members that move with it or that stay visible to the new class."
                        : "The group needs " + String.join("; ", unique(privateUse, selfContained)) + ", which would not resolve in the new class."));
        g.preconditions.add(new Decomposer.Precondition("references_repointable", awkward.isEmpty(),
                awkward.isEmpty() ? "Every call into the group can be re-pointed at the new class." : String.join("; ", awkward) + "."));
        g.extractable = g.preconditions.stream().allMatch(Decomposer.Precondition::satisfied);
    }

    private static List<String> unique(List<String> a, List<String> b) {
        Set<String> s = new LinkedHashSet<>(a);
        s.addAll(b);
        return new ArrayList<>(s);
    }

    /** A field with a literal or `new` initialiser or a private helper method can move; its initialisation order does not matter. */
    private static boolean movable(Member m) {
        if (m.method) {
            return !Modifier.isSynchronized(((MethodDeclaration) m.node).getModifiers());
        }
        VariableDeclarationFragment frag = (VariableDeclarationFragment) ((FieldDeclaration) m.node).fragments().get(0);
        Expression init = frag.getInitializer();
        return init == null || init instanceof org.eclipse.jdt.core.dom.NumberLiteral || init instanceof org.eclipse.jdt.core.dom.StringLiteral
                || init instanceof org.eclipse.jdt.core.dom.BooleanLiteral || init instanceof org.eclipse.jdt.core.dom.CharacterLiteral
                || init instanceof org.eclipse.jdt.core.dom.NullLiteral || init instanceof org.eclipse.jdt.core.dom.ClassInstanceCreation
                || init instanceof org.eclipse.jdt.core.dom.ArrayCreation
                || init instanceof org.eclipse.jdt.core.dom.PrefixExpression;
    }

    /** `m(...)`, `Host.m(...)` or `Host::m`. */
    private static boolean repointable(SimpleName n, TypeDeclaration host) {
        ASTNode p = n.getParent();
        if (p instanceof MethodInvocation mi && mi.getName() == n) {
            return mi.getExpression() == null || isHostName(mi.getExpression(), host);
        }
        if (p instanceof ExpressionMethodReference mr && mr.getName() == n) {
            return isHostName(mr.getExpression(), host);
        }
        return false;
    }

    private static boolean isHostName(Expression e, TypeDeclaration host) {
        return e instanceof Name name && name.resolveBinding() instanceof ITypeBinding t
                && t.getTypeDeclaration().isEqualTo(host.resolveBinding().getTypeDeclaration());
    }

    // ---------- discovery ----------

    static List<Object> discover(ProjectAnalyzer.Loaded loaded) {
        Ctx ctx = context(loaded);
        List<Object> out = new ArrayList<>();
        for (Group g : suggest(ctx)) {
            out.add(describe(g));
        }
        return out;
    }

    private static Map<String, Object> describe(Group g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id);
        m.put("name", g.name);
        m.put("host", g.host.resolveBinding().getQualifiedName());
        m.put("file", g.json.file());
        m.put("new_file", g.newFile);
        List<Object> methods = new ArrayList<>();
        List<Object> dependents = new ArrayList<>();
        int lines = 0;
        for (Member member : g.members.values()) {
            int l = g.json.unit().getLineNumber(member.node.getStartPosition() + member.node.getLength() - 1)
                    - g.json.unit().getLineNumber(member.node.getStartPosition()) + 1;
            lines += l;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", member.id);
            e.put("name", member.name);
            e.put("lines", l);
            (g.requested.contains(member) ? methods : dependents).add(e);
        }
        m.put("methods", methods);
        m.put("moves_along", dependents);
        m.put("lines", lines);
        m.put("extractable", g.extractable);
        List<Object> pre = new ArrayList<>();
        for (Decomposer.Precondition p : g.preconditions) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.id());
            pm.put("status", p.satisfied() ? "satisfied" : "violated");
            pm.put("detail", p.detail());
            pre.add(pm);
        }
        m.put("preconditions", pre);
        m.put("not_extractable_reasons", g.preconditions.stream().filter(p -> !p.satisfied()).map(Decomposer.Precondition::detail).toList());
        return m;
    }

    // ---------- apply ----------

    static Map<String, Object> apply(ProjectAnalyzer.Loaded loaded, Map<String, Object> baselineAnalysis, Path root,
            List<String> files, String level, List<Spec> specs, Path outputDir) throws IOException {
        Ctx ctx = context(loaded);
        List<Group> suggested = suggest(ctx);
        List<Group> chosen = new ArrayList<>();
        for (Spec spec : specs) {
            Group g = null;
            if (spec.methods() != null && !spec.methods().isEmpty()) {
                List<Member> requested = new ArrayList<>();
                for (String id : spec.methods()) {
                    Member found = ctx.all.values().stream().filter(m -> m.method && m.id.equals(id)).findFirst().orElse(null);
                    if (found == null) {
                        return Decomposer.failure("method_not_found", "No static method with id " + id + " exists in this revision.");
                    }
                    if (found.name.equals("main")) {
                        return Decomposer.failure("not_extractable", "main cannot move.");
                    }
                    if (!requested.isEmpty() && found.host != requested.get(0).host) {
                        return Decomposer.failure("invalid_request", "All methods of a group must belong to the same class.");
                    }
                    requested.add(found);
                }
                if (spec.name() == null || spec.name().isBlank()) {
                    return Decomposer.failure("invalid_request", "A group built from a methods list needs a name.");
                }
                g = newGroup(ctx, requested.get(0), spec.name(), requested);
                plan(ctx, g);
            } else {
                for (Group s : suggested) {
                    if (s.id.equals(spec.id())) {
                        g = s;
                    }
                }
                if (g == null) {
                    return Decomposer.failure("class_not_found", "No method group with id " + spec.id() + " exists in this revision.");
                }
                if (spec.name() != null && !spec.name().isBlank() && !spec.name().equals(g.name)) {
                    Group renamed = newGroup(ctx, g.requested.get(0), spec.name(), g.requested);
                    plan(ctx, renamed);
                    g = renamed;
                }
            }
            if (!g.extractable) {
                return Decomposer.failure("not_extractable", g.name + " cannot be extracted: "
                        + String.join(" ", g.preconditions.stream().filter(p -> !p.satisfied()).map(Decomposer.Precondition::detail).toList()));
            }
            chosen.add(g);
        }
        // groups must not share a member and must not clash on the class name
        Map<String, Group> owner = new LinkedHashMap<>();
        for (Group g : chosen) {
            for (String k : g.members.keySet()) {
                Group previous = owner.put(k, g);
                if (previous != null && previous != g) {
                    return Decomposer.failure("group_overlap", "'" + ctx.all.get(k).name + "' belongs to both " + previous.name + " and " + g.name + ".");
                }
            }
            if (chosen.stream().anyMatch(o -> o != g && o.name.equals(g.name))) {
                return Decomposer.failure("invalid_request", "Two groups are called " + g.name + ".");
            }
        }

        // edits per compilation unit
        Map<AstJson, List<Decomposer.Edit>> edits = new IdentityHashMap<>();
        Map<Group, List<Decomposer.Edit>> inGroup = new IdentityHashMap<>();
        for (Group g : chosen) {
            inGroup.put(g, new ArrayList<>());
        }
        for (AstJson json : loaded.jsons()) {
            List<Decomposer.Edit> list = new ArrayList<>();
            edits.put(json, list);
            json.unit().accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName n) {
                    if (n.isDeclaration()) {
                        return true;
                    }
                    IBinding b = n.resolveBinding();
                    String k = key(b);
                    Group here = chosen.stream().filter(g -> g.json == json && inside(n, nodesOf(g))).findFirst().orElse(null);
                    Group target = k == null ? null : owner.get(k);
                    String qualifier = null;
                    if (target != null && target != here) {
                        // a call into a moved member from elsewhere (or from another group)
                        ASTNode p = n.getParent();
                        if (p instanceof MethodInvocation mi && mi.getName() == n) {
                            if (mi.getExpression() == null) {
                                qualifier = target.name;
                            } else {
                                add(json, here, mi.getExpression().getStartPosition(),
                                        mi.getExpression().getStartPosition() + mi.getExpression().getLength(), target.name, list, inGroup);
                                return true;
                            }
                        } else if (p instanceof ExpressionMethodReference mr && mr.getName() == n) {
                            add(json, here, mr.getExpression().getStartPosition(),
                                    mr.getExpression().getStartPosition() + mr.getExpression().getLength(), target.name, list, inGroup);
                            return true;
                        }
                    } else if (here != null && target == null) {
                        // moved code reaching a member that stays: it needs its class name now
                        if (b != null && !Decomposer.qualified(n) && !(n.getParent() instanceof ExpressionMethodReference)) {
                            if (k != null && declaredIn(b, here.host) && Modifier.isStatic(b.getModifiers())) {
                                qualifier = here.host.getName().getIdentifier();
                            } else if (b instanceof ITypeBinding t && t.isMember() && t.getDeclaringClass() != null
                                    && t.getDeclaringClass().getTypeDeclaration().isEqualTo(here.host.resolveBinding().getTypeDeclaration())) {
                                qualifier = here.host.getName().getIdentifier();
                            }
                        }
                    }
                    if (qualifier != null) {
                        add(json, here, n.getStartPosition(), n.getStartPosition(), qualifier + ".", list, inGroup);
                    }
                    return true;
                }
            });
        }

        for (Group g : chosen) {
            Set<ASTNode> nodes = nodesOf(g);
            for (Member m : g.members.values()) {
                boolean outside = ctx.refs.getOrDefault(m.key, List.of()).stream().anyMatch(r -> !inside(r.name, nodes));
                if (!outside) {
                    continue;
                }
                for (Object o : m.node.modifiers()) {
                    if (o instanceof Modifier mod && mod.isPrivate()) {
                        int start = mod.getStartPosition();
                        int end = start + mod.getLength();
                        String text = g.json.sourceText();
                        while (end < text.length() && text.charAt(end) == ' ') {
                            end++;
                        }
                        inGroup.get(g).add(new Decomposer.Edit(start, end, ""));
                    }
                }
            }
        }

        Map<String, String> outputs = new LinkedHashMap<>();
        Map<String, String> added = new LinkedHashMap<>();
        Map<String, Object> hostLines = new LinkedHashMap<>();
        for (Group g : chosen) {
            added.put(g.newFile, newFileText(g, inGroup.get(g)));
        }
        for (AstJson json : loaded.jsons()) {
            List<Decomposer.Edit> list = new ArrayList<>(edits.get(json));
            String source = json.sourceText();
            boolean touched = !list.isEmpty();
            for (Group g : chosen) {
                if (g.json == json) {
                    touched = true;
                    for (Member m : g.members.values()) {
                        list.add(Decomposer.removal(source, m.node.getStartPosition(), m.node.getStartPosition() + m.node.getLength()));
                    }
                }
            }
            if (touched) {
                String rewritten = Decomposer.applyEdits(source, list);
                outputs.put(json.file(), rewritten);
                hostLines.put(json.file(), Map.of("before", Decomposer.lineCount(source), "after", Decomposer.lineCount(rewritten)));
            }
        }
        outputs.keySet().removeIf(f -> outputs.get(f).equals(loaded.jsons().stream().filter(j -> j.file().equals(f)).findFirst().get().sourceText()));

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
        List<String> introduced = Optimizer.newErrors(baselineAnalysis, candidateAnalysis);
        if ("failed".equals(candidateAnalysis.get("status")) || !introduced.isEmpty()) {
            return Decomposer.failure("transformation_invalid", "The split project does not analyse cleanly: "
                    + (introduced.isEmpty() ? "analysis failed" : String.join("; ", introduced)));
        }

        List<Object> extracted = new ArrayList<>();
        for (Group g : chosen) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.id);
            m.put("name", g.name);
            m.put("kind", "method_group");
            m.put("from", g.json.file());
            m.put("new_file", g.newFile);
            m.put("methods", g.members.values().stream().map(x -> x.id).toList());
            m.put("lines", Decomposer.lineCount(added.get(g.newFile)));
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

    private static Set<ASTNode> nodesOf(Group g) {
        Set<ASTNode> nodes = new java.util.HashSet<>();
        g.members.values().forEach(m -> nodes.add(m.node));
        return nodes;
    }

    /** Edits inside moved code are kept per group (positions relative to the original text); the rest go to the unit. */
    private static void add(AstJson json, Group here, int start, int end, String text, List<Decomposer.Edit> unit,
            Map<Group, List<Decomposer.Edit>> inGroup) {
        Decomposer.Edit edit = new Decomposer.Edit(start, end, text);
        if (here != null) {
            inGroup.get(here).add(edit);
        } else {
            unit.add(edit);
        }
    }

    private static String newFileText(Group g, List<Decomposer.Edit> edits) {
        String source = g.json.sourceText();
        StringBuilder body = new StringBuilder();
        boolean first = true;
        for (Member m : g.members.values()) {
            int start = m.node.getStartPosition();
            int end = start + m.node.getLength();
            List<Decomposer.Edit> inner = new ArrayList<>();
            for (Decomposer.Edit e : edits) {
                if (e.start() >= start && e.end() <= end) {
                    inner.add(new Decomposer.Edit(e.start() - start, e.end() - start, e.replacement()));
                }
            }
            String text = source.substring(start, end);
            // a private member that other code now needs is not part of this move; private ones stay private
            text = Decomposer.applyEdits(text, inner);
            int lineStart = start;
            while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
                lineStart--;
            }
            String indent = source.substring(lineStart, start).isBlank() ? source.substring(lineStart, start) : "";
            if (!first) {
                body.append('\n');
            }
            first = false;
            body.append("    ").append(dedent(text, indent)).append('\n');
        }
        String methods = body.toString();
        StringBuilder out = new StringBuilder();
        CompilationUnit unit = g.json.unit();
        if (unit.getPackage() != null) {
            out.append(source, unit.getPackage().getStartPosition(), unit.getPackage().getStartPosition() + unit.getPackage().getLength()).append("\n\n");
        }
        Set<String> usedPackages = new LinkedHashSet<>();
        g.members.values().forEach(m -> m.node.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName n) {
                if (n.resolveBinding() instanceof ITypeBinding t && t.getPackage() != null) {
                    usedPackages.add(t.getPackage().getName());
                }
                return true;
            }
        }));
        boolean any = false;
        for (Object o : unit.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            String name = imp.getName().getFullyQualifiedName();
            String simple = name.substring(name.lastIndexOf('.') + 1);
            boolean keep = imp.isOnDemand()
                    ? imp.isStatic() || usedPackages.contains(name)
                    : Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(methods).find();
            if (keep) {
                out.append(source, imp.getStartPosition(), imp.getStartPosition() + imp.getLength()).append('\n');
                any = true;
            }
        }
        if (any) {
            out.append('\n');
        }
        out.append("final class ").append(g.name).append(" {\n");
        out.append("    private ").append(g.name).append("() {\n    }\n\n");
        out.append(methods);
        out.append("}\n");
        return out.toString();
    }

    private static String dedent(String text, String indent) {
        if (indent.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            sb.append(i > 0 && line.startsWith(indent) ? line.substring(indent.length()) : line);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
