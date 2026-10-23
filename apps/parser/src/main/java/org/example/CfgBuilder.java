package org.example;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.jdt.core.dom.*;

/**
 * Statement-level control-flow graph of one method body, built straight from the JDT AST.
 *
 * Construction is continuation-passing: {@code build(stmt, ctx, next)} returns the entry node of
 * the sub-graph for {@code stmt}, whose normal completion flows into {@code next}. Jumps
 * (return/break/continue) and exceptions are resolved through {@link Ctx}, so a {@code finally}
 * block is simply built again (cloned) for every exit path that crosses it.
 *
 * Exception edges are an over-approximation: only inside a {@code try}, from nodes that may
 * throw (calls, {@code new}, array access, casts, division, {@code throw}) to every catch clause
 * that could match, and onward to the outer handler or method exit unless a clause catches
 * {@code Exception}/{@code Throwable}.
 */
final class CfgBuilder {
    private static final class Node {
        final String kind;
        final String statementKind;
        final String text;
        final Map<String, Object> range;

        Node(String kind, String statementKind, String text, Map<String, Object> range) {
            this.kind = kind;
            this.statementKind = statementKind;
            this.text = text;
            this.range = range;
        }
    }

    private record Edge(Node from, Node to, String kind, String label) {
    }

    /** {@code type == null && !catchAll} means "unknown type": may match, propagation continues. */
    private record Handler(Supplier<Node> node, String label, ITypeBinding type, boolean catchAll) {
    }

    private record Ctx(Supplier<Node> ret, Supplier<Node> brk, Supplier<Node> cont,
                       Map<String, Supplier<Node>> breaks, Map<String, Supplier<Node>> continues,
                       List<Handler> handlers, List<String> labels) {
        Ctx withHandlers(List<Handler> h) {
            return new Ctx(ret, brk, cont, breaks, continues, h, labels);
        }

        Ctx withLabels(List<String> l) {
            return new Ctx(ret, brk, cont, breaks, continues, handlers, l);
        }
    }

    private final AstJson json;
    private final String prefix;
    private final String methodId;
    private final MethodDeclaration method;
    private final List<Node> nodes = new ArrayList<>();
    private final Set<Edge> edges = new LinkedHashSet<>();
    private Node exit;

    CfgBuilder(AstJson json, String prefix, String methodId, MethodDeclaration method) {
        this.json = json;
        this.prefix = prefix;
        this.methodId = methodId;
        this.method = method;
    }

    Map<String, Object> build() {
        Map<String, Object> methodRange = json.range(method);
        Node entry = node("entry", null, null, methodRange);
        exit = node("exit", null, null, methodRange);

        Ctx root = new Ctx(() -> exit, null, null, Map.of(), Map.of(), List.of(), List.of());
        edge(entry, buildList(method.getBody().statements(), root, exit), "next");
        return render(entry);
    }

    // ---------- statements ----------

    private Node buildList(List<?> stmts, Ctx c, Node next) {
        Node entry = next;
        for (int i = stmts.size() - 1; i >= 0; i--) {
            entry = build((Statement) stmts.get(i), c, entry);
        }
        return entry;
    }

