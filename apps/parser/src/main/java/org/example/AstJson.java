package org.example;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;

/**
 * Converts a JDT {@link CompilationUnit} into a nested Map/List tree (serialized by {@link Json}).
 *
 * Statements keep their nesting: an {@code if} holds its {@code then}/{@code else} statement lists,
 * a loop holds its {@code body}, and so on. Expressions are kept as exact source text.
 * Anything not understood is emitted as {@code kind: "unknown"} rather than dropped.
 */
final class AstJson {
    private final CompilationUnit cu;
    private final String src;
    private final boolean bindings;
    private final List<Object> methods = new ArrayList<>();
    private final List<Object> cfgFailures = new ArrayList<>();
    private final Map<MethodDeclaration, ProjectGraph.Link> methodLinks = new java.util.IdentityHashMap<>();
    private int cfgCount;

    /** Project mode: project-relative path stamped on every source range; null for a single file. */
    private String file;
    /** Project mode: {@code "pkg."} prefix of every type path, so method ids are unique across packages. */
    private String idPrefix = "";
    /** Project mode: keeps CFG node ids unique across files (e.g. {@code "f3"} gives {@code f3m0.n0}). */
    private String cfgPrefix = "";
    private boolean ownGraph = true;
    /** Every file analysed together with this one (itself in single-file mode); callee lookup spans them. */
    private List<AstJson> peers = List.of(this);

    /** @param bindings whether the unit was parsed with resolved bindings (semantic information) */
    AstJson(CompilationUnit cu, String src, boolean bindings) {
        this.cu = cu;
        this.src = src;
        this.bindings = bindings;
    }

    /**
     * One file of a multi-file project. The project graph is built once over all files by
     * {@link ProjectAnalyzer}, not per file.
     */
    static AstJson forProjectFile(CompilationUnit cu, String src, boolean bindings, String file, int index) {
        AstJson json = new AstJson(cu, src, bindings);
        json.file = file;
        json.cfgPrefix = "f" + index;
        json.ownGraph = false;
        if (cu.getPackage() != null) {
            json.idPrefix = cu.getPackage().getName().getFullyQualifiedName() + ".";
        }
        return json;
    }

    CompilationUnit unit() {
        return cu;
    }

    String sourceText() {
        return src;
    }

    static Expression strip(Expression e) {
        while (e instanceof ParenthesizedExpression p) {
            e = p.getExpression();
        }
        return e;
    }

    /** Whitespace-free source text, the form used in ids and messages. */
    static String normalize(String s) {
        return s.replaceAll("\\s+", "");
    }

    /** {@code contact.id()} -> {@code Id}, {@code t.name} -> {@code Name}, {@code key} -> {@code Key}. */
    static String keyNameFor(Expression key) {
        Expression e = strip(key);
        String name = switch (e) {
            case MethodInvocation mi -> mi.getName().getIdentifier().replaceFirst("^(get|is)(?=[A-Z])", "");
            case QualifiedName q -> q.getName().getIdentifier();
            case FieldAccess fa -> fa.getName().getIdentifier();
            case SimpleName n -> n.getIdentifier();
            default -> "Key";
        };
        return name.isEmpty() ? "Key" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    List<AstJson> peers() {
        return peers;
    }

    void setPeers(List<AstJson> peers) {
        this.peers = peers;
    }

    String file() {
        return file;
    }

    Map<MethodDeclaration, ProjectGraph.Link> methodLinks() {
        return methodLinks;
    }

    Map<String, Object> convert() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<IProblem> problems = List.of(cu.getProblems());
        boolean hasErrors = problems.stream().anyMatch(IProblem::isError);

        out.put("protocol_version", 1);
        out.put("status", hasErrors ? "partial" : "completed");
        out.put("semantic", Map.of("bindings", bindings));
        out.put("package", cu.getPackage() == null ? null : cu.getPackage().getName().getFullyQualifiedName());

        List<Object> imports = new ArrayList<>();
        for (Object o : cu.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", imp.getName().getFullyQualifiedName());
            m.put("static", imp.isStatic());
            m.put("on_demand", imp.isOnDemand());
            imports.add(m);
        }
        out.put("imports", imports);

        List<Object> types = new ArrayList<>();
        for (Object o : cu.types()) {
            types.add(typeDecl((AbstractTypeDeclaration) o, idPrefix));
        }
        out.put("types", types);
        out.put("methods", methods);
        out.put("project_graph", ownGraph ? projectGraph() : null);
        List<Object> diagnostics = diagnostics(problems);
        diagnostics.addAll(cfgFailures);
        diagnostics.addAll(unanalyzedBodies());
        out.put("diagnostics", diagnostics);
        return out;
    }