    private Node build(Statement s, Ctx c, Node next) {
        if (!c.labels().isEmpty() && !(s instanceof LabeledStatement || isLoop(s))) {
            c = c.withLabels(List.of());
        }
        switch (s) {
            case Block b -> {
                return buildList(b.statements(), c, next);
            }
            case EmptyStatement _e -> {
                return next;
            }
            case IfStatement st -> {
                Node cond = node("branch", "if", st.getExpression());
                Node then = build(st.getThenStatement(), c, next);
                Node otherwise = st.getElseStatement() == null ? next : build(st.getElseStatement(), c, next);
                edge(cond, then, "true");
                edge(cond, otherwise, "false");
                return throwing(cond, c, st.getExpression());
            }
            case WhileStatement st -> {
                Node cond = node("branch", "while", st.getExpression());
                Node body = build(st.getBody(), loop(c, next, () -> cond), cond);
                edge(cond, body, "true");
                edge(cond, next, "false");
                return throwing(cond, c, st.getExpression());
            }
            case DoStatement st -> {
                Node cond = node("branch", "do_while", st.getExpression());
                Node body = build(st.getBody(), loop(c, next, () -> cond), cond);
                edge(cond, body, "true");
                edge(cond, next, "false");
                throwing(cond, c, st.getExpression());
                return body;
            }
            case ForStatement st -> {
                return buildFor(st, c, next);
            }
            case EnhancedForStatement st -> {
                int start = st.getParameter().getStartPosition();
                int end = st.getExpression().getStartPosition() + st.getExpression().getLength();
                Node head = node("branch", "foreach",
                        json.text(st.getParameter()) + " : " + json.text(st.getExpression()),
                        json.range(start, end - start));
                Node body = build(st.getBody(), loop(c, next, () -> head), head);
                edge(head, body, "true");
                edge(head, next, "false");
                return throwing(head, c, st.getExpression());
            }
            case LabeledStatement st -> {
                String label = st.getLabel().getIdentifier();
                Map<String, Supplier<Node>> breaks = new HashMap<>(c.breaks());
                breaks.put(label, () -> next);
                List<String> labels = new ArrayList<>(c.labels());
                labels.add(label);
                Ctx labeled = new Ctx(c.ret(), c.brk(), c.cont(), breaks, c.continues(), c.handlers(), labels);
                return build(st.getBody(), labeled, next);
            }
            case SwitchStatement st -> {
                return buildSwitch(st, c, next);
            }
            case TryStatement st -> {
                return buildTry(st, c, next);
            }
            case SynchronizedStatement st -> {
                Node body = buildList(st.getBody().statements(), c, next);
                Node lock = node("statement", "synchronized", st.getExpression());
                edge(lock, body, "next");
                return throwing(lock, c, st.getExpression());
            }
            case ReturnStatement st -> {
                Node n = node("return", "return", st);
                edge(n, c.ret().get(), "return");
                return throwing(n, c, st.getExpression());
            }
            case BreakStatement st -> {
                Supplier<Node> target = st.getLabel() == null ? c.brk() : c.breaks().get(st.getLabel().getIdentifier());
                Node n = node("break", "break", st);
                edge(n, target == null ? exit : target.get(), "break");
                return n;
            }
            case ContinueStatement st -> {
                Supplier<Node> target = st.getLabel() == null ? c.cont() : c.continues().get(st.getLabel().getIdentifier());
                Node n = node("continue", "continue", st);
                edge(n, target == null ? exit : target.get(), "continue");
                return n;
            }
            case ThrowStatement st -> {
                Node n = node("throw", "throw", st);
                exceptionEdges(n, c, st.getExpression().resolveTypeBinding());
                return n;
            }
            case ExpressionStatement st -> {
                return simple("expression", st, st, c, next);
            }
            case VariableDeclarationStatement st -> {
                return simple("variable_declaration", st, st, c, next);
            }
            case AssertStatement st -> {
                return simple("assert", st, st, c, next);
            }
            case ConstructorInvocation st -> {
                return simple("this_call", st, st, c, next);
            }
            case SuperConstructorInvocation st -> {
                return simple("super_call", st, st, c, next);
            }
            case YieldStatement st -> {
                // `case X -> expr;` in a switch statement is an implicit yield in JDT
                return simple(st.getParent() instanceof SwitchStatement ? "expression" : "yield", st, st, c, next);
            }
            case TypeDeclarationStatement st -> {
                return simple("local_type", st, null, c, next);
            }
            default -> {
                return simple("unknown", s, s, c, next);
            }
        }
    }

    private Node simple(String statementKind, ASTNode text, ASTNode scan, Ctx c, Node next) {
        Node n = node("statement", statementKind, text);
        edge(n, next, "next");
        return throwing(n, c, scan);
    }

    private Node buildFor(ForStatement st, Ctx c, Node next) {
        Node head = st.getExpression() != null
                ? node("branch", "for", st.getExpression())
                : node("join", "for", null, json.range(st));
        Node updates = head;
        for (int i = st.updaters().size() - 1; i >= 0; i--) {
            updates = simple("for_update", (Expression) st.updaters().get(i), (Expression) st.updaters().get(i), c, updates);
        }
        Node updateEntry = updates;
        Node body = build(st.getBody(), loop(c, next, () -> updateEntry), updateEntry);
        if (st.getExpression() != null) {
            edge(head, body, "true");
            edge(head, next, "false");
            throwing(head, c, st.getExpression());
        } else {
            edge(head, body, "next");
        }
        Node entry = head;
        for (int i = st.initializers().size() - 1; i >= 0; i--) {
            entry = simple("for_init", (Expression) st.initializers().get(i), (Expression) st.initializers().get(i), c, entry);
        }
        return entry;
    }

    /**
     * JDT stores switch contents flat (SwitchCase, stmt, ..., SwitchCase, ...). Old-style groups
     * fall through into the next group; arrow groups go straight to the end of the switch.
     * Without a default case the selector can also skip the whole switch.
     */
    private Node buildSwitch(SwitchStatement st, Ctx c, Node next) {
        List<SwitchCase> cases = new ArrayList<>();
        List<List<Statement>> bodies = new ArrayList<>();
        for (Object o : st.statements()) {
            if (o instanceof SwitchCase sc) {
                cases.add(sc);
                bodies.add(new ArrayList<>());
            } else if (!bodies.isEmpty()) {
                bodies.get(bodies.size() - 1).add((Statement) o);
            }
        }

        Ctx inner = new Ctx(c.ret(), () -> next, c.cont(), c.breaks(), c.continues(), c.handlers(), List.of());
        Node[] entries = new Node[cases.size()];
        Node fallthrough = next;
        for (int i = cases.size() - 1; i >= 0; i--) {
            entries[i] = buildList(bodies.get(i), inner, cases.get(i).isSwitchLabeledRule() ? next : fallthrough);
            fallthrough = entries[i];
        }

        Node sw = node("switch", "switch", st.getExpression());
        boolean hasDefault = false;
        for (int i = 0; i < cases.size(); i++) {
            SwitchCase sc = cases.get(i);
            if (sc.isDefault()) {
                hasDefault = true;
                edge(sw, entries[i], "default");
            } else {
                List<String> labels = new ArrayList<>();
                for (Object e : sc.expressions()) {
                    labels.add(json.text((ASTNode) e));
                }
                edge(sw, entries[i], "case", String.join(", ", labels));
            }
        }
        if (!hasDefault) {
            edge(sw, next, "default");
        }
        return throwing(sw, c, st.getExpression());
    }

    private Node buildTry(TryStatement st, Ctx outer, Node next) {
        Block fin = st.getFinally();
        Node afterNormal = fin == null ? next : buildList(fin.statements(), outer, next);

        Handler finHandler = null;
        if (fin != null) {
            Supplier<Node> rethrowEntry = memo(() -> {
                Node rethrow = node("rethrow", "finally", null, json.range(fin));
                exceptionEdges(rethrow, outer, null);
                return buildList(fin.statements(), outer, rethrow);
            });
            finHandler = new Handler(rethrowEntry, "finally", null, true);
        }

        List<Handler> afterBody = finHandler != null ? List.of(finHandler) : outer.handlers();
        Ctx catchCtx = crossing(outer, fin, afterBody);

        List<Handler> handlers = new ArrayList<>();
        for (Object o : st.catchClauses()) {
            CatchClause cc = (CatchClause) o;
            Node body = buildList(cc.getBody().statements(), catchCtx, afterNormal);
            Node catchNode = node("catch", "catch", "catch (" + json.text(cc.getException()) + ")", json.range(cc.getException()));
            edge(catchNode, body, "next");
            Type type = cc.getException().getType();
            List<?> alternatives = type instanceof UnionType u ? u.types() : List.of(type);
            for (Object alt : alternatives) {
                handlers.add(handler(() -> catchNode, (Type) alt));
            }
        }
        handlers.addAll(afterBody);

        Ctx bodyCtx = crossing(outer, fin, handlers);
        Node entry = buildList(st.getBody().statements(), bodyCtx, afterNormal);
        for (int i = st.resources().size() - 1; i >= 0; i--) {
            Expression resource = (Expression) st.resources().get(i);
            entry = simple("resource", resource, resource, bodyCtx, entry);
        }
        return entry;
    }