    /**
     * Methods inside anonymous classes and enum-constant bodies are neither listed in {@code methods} nor given a CFG
     * or analysis (their code stays in the statement text). Say so instead of dropping them silently.
     */
    private List<Object> unanalyzedBodies() {
        List<Object> out = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(AnonymousClassDeclaration n) {
                boolean constant = n.getParent() instanceof EnumConstantDeclaration;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("code", "unanalyzed_anonymous_body");
                d.put("category", "analysis");
                d.put("severity", "info");
                d.put("message", "Methods declared in this " + (constant ? "enum constant body" : "anonymous class")
                        + " are not listed and have no CFG or complexity analysis; their code is kept only as text.");
                d.put("source_range", range(n));
                out.add(d);
                return true;
            }
        });
        return out;
    }

    /** Best effort like the CFG: a failure becomes a warning and `project_graph: null`. */
    private Map<String, Object> projectGraph() {
        try {
            return new ProjectGraph(this, cu, methodLinks).build();
        } catch (RuntimeException | StackOverflowError e) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("code", "project_graph_failed");
            d.put("category", "analysis");
            d.put("severity", "warning");
            d.put("message", "Project graph could not be built: " + e);
            d.put("source_range", range(0, 0));
            cfgFailures.add(d);
            return null;
        }
    }

    // ---------- diagnostics ----------

    private List<Object> diagnostics(List<IProblem> problems) {
        List<Object> list = new ArrayList<>();
        for (IProblem p : problems) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", "jdt_" + Integer.toHexString(p.getID()));
            m.put("category", (p.getID() & IProblem.Syntax) != 0 ? "syntax" : "semantic");
            m.put("severity", p.isError() ? "error" : p.isWarning() ? "warning" : "info");
            m.put("message", p.getMessage());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("start", p.getSourceStart());
            r.put("length", Math.max(0, p.getSourceEnd() - p.getSourceStart() + 1));
            r.put("line", p.getSourceLineNumber());
            if (file != null) {
                r.put("file", file);
            }
            m.put("source_range", r);
            list.add(m);
        }
        return list;
    }

    // ---------- declarations ----------

    private Map<String, Object> typeDecl(AbstractTypeDeclaration t, String parentPath) {
        String path = parentPath + t.getName().getIdentifier();
        Map<String, Object> m = node(switch (t) {
            case TypeDeclaration td -> td.isInterface() ? "interface" : "class";
            case EnumDeclaration ignoredEnum -> "enum";
            case RecordDeclaration ignoredRecord -> "record";
            default -> "annotation_type";
        }, t);
        m.put("name", t.getName().getIdentifier());
        ITypeBinding tb = t.resolveBinding();
        m.put("qualified_name", tb == null ? null : tb.getQualifiedName());
        m.put("modifiers", modifiers(t.modifiers()));

        if (t instanceof TypeDeclaration td) {
            m.put("extends", td.getSuperclassType() == null ? null : text(td.getSuperclassType()));
            m.put("implements", texts(td.superInterfaceTypes()));
        } else if (t instanceof EnumDeclaration ed) {
            m.put("implements", texts(ed.superInterfaceTypes()));
            List<Object> constants = new ArrayList<>();
            for (Object o : ed.enumConstants()) {
                EnumConstantDeclaration c = (EnumConstantDeclaration) o;
                Map<String, Object> cm = node("enum_constant", c);
                cm.put("name", c.getName().getIdentifier());
                cm.put("arguments", texts(c.arguments()));
                withCalls(cm, c.arguments());
                constants.add(cm);
            }
            m.put("constants", constants);
        } else if (t instanceof RecordDeclaration rd) {
            m.put("implements", texts(rd.superInterfaceTypes()));
            m.put("components", parameters(rd.recordComponents()));
        }

        List<Object> members = new ArrayList<>();
        for (Object o : t.bodyDeclarations()) {
            members.add(member((BodyDeclaration) o, path));
        }
        m.put("members", members);
        return m;
    }

    private Map<String, Object> member(BodyDeclaration d, String typePath) {
        switch (d) {
            case MethodDeclaration md -> {
                return method(md, typePath);
            }
            case FieldDeclaration fd -> {
                Map<String, Object> m = node("field", fd);
                m.put("modifiers", modifiers(fd.modifiers()));
                m.put("var_type", text(fd.getType()));
                m.put("fragments", fragments(fd.fragments()));
                return withCalls(m, fd.fragments());
            }
            case Initializer init -> {
                Map<String, Object> m = node("initializer", init);
                m.put("static", Modifier.isStatic(init.getModifiers()));
                m.put("body", block(init.getBody()));
                return m;
            }
            case AbstractTypeDeclaration td -> {
                return typeDecl(td, typePath + ".");
            }
            default -> {
                return unknown(d);
            }
        }
    }

    private Map<String, Object> method(MethodDeclaration md, String typePath) {
        Map<String, Object> m = node(md.isConstructor() ? "constructor" : "method", md);
        String name = md.getName().getIdentifier();
        List<String> ptypes = new ArrayList<>();
        // a compact record constructor has no parameter list of its own: its parameters are the components
        List<?> declaredParameters = md.isCompactConstructor() && md.getParent() instanceof RecordDeclaration rd
                ? rd.recordComponents() : md.parameters();
        for (Object o : declaredParameters) {
            SingleVariableDeclaration p = (SingleVariableDeclaration) o;
            ptypes.add(text(p.getType()) + (p.isVarargs() ? "..." : ""));
        }
        String id = typePath + "." + name + "(" + String.join(",", ptypes) + ")";
        m.put("id", id);
        m.put("name", name);
        m.put("modifiers", modifiers(md.modifiers()));
        m.put("return_type", md.getReturnType2() == null ? null : text(md.getReturnType2()));
        IMethodBinding mb = md.resolveBinding();
        m.put("resolved_return_type", mb == null ? null : typeInfo(mb.getReturnType()));
        m.put("parameters", parameters(declaredParameters));
        m.put("throws", texts(md.thrownExceptionTypes()));
        m.put("resolved_throws", mb == null ? null : typeInfos(mb.getExceptionTypes()));
        m.put("body", md.getBody() == null ? null : block(md.getBody()));
        Map<String, Object> cfg = md.getBody() == null ? null : cfg(md, id);
        m.put("cfg", cfg);
        methodLinks.put(md, new ProjectGraph.Link(id, cfg == null ? null : (String) cfg.get("entry")));
        m.put("analysis", md.getBody() == null ? null : analysis(md, id, cfg));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", id);
        summary.put("name", name);
        summary.put("source_range", range(md));
        methods.add(summary);
        return m;
    }

    /** Complexity analysis is best effort: a failure becomes a warning and `analysis: null`, never a failed parse. */
    private Map<String, Object> analysis(MethodDeclaration md, String methodId, Map<String, Object> cfg) {
        try {
            return new ComplexityAnalyzer(this, cu, md, cfg).analyze();
        } catch (RuntimeException | StackOverflowError e) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("code", "analysis_failed");
            d.put("category", "analysis");
            d.put("severity", "warning");
            d.put("message", "Complexity analysis could not be completed for " + methodId + ": " + e);
            d.put("source_range", range(md));
            cfgFailures.add(d);
            return null;
        }
    }

    /** A CFG failure must not invalidate the AST: report it as a warning and emit `cfg: null`. */
    private Map<String, Object> cfg(MethodDeclaration md, String methodId) {
        try {
            return new CfgBuilder(this, cfgPrefix + "m" + cfgCount++, methodId, md).build();
        } catch (RuntimeException | StackOverflowError e) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("code", "cfg_build_failed");
            d.put("category", "analysis");
            d.put("severity", "warning");
            d.put("message", "Control-flow graph could not be built for " + methodId + ": " + e);
            d.put("source_range", range(md));
            cfgFailures.add(d);
            return null;
        }
    }

    private List<Object> parameters(List<?> params) {
        List<Object> list = new ArrayList<>();
        for (Object o : params) {
            SingleVariableDeclaration p = (SingleVariableDeclaration) o;
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.getName().getIdentifier());
            pm.put("type", text(p.getType()));
            IVariableBinding vb = p.resolveBinding();
            pm.put("resolved_type", vb == null ? null : typeInfo(vb.getType()));
            pm.put("varargs", p.isVarargs());
            pm.put("modifiers", modifiers(p.modifiers()));
            list.add(pm);
        }
        return list;
    }

    private List<Object> fragments(List<?> frags) {
        List<Object> list = new ArrayList<>();
        for (Object o : frags) {
            VariableDeclarationFragment f = (VariableDeclarationFragment) o;
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", f.getName().getIdentifier());
            fm.put("extra_dimensions", f.getExtraDimensions());
            fm.put("initializer", f.getInitializer() == null ? null : text(f.getInitializer()));
            IVariableBinding vb = f.resolveBinding();
            fm.put("resolved_type", vb == null ? null : typeInfo(vb.getType()));
            fm.put("source_range", range(f));
            list.add(fm);
        }
        return list;
    }

    // ---------- statements ----------

    private List<Object> block(Block b) {
        return statements(b.statements());
    }

    private List<Object> statements(List<?> stmts) {
        List<Object> list = new ArrayList<>();
        for (Object o : stmts) {
            list.add(statement((Statement) o));
        }
        return list;
    }

    /** A nested body: a block becomes its statement list, a single statement becomes a one-element list. */
    private List<Object> body(Statement s) {
        if (s == null) {
            return new ArrayList<>();
        }
        if (s instanceof Block b) {
            return block(b);
        }
        List<Object> list = new ArrayList<>();
        list.add(statement(s));
        return list;
    }

    private Map<String, Object> statement(Statement s) {
        switch (s) {
            case Block b -> {
                Map<String, Object> m = node("block", b);
                m.put("body", block(b));
                return m;
            }
            case IfStatement st -> {
                Map<String, Object> m = node("if", st);
                m.put("condition", text(st.getExpression()));
                m.put("then", body(st.getThenStatement()));
                m.put("else", st.getElseStatement() == null ? null : body(st.getElseStatement()));
                return withCalls(m, st.getExpression());
            }
            case ForStatement st -> {
                Map<String, Object> m = node("for", st);
                m.put("init", texts(st.initializers()));
                m.put("condition", st.getExpression() == null ? null : text(st.getExpression()));
                m.put("update", texts(st.updaters()));
                m.put("body", body(st.getBody()));
                return withCalls(m, st.initializers(), st.getExpression(), st.updaters());
            }
            case EnhancedForStatement st -> {
                Map<String, Object> m = node("foreach", st);
                m.put("variable", text(st.getParameter()));
                IVariableBinding vb = st.getParameter().resolveBinding();
                m.put("resolved_variable_type", vb == null ? null : typeInfo(vb.getType()));
                m.put("iterable", text(st.getExpression()));
                m.put("body", body(st.getBody()));
                return withCalls(m, st.getExpression());
            }
            case WhileStatement st -> {
                Map<String, Object> m = node("while", st);
                m.put("condition", text(st.getExpression()));
                m.put("body", body(st.getBody()));
                return withCalls(m, st.getExpression());
            }
            case DoStatement st -> {
                Map<String, Object> m = node("do_while", st);
                m.put("body", body(st.getBody()));
                m.put("condition", text(st.getExpression()));
                return withCalls(m, st.getExpression());
            }
            case SwitchStatement st -> {
                Map<String, Object> m = node("switch", st);
                m.put("selector", text(st.getExpression()));
                m.put("cases", switchCases(st.statements()));
                return withCalls(m, st.getExpression());
            }
            case TryStatement st -> {
                Map<String, Object> m = node("try", st);
                m.put("resources", texts(st.resources()));
                withCalls(m, st.resources());
                m.put("body", block(st.getBody()));
                List<Object> catches = new ArrayList<>();
                for (Object o : st.catchClauses()) {
                    CatchClause c = (CatchClause) o;
                    Map<String, Object> cm = node("catch", c);
                    cm.put("exception_type", text(c.getException().getType()));
                    cm.put("resolved_exception_types", caughtTypes(c.getException().getType()));
                    cm.put("name", c.getException().getName().getIdentifier());
                    cm.put("body", block(c.getBody()));
                    catches.add(cm);
                }
                m.put("catches", catches);
                m.put("finally", st.getFinally() == null ? null : block(st.getFinally()));
                return m;
            }
            case SynchronizedStatement st -> {
                Map<String, Object> m = node("synchronized", st);
                m.put("lock", text(st.getExpression()));
                m.put("body", block(st.getBody()));
                return withCalls(m, st.getExpression());
            }
            case LabeledStatement st -> {
                Map<String, Object> m = node("labeled", st);
                m.put("label", st.getLabel().getIdentifier());
                m.put("body", body(st.getBody()));
                return m;
            }
            case ReturnStatement st -> {
                Map<String, Object> m = node("return", st);
                m.put("value", st.getExpression() == null ? null : text(st.getExpression()));
                return withCalls(m, st.getExpression());
            }
            case ThrowStatement st -> {
                Map<String, Object> m = node("throw", st);
                m.put("value", text(st.getExpression()));
                ITypeBinding tb = st.getExpression().resolveTypeBinding();
                m.put("resolved_type", tb == null ? null : typeInfo(tb));
                return withCalls(m, st.getExpression());
            }
            case BreakStatement st -> {
                Map<String, Object> m = node("break", st);
                m.put("label", st.getLabel() == null ? null : st.getLabel().getIdentifier());
                return m;
            }
            case ContinueStatement st -> {
                Map<String, Object> m = node("continue", st);
                m.put("label", st.getLabel() == null ? null : st.getLabel().getIdentifier());
                return m;
            }
            case YieldStatement st when st.getParent() instanceof SwitchStatement -> {
                // `case X -> expr;` in a switch statement: JDT models the expr as an implicit yield
                Map<String, Object> m = node("expression", st);
                m.put("expression", text(st.getExpression()));
                m.put("expression_type", st.getExpression().getClass().getSimpleName());
                return withCalls(m, st.getExpression());
            }
            case YieldStatement st -> {
                Map<String, Object> m = node("yield", st);
                m.put("value", text(st.getExpression()));
                return withCalls(m, st.getExpression());
            }
            case AssertStatement st -> {
                Map<String, Object> m = node("assert", st);
                m.put("condition", text(st.getExpression()));
                m.put("message", st.getMessage() == null ? null : text(st.getMessage()));
                return withCalls(m, st.getExpression(), st.getMessage());
            }
            case ExpressionStatement st -> {
                Map<String, Object> m = node("expression", st);
                m.put("expression", text(st.getExpression()));
                m.put("expression_type", st.getExpression().getClass().getSimpleName());
                return withCalls(m, st.getExpression());
            }
            case VariableDeclarationStatement st -> {
                Map<String, Object> m = node("variable_declaration", st);
                m.put("modifiers", modifiers(st.modifiers()));
                m.put("var_type", text(st.getType()));
                m.put("fragments", fragments(st.fragments()));
                return withCalls(m, st.fragments());
            }
            case TypeDeclarationStatement st -> {
                Map<String, Object> m = node("local_type", st);
                m.put("declaration", typeDecl((AbstractTypeDeclaration) st.getDeclaration(), "local."));
                return m;
            }
            case ConstructorInvocation st -> {
                Map<String, Object> m = node("this_call", st);
                m.put("arguments", texts(st.arguments()));
                return withCalls(m, st.arguments());
            }
            case SuperConstructorInvocation st -> {
                Map<String, Object> m = node("super_call", st);
                m.put("arguments", texts(st.arguments()));
                return withCalls(m, st.arguments());
            }
            case EmptyStatement st -> {
                return node("empty", st);
            }
            default -> {
                return unknown(s);
            }
        }
    }

    /**
     * JDT stores switch contents flat: SwitchCase, stmt, stmt, SwitchCase, ...
     * Group them so each case owns its statements. Old-style fallthrough stays visible:
     * a case without {@code break} simply has no break statement in its body.
     */
    private List<Object> switchCases(List<?> flat) {
        List<Object> cases = new ArrayList<>();
        Map<String, Object> current = null;
        List<Object> currentBody = null;
        for (Object o : flat) {
            Statement s = (Statement) o;
            if (s instanceof SwitchCase sc) {
                current = node("case", sc);
                current.put("default", sc.isDefault());
                current.put("labels", texts(sc.expressions()));
                current.put("arrow", sc.isSwitchLabeledRule());
                currentBody = new ArrayList<>();
                current.put("body", currentBody);
                cases.add(current);
            } else if (currentBody != null) {
                currentBody.add(statement(s));
            } else {
                cases.add(unknown(s));
            }
        }
        return cases;
    }

    // ---------- helpers ----------

    private Map<String, Object> node(String kind, ASTNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("source_range", range(n));
        return m;
    }

    /**
     * Adds {@code calls}: every method/constructor invocation in the statement's own expressions,
     * outermost first, with resolved binding data. Omitted when there are none. Lambda bodies and
     * anonymous class bodies are not searched.
     */
    private Map<String, Object> withCalls(Map<String, Object> m, Object... roots) {
        List<Object> calls = new ArrayList<>();
        ASTVisitor collector = new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation n) {
                calls.add(call("method_call", n, n.getName().getIdentifier(), n.getExpression(), n.resolveMethodBinding()));
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation n) {
                calls.add(call("method_call", n, n.getName().getIdentifier(), null, n.resolveMethodBinding()));
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation n) {
                calls.add(call("constructor_call", n, text(n.getType()), null, n.resolveConstructorBinding()));
                return true;
            }

            @Override
            public boolean visit(LambdaExpression n) {
                return false;
            }

            @Override
            public boolean visit(AnonymousClassDeclaration n) {
                return false;
            }
        };
        for (Object root : roots) {
            if (root instanceof ASTNode node) {
                node.accept(collector);
            } else if (root instanceof List<?> list) {
                for (Object o : list) {
                    ((ASTNode) o).accept(collector);
                }
            }
        }
        if (!calls.isEmpty()) {
            m.put("calls", calls);
        }
        return m;
    }

    private Map<String, Object> call(String kind, ASTNode n, String name, Expression receiver, IMethodBinding b) {
        Map<String, Object> m = node(kind, n);
        m.put("name", name);
        m.put("expression", text(n));
        boolean resolved = b != null && !b.isRecovered();
        m.put("resolved", resolved);
        if (receiver != null) {
            ITypeBinding rt = receiver.resolveTypeBinding();
            m.put("receiver_type", rt == null ? null : typeInfo(rt));
        }
        if (resolved) {
            m.put("declaring_class", b.getDeclaringClass().getErasure().getQualifiedName());
            List<Object> ptypes = typeInfos(b.getParameterTypes());
            List<String> pnames = new ArrayList<>();
            for (Object o : ptypes) {
                pnames.add((String) ((Map<?, ?>) o).get("name"));
            }
            String shown = kind.equals("constructor_call") ? b.getDeclaringClass().getErasure().getName() : b.getName();
            m.put("signature", shown + "(" + String.join(", ", pnames) + ")");
            m.put("parameter_types", ptypes);
            m.put("return_type", kind.equals("constructor_call") ? null : typeInfo(b.getReturnType()));
            m.put("static", Modifier.isStatic(b.getModifiers()));
            m.put("thrown_exceptions", typeInfos(b.getExceptionTypes()));
        }
        return m;
    }

    /** Qualified type name plus a coarse kind; recovered (unresolvable) types are marked "unresolved". */
    private Map<String, Object> typeInfo(ITypeBinding t) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (t.isRecovered()) {
            m.put("name", t.getName());
            m.put("kind", "unresolved");
            return m;
        }
        m.put("name", t.getQualifiedName());
        m.put("kind", t.isPrimitive() ? "primitive"
                : t.isNullType() ? "null"
                : t.isArray() ? "array"
                : t.isTypeVariable() || t.isCapture() ? "type_variable"
                : t.isAnnotation() ? "annotation"
                : t.isEnum() ? "enum"
                : t.isRecord() ? "record"
                : t.isInterface() ? "interface"
                : "class");
        return m;
    }

    private List<Object> typeInfos(ITypeBinding[] types) {
        List<Object> list = new ArrayList<>();
        for (ITypeBinding t : types) {
            list.add(typeInfo(t));
        }
        return list;
    }

    /** `catch (A | B e)` has two caught types; a plain catch has one. */
    private List<Object> caughtTypes(Type type) {
        List<Object> list = new ArrayList<>();
        List<?> alternatives = type instanceof UnionType u ? u.types() : List.of(type);
        for (Object o : alternatives) {
            ITypeBinding tb = ((Type) o).resolveBinding();
            if (tb != null) {
                list.add(typeInfo(tb));
            }
        }
        return list;
    }

    private Map<String, Object> unknown(ASTNode n) {
        Map<String, Object> m = node("unknown", n);
        m.put("node_type", n.getClass().getSimpleName());
        m.put("text", text(n));
        return m;
    }

    Map<String, Object> range(ASTNode n) {
        return range(n.getStartPosition(), n.getLength());
    }

    Map<String, Object> range(int start, int length) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (file != null) {
            r.put("file", file);
        }
        r.put("start", start);
        r.put("length", length);
        r.put("line", cu.getLineNumber(start));
        r.put("end_line", cu.getLineNumber(Math.max(start, start + length - 1)));
        return r;
    }

    String text(ASTNode n) {
        return src.substring(n.getStartPosition(), n.getStartPosition() + n.getLength());
    }

    private List<Object> texts(List<?> nodes) {
        List<Object> list = new ArrayList<>();
        for (Object o : nodes) {
            list.add(text((ASTNode) o));
        }
        return list;
    }

    private List<Object> modifiers(List<?> mods) {
        List<Object> list = new ArrayList<>();
        for (Object o : mods) {
            if (o instanceof org.eclipse.jdt.core.dom.Modifier mod) {
                list.add(mod.getKeyword().toString());
            } else {
                list.add(text((ASTNode) o));
            }
        }
        return list;
    }
}