    private Handler handler(Supplier<Node> node, Type type) {
        ITypeBinding b = type.resolveBinding();
        String name = b != null ? b.getQualifiedName() : json.text(type);
        boolean catchAll = name.equals("java.lang.Throwable") || name.equals("java.lang.Exception")
                || (b == null && (name.equals("Throwable") || name.equals("Exception")));
        return new Handler(node, json.text(type), catchAll ? null : b, catchAll);
    }

    /** Context for code inside a try: jumps out of it first run a clone of {@code fin}. */
    private Ctx crossing(Ctx c, Block fin, List<Handler> handlers) {
        if (fin == null) {
            return c.withHandlers(handlers);
        }
        Map<String, Supplier<Node>> breaks = new HashMap<>();
        c.breaks().forEach((k, v) -> breaks.put(k, through(fin, c, v)));
        Map<String, Supplier<Node>> continues = new HashMap<>();
        c.continues().forEach((k, v) -> continues.put(k, through(fin, c, v)));
        return new Ctx(through(fin, c, c.ret()), through(fin, c, c.brk()), through(fin, c, c.cont()),
                breaks, continues, handlers, c.labels());
    }

    private Supplier<Node> through(Block fin, Ctx outer, Supplier<Node> target) {
        return target == null ? null : memo(() -> buildList(fin.statements(), outer, target.get()));
    }

    private Ctx loop(Ctx c, Node next, Supplier<Node> continueTarget) {
        Map<String, Supplier<Node>> continues = new HashMap<>(c.continues());
        for (String label : c.labels()) {
            continues.put(label, continueTarget);
        }
        return new Ctx(c.ret(), () -> next, continueTarget, c.breaks(), continues, c.handlers(), List.of());
    }

    private static boolean isLoop(Statement s) {
        return s instanceof WhileStatement || s instanceof DoStatement
                || s instanceof ForStatement || s instanceof EnhancedForStatement;
    }

    // ---------- exceptions ----------

    /** Adds exception edges to a node that was just created, if it may throw inside a try. */
    private Node throwing(Node n, Ctx c, ASTNode scan) {
        if (!c.handlers().isEmpty() && mayThrow(scan)) {
            exceptionEdges(n, c, null);
        }
        return n;
    }

    /** Walks handlers innermost-first; stops at the first one that certainly catches. */
    private void exceptionEdges(Node from, Ctx c, ITypeBinding thrown) {
        for (Handler h : c.handlers()) {
            if (h.catchAll()) {
                edge(from, h.node().get(), "exception", h.label());
                return;
            }
            if (thrown != null && h.type() != null) {
                if (thrown.isSubTypeCompatible(h.type())) {
                    edge(from, h.node().get(), "exception", h.label());
                    return;
                }
                if (h.type().isSubTypeCompatible(thrown)) {
                    edge(from, h.node().get(), "exception", h.label());
                }
            } else {
                edge(from, h.node().get(), "exception", h.label());
            }
        }
        edge(from, exit, "exception", null);
    }

    private static boolean mayThrow(ASTNode scan) {
        if (scan == null) {
            return false;
        }
        boolean[] found = {false};
        scan.accept(new ASTVisitor() {
            @Override
            public boolean preVisit2(ASTNode n) {
                if (n instanceof LambdaExpression || n instanceof AnonymousClassDeclaration) {
                    return false;
                }
                if (n instanceof MethodInvocation || n instanceof SuperMethodInvocation
                        || n instanceof ClassInstanceCreation || n instanceof ArrayAccess
                        || n instanceof ArrayCreation || n instanceof CastExpression) {
                    found[0] = true;
                } else if (n instanceof InfixExpression ie
                        && (ie.getOperator() == InfixExpression.Operator.DIVIDE
                        || ie.getOperator() == InfixExpression.Operator.REMAINDER)) {
                    found[0] = true;
                } else if (n instanceof Assignment a
                        && (a.getOperator() == Assignment.Operator.DIVIDE_ASSIGN
                        || a.getOperator() == Assignment.Operator.REMAINDER_ASSIGN)) {
                    found[0] = true;
                }
                return !found[0];
            }
        });
        return found[0];
    }

    // ---------- graph plumbing ----------

    private Node node(String kind, String statementKind, ASTNode n) {
        return node(kind, statementKind, json.text(n), json.range(n));
    }

    private Node node(String kind, String statementKind, String text, Map<String, Object> range) {
        Node n = new Node(kind, statementKind, text, range);
        nodes.add(n);
        return n;
    }

    private void edge(Node from, Node to, String kind) {
        edge(from, to, kind, null);
    }

    private void edge(Node from, Node to, String kind, String label) {
        edges.add(new Edge(from, to, kind, label));
    }

    private static <T> Supplier<T> memo(Supplier<T> s) {
        return new Supplier<>() {
            private T value;
            private boolean done;

            @Override
            public T get() {
                if (!done) {
                    value = s.get();
                    done = true;
                }
                return value;
            }
        };
    }

    /**
     * Entry is n0, exit is n1, the rest follow in breadth-first order; unreachable nodes
     * (dead code, unused catch/finally clones) come last and are marked {@code reachable: false}.
     * An edge is a back edge when it closes a cycle in a depth-first walk from the entry.
     */
    private Map<String, Object> render(Node entry) {
        Map<Node, List<Edge>> out = new HashMap<>();
        for (Edge e : edges) {
            out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
        }

        Set<Edge> back = new HashSet<>();
        Map<Node, Integer> state = new HashMap<>();
        Map<Node, Integer> next = new HashMap<>();
        Deque<Node> stack = new ArrayDeque<>();
        state.put(entry, 1);
        next.put(entry, 0);
        stack.push(entry);
        while (!stack.isEmpty()) {
            Node n = stack.peek();
            List<Edge> list = out.getOrDefault(n, List.of());
            int i = next.get(n);
            if (i < list.size()) {
                next.put(n, i + 1);
                Edge e = list.get(i);
                Integer s = state.get(e.to());
                if (s == null) {
                    state.put(e.to(), 1);
                    next.put(e.to(), 0);
                    stack.push(e.to());
                } else if (s == 1) {
                    back.add(e);
                }
            } else {
                state.put(n, 2);
                stack.pop();
            }
        }

        List<Node> order = new ArrayList<>(List.of(entry, exit));
        Set<Node> seen = new HashSet<>(order);
        for (int i = 0; i < order.size(); i++) {
            if (i == 1) {
                continue;
            }
            for (Edge e : out.getOrDefault(order.get(i), List.of())) {
                if (seen.add(e.to())) {
                    order.add(e.to());
                }
            }
        }
        for (Node n : nodes) {
            if (seen.add(n)) {
                order.add(n);
            }
        }

        Map<Node, String> ids = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            ids.put(order.get(i), prefix + ".n" + i);
        }

        List<Object> nodeList = new ArrayList<>();
        List<Object> edgeList = new ArrayList<>();
        for (Node n : order) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ids.get(n));
            m.put("kind", n.kind);
            if (n.statementKind != null) {
                m.put("statement_kind", n.statementKind);
            }
            if (n.text != null) {
                m.put("text", n.text);
            }
            m.put("source_range", n.range);
            if (!state.containsKey(n)) {
                m.put("reachable", false);
            }
            nodeList.add(m);
            for (Edge e : out.getOrDefault(n, List.of())) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("from", ids.get(e.from()));
                em.put("to", ids.get(e.to()));
                em.put("kind", e.kind());
                if (e.label() != null) {
                    em.put("label", e.label());
                }
                if (back.contains(e)) {
                    em.put("back", true);
                }
                edgeList.add(em);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method_id", methodId);
        result.put("entry", ids.get(entry));
        result.put("exit", ids.get(exit));
        result.put("nodes", nodeList);
        result.put("edges", edgeList);
        return result;
    }
}
