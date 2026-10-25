package org.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jdt.core.dom.*;

/**
 * Conservative time-complexity estimate and repeated-scan detection for one method.
 *
 * The cost of a statement is a sum of monomials over "size variables" (the element count of a
 * collection, or the value of an int variable used as a loop bound), each with a polynomial power
 * and a log power. Sequential code adds costs (the dominant term wins), a loop multiplies the
 * per-iteration cost by its iteration factor. Anything that cannot be bounded from the source
 * (unknown loop shape, call without a known cost model) makes the result {@code unknown} instead
 * of guessing. Only the method's own AST is inspected, plus one narrow exception: a call to a method
 * of the same file counts as O(1) when that method is itself proven constant by this analyzer (see
 * {@link #userMethodCost}). There is no general call graph and no data flow.
 */
final class ComplexityAnalyzer {
    // ---------- cost model ----------

    /** Product of size variables: key -> {power, logPower}. The empty map is the constant 1. */
    private record Mono(TreeMap<String, int[]> vars) {
        Mono times(String key, boolean log) {
            TreeMap<String, int[]> copy = new TreeMap<>();
            vars.forEach((k, v) -> copy.put(k, v.clone()));
            copy.computeIfAbsent(key, k -> new int[2])[log ? 1 : 0]++;
            return new Mono(copy);
        }

        /** True when this is at least as large as {@code o} for every variable (so {@code o} is redundant). */
        boolean dominates(Mono o) {
            for (Map.Entry<String, int[]> e : o.vars.entrySet()) {
                int[] mine = vars.get(e.getKey());
                int[] theirs = e.getValue();
                if (mine == null || mine[0] < theirs[0] || (mine[0] == theirs[0] && mine[1] < theirs[1])) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class Cost {
        final List<Mono> monos = new ArrayList<>();
        final Set<String> unknown = new LinkedHashSet<>();
        final Set<String> notes = new LinkedHashSet<>();
        boolean weak;

        static Cost constant() {
            Cost c = new Cost();
            c.monos.add(new Mono(new TreeMap<>()));
            return c;
        }

        static Cost constant(String note) {
            Cost c = constant();
            c.notes.add(note);
            c.weak = true;
            return c;
        }

        static Cost unknown(String reason) {
            Cost c = constant();
            c.unknown.add(reason);
            return c;
        }

        static Cost linear(String key, boolean log) {
            Cost c = new Cost();
            c.monos.add(new Mono(new TreeMap<>()).times(key, false));
            if (log) {
                c.monos.set(0, c.monos.get(0).times(key, true));
            }
            return c;
        }

        Cost plus(Cost o) {
            Cost r = new Cost();
            List<Mono> all = new ArrayList<>(monos);
            all.addAll(o.monos);
            for (int i = 0; i < all.size(); i++) {
                boolean redundant = false;
                for (int j = 0; j < all.size() && !redundant; j++) {
                    // drop a term that another term covers; of two equal terms keep the first
                    redundant = j != i && all.get(j).dominates(all.get(i)) && (!all.get(i).dominates(all.get(j)) || j < i);
                }
                if (!redundant) {
                    r.monos.add(all.get(i));
                }
            }
            r.unknown.addAll(unknown);
            r.unknown.addAll(o.unknown);
            r.notes.addAll(notes);
            r.notes.addAll(o.notes);
            r.weak = weak || o.weak;
            return r;
        }

        Cost times(Factor f) {
            Cost r = new Cost();
            r.unknown.addAll(unknown);
            r.notes.addAll(notes);
            r.weak = weak || f.weak;
            switch (f.kind) {
                case CONST -> {
                    r.monos.addAll(monos);
                    r.notes.add("Loops with constant bounds are counted as O(1), regardless of the constant's size.");
                }
                case UNKNOWN -> {
                    r.monos.addAll(monos);
                    r.unknown.add(f.reason);
                }
                default -> {
                    for (Mono m : monos) {
                        r.monos.add(m.times(f.key, f.kind == Kind.LOG));
                    }
                }
            }
            return r;
        }
    }

    private enum Kind { CONST, LINEAR, LOG, UNKNOWN }

    /** How many times a loop body runs. {@code collection}: derived from a collection's size (vs. a plain int). */
    private record Factor(Kind kind, String key, boolean collection, boolean viaEnv, boolean weak, String reason,
                          IVariableBinding counter) {
        static Factor constant() {
            return new Factor(Kind.CONST, null, false, false, false, null, null);
        }

        static Factor unknown(String reason) {
            return new Factor(Kind.UNKNOWN, null, false, false, true, reason, null);
        }
    }

    /** A recognised loop bound. */
    private record Bound(Kind kind, String key, boolean collection, boolean viaEnv, String reason) {
        static final Bound CONST = new Bound(Kind.CONST, null, false, false, null);

        static Bound unknown(String reason) {
            return new Bound(Kind.UNKNOWN, null, false, false, reason);
        }
    }

    private record LoopInfo(Statement stmt, Factor factor, Set<String> derived, List<String> nodeIds) {
    }

    private enum Step { ADD, MUL, DIV, OTHER }

    private record Update(Step step, long amount) {
    }

    // ---------- state ----------

    private static final String[] SYMBOLS = {"n", "m", "k", "p", "q", "r", "s", "t"};
    private static final Set<String> MUTATORS = Set.of("add", "addAll", "addFirst", "addLast", "offer", "push", "put",
            "putAll", "remove", "removeAll", "removeIf", "clear", "set", "sort", "poll", "pop", "removeFirst", "removeLast",
            "retainAll", "putIfAbsent", "merge", "compute", "computeIfAbsent");
    private static final String ASSUME_RANDOM_ACCESS =
            "List.get/set/add are treated as O(1), assuming a random-access list such as ArrayList.";
    private static final String ASSUME_HASH =
            "Hash-based Map/Set operations are treated as O(1) on average.";
    private static final String ASSUME_BINARY_SEARCH =
            "A midpoint search loop halves the interval [lo, hi] each iteration (assuming no int overflow of lo + hi).";
    private static final String ASSUME_EUCLID =
            "Euclid's algorithm: the remainder at least halves every two iterations, so the loop runs O(log(min(a, b))) times.";
    private static final String ASSUME_STRING = "String/StringBuilder operations are treated as O(1) (short strings).";
    private static final String ASSUME_INPUT = "A loop that reads standard input on every iteration runs once per input token or line; "
            + "the input size is the bound, and reading one token or line is treated as O(1) (short lines).";
    private static final String INPUT_KEY = "input";

    private final AstJson json;
    private final CompilationUnit cu;
    private final MethodDeclaration method;
    private final Map<String, Object> cfg;
    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final List<LoopInfo> stack = new ArrayList<>();
    private final List<Object> findings = new ArrayList<>();
    private final List<Object> fragments = new ArrayList<>();
    /** Methods being analyzed right now (this one plus the callees being summarized): recursion guard. */
    private final Set<MethodDeclaration> active;
    /** Keys of fields that called same-file methods may write (only filled while summarizing callees). */
    private final Set<String> writes = new LinkedHashSet<>();
    /** The summarized callee may change state reachable from its arguments, so its cost is not trusted. */
    private boolean impure;
    /** Only the analyzer of the requested method detects direct recursion; callee summaries stay conservative. */
    private boolean root;
    private final List<MethodInvocation> recursiveCalls = new ArrayList<>();

    ComplexityAnalyzer(AstJson json, CompilationUnit cu, MethodDeclaration method, Map<String, Object> cfg) {
        this(json, cu, method, cfg, new LinkedHashSet<>());
        active.add(method);
        root = true;
    }

    private ComplexityAnalyzer(AstJson json, CompilationUnit cu, MethodDeclaration method, Map<String, Object> cfg,
                               Set<MethodDeclaration> active) {
        this.json = json;
        this.cu = cu;
        this.method = method;
        this.cfg = cfg;
        this.active = active;
    }

    Map<String, Object> analyze() {
        Cost total = block(method.getBody().statements(), Map.of());
        String bound = writtenLoopBound();
        if (bound != null) {
            total = total.plus(Cost.unknown("A called method of this file may modify '" + bound
                    + "', which a loop bound of this method depends on."));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> complexity = recursiveCalls.isEmpty() ? render(total) : recursion(total);
        complexity.put("engine", ENGINE);
        complexity.put("limitations", LIMITATIONS);
        out.put("complexity", complexity);
        out.put("fragments", fragments);
        if (root) {
            new CodeSmells(json, cu, method, findings).detect();
        }
        out.put("findings", findings);
        return out;
    }

    static final String ENGINE = "jdt-static-cost-model/2";
    static final List<String> LIMITATIONS = List.of(
            "Static upper-bound estimate from the source; no execution, no profiling.",
            "Loop bounds come from recognised counter, collection-size and midpoint-search patterns; anything else is unknown.",
            "A callee is trusted only when it is proven O(1); a callee's cost as a function of its arguments is not tracked.",
            "Standard-library costs assume typical implementations (see assumptions); user-defined generics and streams are unknown.",
            "Space complexity is not estimated.");

    // ---------- statements ----------

    private Cost block(List<?> statements, Map<String, String> env) {
        Cost c = Cost.constant();
        for (Object o : statements) {
            c = c.plus(stmt((Statement) o, env));
        }
        return c;
    }

    private Cost stmt(Statement s, Map<String, String> env) {
        switch (s) {
            case Block b -> {
                return block(b.statements(), env);
            }
            case ExpressionStatement st -> {
                return expr(st.getExpression());
            }
            case VariableDeclarationStatement st -> {
                Cost c = Cost.constant();
                for (Object f : st.fragments()) {
                    Expression init = ((VariableDeclarationFragment) f).getInitializer();
                    if (init != null) {
                        c = c.plus(expr(init));
                    }
                }
                return c;
            }
            case ReturnStatement st -> {
                return st.getExpression() == null ? Cost.constant() : expr(st.getExpression());
            }
            case ThrowStatement st -> {
                return expr(st.getExpression());
            }
            case AssertStatement st -> {
                return expr(st.getExpression());
            }
            case IfStatement st -> {
                Cost c = expr(st.getExpression()).plus(stmt(st.getThenStatement(), env));
                return st.getElseStatement() == null ? c : c.plus(stmt(st.getElseStatement(), env));
            }
            case LabeledStatement st -> {
                return stmt(st.getBody(), env);
            }
            case SynchronizedStatement st -> {
                return expr(st.getExpression()).plus(stmt(st.getBody(), env));
            }
            case SwitchStatement st -> {
                Cost c = expr(st.getExpression());
                for (Object o : st.statements()) {
                    if (o instanceof Statement inner) {
                        c = c.plus(stmt(inner, env));
                    }
                }
                return c;
            }
            case TryStatement st -> {
                Cost c = Cost.constant();
                for (Object r : st.resources()) {
                    c = c.plus(expr((ASTNode) r));
                }
                c = c.plus(stmt(st.getBody(), env));
                for (Object o : st.catchClauses()) {
                    c = c.plus(stmt(((CatchClause) o).getBody(), env));
                }
                return st.getFinally() == null ? c : c.plus(stmt(st.getFinally(), env));
            }
            case ForStatement st -> {
                return loop(st, env);
            }
            case EnhancedForStatement st -> {
                return loop(st, env);
            }
            case WhileStatement st -> {
                return loop(st, env);
            }
            case DoStatement st -> {
                return loop(st, env);
            }
            default -> {
                return Cost.constant();
            }
        }
    }

    private Cost loop(Statement s, Map<String, String> env) {
        Cost init = Cost.constant();
        Cost perIteration;
        Statement body;
        Factor factor;
        Set<String> derived = new LinkedHashSet<>();
        String cfgKind;
        int cfgStart;
        String loopNote = null;

        switch (s) {
            case EnhancedForStatement st -> {
                body = st.getBody();
                cfgKind = "foreach";
                cfgStart = st.getParameter().getStartPosition();
                factor = foreachFactor(st);
                init = expr(st.getExpression());
                perIteration = Cost.constant();
                derived.add(st.getParameter().resolveBinding() == null ? "" : st.getParameter().resolveBinding().getKey());
            }
            case ForStatement st -> {
                body = st.getBody();
                cfgKind = "for";
                cfgStart = st.getExpression() != null ? st.getExpression().getStartPosition() : st.getStartPosition();
                for (Object o : st.initializers()) {
                    init = init.plus(expr((ASTNode) o));
                }
                factor = st.getExpression() == null
                        ? Factor.unknown("for loop has no condition")
                        : counterFactor(st.getExpression(), st, env);
                perIteration = st.getExpression() == null ? Cost.constant() : expr(st.getExpression());
                for (Object o : st.updaters()) {
                    perIteration = perIteration.plus(expr((ASTNode) o));
                }
                if (factor.counter() != null) {
                    derived.add(factor.counter().getKey());
                }
            }
            case WhileStatement st -> {
                body = st.getBody();
                cfgKind = "while";
                cfgStart = st.getExpression().getStartPosition();
                factor = counterFactor(st.getExpression(), st, env);
                if (factor.kind() == Kind.UNKNOWN) {
                    Factor halving = binarySearchFactor(st, env);
                    if (halving != null) {
                        factor = halving;
                        loopNote = ASSUME_BINARY_SEARCH;
                    } else {
                        Factor euclid = euclidFactor(st);
                        if (euclid != null) {
                            factor = euclid;
                            loopNote = ASSUME_EUCLID;
                        } else if (readsInput(st.getExpression(), st.getBody())) {
                            factor = inputFactor();
                            loopNote = ASSUME_INPUT;
                        }
                    }
                }
                perIteration = expr(st.getExpression());
                if (factor.counter() != null) {
                    derived.add(factor.counter().getKey());
                }
            }
            default -> {
                DoStatement st = (DoStatement) s;
                body = st.getBody();
                cfgKind = "do_while";
                cfgStart = st.getExpression().getStartPosition();
                factor = counterFactor(st.getExpression(), st, env);
                if (factor.kind() == Kind.UNKNOWN && readsInput(st.getExpression(), st.getBody())) {
                    factor = inputFactor();
                    loopNote = ASSUME_INPUT;
                }
                perIteration = expr(st.getExpression());
                if (factor.counter() != null) {
                    derived.add(factor.counter().getKey());
                }
            }
        }

        List<String> nodeIds = new ArrayList<>();
        String id = cfgNodeId(cfgKind, cfgStart);
        if (id != null) {
            nodeIds.add(id);
        }
        LoopInfo info = new LoopInfo(s, factor, derived, nodeIds);

        if (!stack.isEmpty()) {
            detectFindings(info);
        }

        Map<String, String> inner = env;
        if (factor.counter() != null && factor.key() != null) {
            inner = new HashMap<>(env);
            inner.put(factor.counter().getKey(), factor.key());
        }
        stack.add(info);
        perIteration = perIteration.plus(stmt(body, inner));
        stack.remove(stack.size() - 1);

        Cost total = init.plus(perIteration.times(factor));
        if (loopNote != null) {
            total.notes.add(loopNote);
        }
        if (detectStringConcatenation(info, body)) {
            total.notes.add(STRING_CONCAT_NOTE);
            total.weak = true;
        }
        if (stack.isEmpty()) {
            fragments.add(fragment(info, total));
        }
        return total;
    }

    private Map<String, Object> fragment(LoopInfo info, Cost cost) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("kind", "loop");
        f.put("statement_kind", switch (info.stmt()) {
            case ForStatement x -> "for";
            case EnhancedForStatement x -> "foreach";
            case WhileStatement x -> "while";
            default -> "do_while";
        });
        f.put("source_range", json.range(info.stmt()));
        f.put("node_ids", info.nodeIds());
        f.putAll(render(cost));
        return f;
    }

    // ---------- loop factors ----------

    private Factor foreachFactor(EnhancedForStatement st) {
        Expression it = strip(st.getExpression());
        if (isConstantIterable(it)) {
            return Factor.constant();
        }
        String text = norm(json.text(it));
        if (it instanceof MethodInvocation mi && mi.arguments().isEmpty() && mi.getExpression() != null
                && Set.of("keySet", "values", "entrySet").contains(mi.getName().getIdentifier())) {
            text = norm(json.text(mi.getExpression()));
        }
        String key = "coll:" + text;
        descriptions.putIfAbsent(key, "number of elements in " + text);
        return new Factor(Kind.LINEAR, key, true, false, false, null, null);
    }

    private boolean isConstantIterable(Expression it) {
        if (it instanceof ArrayCreation ac) {
            return ac.getInitializer() != null;
        }
        if (it instanceof ArrayInitializer) {
            return true;
        }
        if (it instanceof MethodInvocation mi) {
            IMethodBinding b = mi.resolveMethodBinding();
            String decl = b == null ? "" : b.getDeclaringClass().getErasure().getQualifiedName();
            String name = mi.getName().getIdentifier();
            boolean literalFactory = (decl.equals("java.util.List") || decl.equals("java.util.Set")) && name.equals("of")
                    || decl.equals("java.util.Arrays") && name.equals("asList");
            if (literalFactory) {
                for (Object a : mi.arguments()) {
                    if (((Expression) a).resolveConstantExpressionValue() == null) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Counter-style loops: {@code i < n; i++}, {@code i > 0; i--}, {@code i < n; i *= 2},
     * {@code n > 1; n /= 2}. The condition may be a conjunction; any conjunct that fits is enough
     * because extra conjuncts can only stop the loop earlier.
     */
    private Factor counterFactor(Expression cond, Statement loop, Map<String, String> env) {
        List<Expression> conjuncts = new ArrayList<>();
        flatten(cond, conjuncts);
        String reason = "Loop condition '" + norm(json.text(cond)) + "' is not a counter compared with a bound.";
        for (Expression c : conjuncts) {
            if (!(c instanceof InfixExpression ie) || ie.hasExtendedOperands()) {
                continue;
            }
            InfixExpression.Operator op = ie.getOperator();
            if (op != InfixExpression.Operator.LESS && op != InfixExpression.Operator.LESS_EQUALS
                    && op != InfixExpression.Operator.GREATER && op != InfixExpression.Operator.GREATER_EQUALS) {
                continue;
            }
            Expression l = strip(ie.getLeftOperand());
            Expression r = strip(ie.getRightOperand());
            IVariableBinding v;
            Expression limit;
            boolean up;
            boolean strictOrNot = op == InfixExpression.Operator.LESS || op == InfixExpression.Operator.GREATER;
            if (counterVar(l) != null) {
                v = counterVar(l);
                limit = r;
                up = op == InfixExpression.Operator.LESS || op == InfixExpression.Operator.LESS_EQUALS;
            } else if (counterVar(r) != null) {
                v = counterVar(r);
                limit = l;
                up = op == InfixExpression.Operator.GREATER || op == InfixExpression.Operator.GREATER_EQUALS;
            } else {
                continue;
            }
            Factor f = tryCounter(v, limit, up, strictOrNot, loop, env);
            if (f.kind() != Kind.UNKNOWN) {
                return f;
            }
            reason = f.reason();
        }
        return Factor.unknown(reason);
    }

    private static IVariableBinding counterVar(Expression e) {
        if (e instanceof SimpleName n && n.resolveBinding() instanceof IVariableBinding vb && !vb.isField()) {
            return vb;
        }
        return null;
    }

    private Factor tryCounter(IVariableBinding v, Expression limit, boolean up, boolean strict, Statement loop,
                              Map<String, String> env) {
        String name = v.getName();
        Statement body;
        List<?> updaters = List.of();
        Expression initExpr = null;
        boolean isFor = loop instanceof ForStatement;
        switch (loop) {
            case ForStatement st -> {
                body = st.getBody();
                updaters = st.updaters();
                initExpr = initOf(st, v);
            }
            case WhileStatement st -> body = st.getBody();
            default -> body = ((DoStatement) loop).getBody();
        }

        // locate the single update of the counter
        List<Expression> candidates = new ArrayList<>();
        if (isFor) {
            for (Object o : updaters) {
                candidates.add((Expression) o);
            }
        } else {
            List<?> top = body instanceof Block b ? b.statements() : List.of(body);
            for (Object o : top) {
                if (o instanceof ExpressionStatement es) {
                    candidates.add(es.getExpression());
                }
            }
        }
        Update update = null;
        int touching = 0;
        for (Expression e : candidates) {
            if (countMods(e, v) > 0) {
                touching++;
                update = parseUpdate(e, v);
            }
        }
        if (touching != 1 || update == null || update.step() == Step.OTHER) {
            return Factor.unknown("Counter '" + name + "' is not updated by a simple step exactly once per iteration.");
        }
        int bodyMods = countMods(body, v);
        if (isFor ? bodyMods != 0 : bodyMods != 1) {
            return Factor.unknown("Counter '" + name + "' is also modified elsewhere in the loop body.");
        }
        if (!isFor && hasContinue(body)) {
            return Factor.unknown("Loop body contains 'continue', which may skip the update of '" + name + "'.");
        }

        boolean increasing = update.step() == Step.MUL || (update.step() == Step.ADD && update.amount() > 0);
        if (update.step() == Step.ADD && update.amount() == 0 || update.step() != Step.ADD && update.amount() < 2) {
            return Factor.unknown("Counter '" + name + "' does not change by a positive step.");
        }
        if (increasing != up) {
            return Factor.unknown("Counter '" + name + "' moves away from its bound, so termination is not guaranteed.");
        }

        Bound bound;
        boolean log = update.step() != Step.ADD;
        if (up) {
            if (log) {
                Object start = initExpr == null ? null : initExpr.resolveConstantExpressionValue();
                if (!(start instanceof Number num) || num.longValue() < 1) {
                    return Factor.unknown("Multiplying '" + name + "' only terminates if its start value is a constant >= 1.");
                }
            }
            bound = classify(limit, env);
            if (bound.kind() != Kind.UNKNOWN && mutatedInLoop(limit, loop)) {
                return Factor.unknown("The bound '" + norm(json.text(limit)) + "' can change while the loop runs.");
            }
        } else {
            if (log) {
                Object lim = limit.resolveConstantExpressionValue();
                boolean ok = lim instanceof Number n
                        && (strict ? n.longValue() >= 0 : n.longValue() >= 1);
                if (!ok) {
                    return Factor.unknown("Dividing '" + name + "' only terminates against a constant limit (> 0).");
                }
            }
            if (isFor) {
                bound = initExpr == null ? Bound.unknown("Start value of '" + name + "' is not visible in the for header.")
                        : classify(initExpr, env);
            } else {
                String key = "var:" + v.getKey();
                descriptions.putIfAbsent(key, "initial value of " + name);
                bound = new Bound(Kind.LINEAR, key, false, false, null);
            }
        }

        switch (bound.kind()) {
            case UNKNOWN -> {
                return Factor.unknown(bound.reason());
            }
            case CONST -> {
                return new Factor(Kind.CONST, null, false, false, false, null, v);
            }
            default -> {
                return new Factor(log ? Kind.LOG : Kind.LINEAR, bound.key(), bound.collection(), bound.viaEnv(),
                        !bound.collection(), null, v);
            }
        }
    }

    private static Expression initOf(ForStatement st, IVariableBinding v) {
        for (Object o : st.initializers()) {
            if (o instanceof VariableDeclarationExpression vde) {
                for (Object f : vde.fragments()) {
                    VariableDeclarationFragment frag = (VariableDeclarationFragment) f;
                    if (v.isEqualTo(frag.resolveBinding())) {
                        return frag.getInitializer();
                    }
                }
            } else if (o instanceof Assignment a && a.getOperator() == Assignment.Operator.ASSIGN
                    && strip(a.getLeftHandSide()) instanceof SimpleName n && v.isEqualTo(n.resolveBinding())) {
                return a.getRightHandSide();
            }
        }
        return null;
    }

    private Update parseUpdate(Expression e, IVariableBinding v) {
        e = strip(e);
        if (e instanceof PostfixExpression pe && isVar(pe.getOperand(), v)) {
            return new Update(Step.ADD, pe.getOperator() == PostfixExpression.Operator.INCREMENT ? 1 : -1);
        }
        if (e instanceof PrefixExpression pe && isVar(pe.getOperand(), v)
                && (pe.getOperator() == PrefixExpression.Operator.INCREMENT
                || pe.getOperator() == PrefixExpression.Operator.DECREMENT)) {
            return new Update(Step.ADD, pe.getOperator() == PrefixExpression.Operator.INCREMENT ? 1 : -1);
        }
        if (e instanceof Assignment a && isVar(a.getLeftHandSide(), v)) {
            Long c = constLong(a.getRightHandSide());
            Assignment.Operator op = a.getOperator();
            if (op == Assignment.Operator.ASSIGN) {
                if (strip(a.getRightHandSide()) instanceof InfixExpression ie && !ie.hasExtendedOperands()) {
                    Expression l = strip(ie.getLeftOperand());
                    Expression r = strip(ie.getRightOperand());
                    InfixExpression.Operator io = ie.getOperator();
                    if (isVar(l, v) && constLong(r) != null) {
                        long k = constLong(r);
                        if (io == InfixExpression.Operator.PLUS) return new Update(Step.ADD, k);
                        if (io == InfixExpression.Operator.MINUS) return new Update(Step.ADD, -k);
                        if (io == InfixExpression.Operator.TIMES) return new Update(Step.MUL, k);
                        if (io == InfixExpression.Operator.DIVIDE) return new Update(Step.DIV, k);
                    } else if (isVar(r, v) && constLong(l) != null) {
                        long k = constLong(l);
                        if (io == InfixExpression.Operator.PLUS) return new Update(Step.ADD, k);
                        if (io == InfixExpression.Operator.TIMES) return new Update(Step.MUL, k);
                    }
                }
                return new Update(Step.OTHER, 0);
            }
            if (c == null) {
                return new Update(Step.OTHER, 0);
            }
            if (op == Assignment.Operator.PLUS_ASSIGN) return new Update(Step.ADD, c);
            if (op == Assignment.Operator.MINUS_ASSIGN) return new Update(Step.ADD, -c);
            if (op == Assignment.Operator.TIMES_ASSIGN) return new Update(Step.MUL, c);
            if (op == Assignment.Operator.DIVIDE_ASSIGN) return new Update(Step.DIV, c);
            if (op == Assignment.Operator.LEFT_SHIFT_ASSIGN && c >= 1 && c < 31) return new Update(Step.MUL, 1L << c);
            if ((op == Assignment.Operator.RIGHT_SHIFT_SIGNED_ASSIGN || op == Assignment.Operator.RIGHT_SHIFT_UNSIGNED_ASSIGN)
                    && c >= 1 && c < 31) return new Update(Step.DIV, 1L << c);
        }
        return new Update(Step.OTHER, 0);
    }

    private static Long constLong(Expression e) {
        Object v = e.resolveConstantExpressionValue();
        return v instanceof Number n && !(v instanceof Double) && !(v instanceof Float) ? Long.valueOf(n.longValue()) : null;
    }

    private static boolean isVar(Expression e, IVariableBinding v) {
        return strip(e) instanceof SimpleName n && v.isEqualTo(n.resolveBinding());
    }


    // ---------- binary search ----------

    /**
     * {@code while (lo <= hi)} (or {@code <}) over two local ints where every iteration computes a midpoint of
     * [lo, hi] and every path through the body either leaves the loop or sets {@code lo = mid + 1} /
     * {@code hi = mid - 1}. The interval then at least halves, so the loop runs O(log(hi - lo + 1)) times, bounded
     * by the size {@code hi} starts from. Returns {@code null} when any of this cannot be shown.
     */
    private Factor binarySearchFactor(WhileStatement st, Map<String, String> env) {
        if (!(strip(st.getExpression()) instanceof InfixExpression cond) || cond.hasExtendedOperands()
                || cond.getOperator() != InfixExpression.Operator.LESS_EQUALS
                        && cond.getOperator() != InfixExpression.Operator.LESS) {
            return null;
        }
        IVariableBinding lo = counterVar(strip(cond.getLeftOperand()));
        IVariableBinding hi = counterVar(strip(cond.getRightOperand()));
        if (lo == null || hi == null || lo.isEqualTo(hi) || !isIntegral(lo.getType()) || !isIntegral(hi.getType())
                || hasContinue(st.getBody())) {
            return null;
        }
        // lo starts at a constant >= 0 and hi at a recognised size; neither changes before the loop
        Expression loInit = declaredInit(lo);
        Expression hiInit = declaredInit(hi);
        if (loInit == null || hiInit == null || !(constLong(strip(loInit)) instanceof Long start) || start < 0
                || countMods(method.getBody(), lo) != countMods(st, lo)
                || countMods(method.getBody(), hi) != countMods(st, hi)) {
            return null;
        }
        Bound bound = classify(hiInit, env);
        if (bound.kind() != Kind.LINEAR) {
            return null;
        }
        List<?> statements = st.getBody() instanceof Block b ? b.statements() : List.of(st.getBody());
        IVariableBinding mid = null;
        for (Object o : statements) {
            if (o instanceof VariableDeclarationStatement vds && vds.fragments().size() == 1) {
                VariableDeclarationFragment f = (VariableDeclarationFragment) vds.fragments().get(0);
                if (f.getInitializer() != null && f.resolveBinding() != null
                        && isMidpoint(f.getInitializer(), lo, hi)) {
                    mid = f.resolveBinding();
                    break;
                }
            }
        }
        if (mid == null || countMods(st.getBody(), mid) != 0 || !onlyMidpointUpdates(st.getBody(), lo, hi, mid)
                || !progresses(st.getBody(), lo, hi)) {
            return null;
        }
        return new Factor(Kind.LOG, bound.key(), bound.collection(), bound.viaEnv(), true, null, null);
    }

    /**
     * {@code while (b != 0) { t = a % b; a = b; b = t; }} (or {@code t = b; b = a % b; a = t;}): Euclid's algorithm on two
     * integral locals. Nothing else may touch them in the loop, so the shape is exactly the textbook one.
     */
    private Factor inputFactor() {
        descriptions.putIfAbsent(INPUT_KEY, "number of input tokens or lines read from standard input");
        return new Factor(Kind.LINEAR, INPUT_KEY, false, false, true, null, null);
    }

    /**
     * The loop consumes input on every iteration: its condition asks a Scanner for more input
     * ({@code while (in.hasNext...())}), compares {@code readLine()} with null, or (for {@code while (true)}) the
     * body reads a token or line as one of its own top-level statements.
     */
    private static boolean readsInput(Expression condition, Statement body) {
        Expression c = strip(condition);
        if (c instanceof MethodInvocation mi && isInputRead(mi, "hasNext")) {
            return true;
        }
        if (c instanceof InfixExpression ie && ie.getOperator() == InfixExpression.Operator.NOT_EQUALS
                && strip(ie.getRightOperand()) instanceof NullLiteral) {
            Expression left = strip(ie.getLeftOperand());
            if (left instanceof Assignment a) {
                left = strip(a.getRightHandSide());
            }
            return left instanceof MethodInvocation mi && isInputRead(mi, "readLine");
        }
        // any other condition (while (true), while (running)): every iteration must read before it can 'continue'
        List<?> top = body instanceof Block blk ? blk.statements() : List.of(body);
        for (Object o : top) {
            boolean[] reads = {false};
            boolean[] skips = {false};
            ((Statement) o).accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodInvocation mi) {
                    reads[0] |= isInputRead(mi, "next") || isInputRead(mi, "readLine");
                    return true;
                }

                @Override
                public boolean visit(Block nested) {
                    return false;
                }

                @Override
                public boolean visit(IfStatement nested) {
                    nested.getExpression().accept(this);
                    return false;
                }

                @Override
                public boolean visit(SwitchStatement nested) {
                    nested.getExpression().accept(this);
                    return false;
                }
            });
            ((Statement) o).accept(new ASTVisitor() {
                @Override
                public boolean visit(ContinueStatement n) {
                    skips[0] |= n.getLabel() == null;
                    return false;
                }

                @Override
                public boolean visit(ForStatement n) {
                    return false;
                }

                @Override
                public boolean visit(EnhancedForStatement n) {
                    return false;
                }

                @Override
                public boolean visit(WhileStatement n) {
                    return false;
                }

                @Override
                public boolean visit(DoStatement n) {
                    return false;
                }
            });
            if (reads[0] && !skips[0]) {
                return true;
            }
            if (skips[0] || o instanceof ReturnStatement || o instanceof BreakStatement) {
                return false;
            }
        }
        return false;
    }

    private static boolean isInputRead(MethodInvocation mi, String prefix) {
        IMethodBinding b = mi.resolveMethodBinding();
        if (b == null || !b.getName().startsWith(prefix)) {
            return false;
        }
        String owner = b.getDeclaringClass().getErasure().getQualifiedName();
        return owner.equals("java.util.Scanner") || owner.equals("java.io.BufferedReader");
    }

    private Factor euclidFactor(WhileStatement st) {
        if (!(strip(st.getExpression()) instanceof InfixExpression cond) || cond.hasExtendedOperands()
                || cond.getOperator() != InfixExpression.Operator.NOT_EQUALS
                        && cond.getOperator() != InfixExpression.Operator.GREATER
                || !(constLong(strip(cond.getRightOperand())) instanceof Long zero) || zero != 0) {
            return null;
        }
        IVariableBinding b = counterVar(strip(cond.getLeftOperand()));
        if (b == null || !isIntegral(b.getType()) || hasContinue(st.getBody())) {
            return null;
        }
        List<?> body = st.getBody() instanceof Block blk ? blk.statements() : List.of(st.getBody());
        if (body.size() != 3) {
            return null;
        }
        // each statement as "target = value" (a declaration with initializer counts)
        IVariableBinding[] target = new IVariableBinding[3];
        Expression[] value = new Expression[3];
        for (int i = 0; i < 3; i++) {
            if (body.get(i) instanceof VariableDeclarationStatement vds && vds.fragments().size() == 1) {
                VariableDeclarationFragment f = (VariableDeclarationFragment) vds.fragments().get(0);
                target[i] = f.resolveBinding();
                value[i] = f.getInitializer() == null ? null : strip(f.getInitializer());
            } else if (body.get(i) instanceof ExpressionStatement es && es.getExpression() instanceof Assignment a
                    && a.getOperator() == Assignment.Operator.ASSIGN
                    && strip(a.getLeftHandSide()) instanceof SimpleName n && n.resolveBinding() instanceof IVariableBinding vb) {
                target[i] = vb;
                value[i] = strip(a.getRightHandSide());
            } else {
                return null;
            }
            if (target[i] == null || value[i] == null || target[i].isField() || !isIntegral(target[i].getType())) {
                return null;
            }
        }
        boolean ok = false;
        // form 1: t = a % b; a = b; b = t
        if (isRemainderBy(value[0], b) && value[1] instanceof SimpleName v1 && b.isEqualTo(v1.resolveBinding())
                && target[2].isEqualTo(b) && value[2] instanceof SimpleName v2 && target[0].isEqualTo(v2.resolveBinding())
                && !target[0].isEqualTo(b) && !target[1].isEqualTo(b) && !target[1].isEqualTo(target[0])
                && target[1].isEqualTo(remainderDividend(value[0]))) {
            ok = true;
        }
        // form 2: t = b; b = a % b; a = t
        if (value[0] instanceof SimpleName w0 && b.isEqualTo(w0.resolveBinding()) && target[1].isEqualTo(b)
                && isRemainderBy(value[1], b) && value[2] instanceof SimpleName w2 && target[0].isEqualTo(w2.resolveBinding())
                && !target[0].isEqualTo(b) && !target[2].isEqualTo(b) && !target[2].isEqualTo(target[0])
                && target[2].isEqualTo(remainderDividend(value[1]))) {
            ok = true;
        }
        if (!ok) {
            return null;
        }
        String key = "var:" + b.getKey();
        descriptions.putIfAbsent(key, "initial value of " + b.getName());
        return new Factor(Kind.LOG, key, false, false, true, null, null);
    }

    private static boolean isRemainderBy(Expression e, IVariableBinding divisor) {
        return strip(e) instanceof InfixExpression ie && !ie.hasExtendedOperands()
                && ie.getOperator() == InfixExpression.Operator.REMAINDER
                && strip(ie.getLeftOperand()) instanceof SimpleName
                && strip(ie.getRightOperand()) instanceof SimpleName r && divisor.isEqualTo(r.resolveBinding());
    }

    private static IVariableBinding remainderDividend(Expression e) {
        SimpleName left = (SimpleName) strip(((InfixExpression) strip(e)).getLeftOperand());
        return left.resolveBinding() instanceof IVariableBinding vb ? vb : null;
    }

    private Expression declaredInit(IVariableBinding v) {
        Expression[] init = {null};
        method.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment f) {
                if (v.isEqualTo(f.resolveBinding())) {
                    init[0] = f.getInitializer();
                }
                return true;
            }
        });
        return init[0];
    }

    /** {@code (lo + hi) / 2}, {@code (lo + hi) >>> 1}, {@code lo + (hi - lo) / 2}, {@code lo + ((hi - lo) >> 1)}. */
    private static boolean isMidpoint(Expression raw, IVariableBinding lo, IVariableBinding hi) {
        Expression e = strip(raw);
        if (!(e instanceof InfixExpression ie) || ie.hasExtendedOperands()) {
            return false;
        }
        InfixExpression.Operator op = ie.getOperator();
        Expression l = strip(ie.getLeftOperand());
        Expression r = strip(ie.getRightOperand());
        boolean half = op == InfixExpression.Operator.DIVIDE && Long.valueOf(2).equals(constLong(r))
                || (op == InfixExpression.Operator.RIGHT_SHIFT_SIGNED || op == InfixExpression.Operator.RIGHT_SHIFT_UNSIGNED)
                && Long.valueOf(1).equals(constLong(r));
        if (half && l instanceof InfixExpression sum && !sum.hasExtendedOperands()
                && sum.getOperator() == InfixExpression.Operator.PLUS) {
            Expression a = strip(sum.getLeftOperand());
            Expression b = strip(sum.getRightOperand());
            return isVar(a, lo) && isVar(b, hi) || isVar(a, hi) && isVar(b, lo);
        }
        if (op == InfixExpression.Operator.PLUS && isVar(l, lo) && strip(r) instanceof InfixExpression tail
                && !tail.hasExtendedOperands()) {
            InfixExpression.Operator top = tail.getOperator();
            boolean tailHalf = top == InfixExpression.Operator.DIVIDE && Long.valueOf(2).equals(constLong(strip(tail.getRightOperand())))
                    || (top == InfixExpression.Operator.RIGHT_SHIFT_SIGNED || top == InfixExpression.Operator.RIGHT_SHIFT_UNSIGNED)
                    && Long.valueOf(1).equals(constLong(strip(tail.getRightOperand())));
            if (tailHalf && strip(tail.getLeftOperand()) instanceof InfixExpression diff && !diff.hasExtendedOperands()
                    && diff.getOperator() == InfixExpression.Operator.MINUS) {
                return isVar(diff.getLeftOperand(), hi) && isVar(diff.getRightOperand(), lo);
            }
        }
        return false;
    }

    /** Every write to lo/hi in the body is {@code lo = mid + 1} or {@code hi = mid - 1}; no ++/-- or compound writes. */
    private static boolean onlyMidpointUpdates(Statement body, IVariableBinding lo, IVariableBinding hi, IVariableBinding mid) {
        boolean[] ok = {true};
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment a) {
                boolean toLo = isVar(a.getLeftHandSide(), lo);
                boolean toHi = isVar(a.getLeftHandSide(), hi);
                if (toLo || toHi) {
                    ok[0] &= a.getOperator() == Assignment.Operator.ASSIGN && midStep(a.getRightHandSide(), mid, toLo);
                }
                return true;
            }

            @Override
            public boolean visit(PostfixExpression p) {
                ok[0] &= !isVar(p.getOperand(), lo) && !isVar(p.getOperand(), hi);
                return true;
            }

            @Override
            public boolean visit(PrefixExpression p) {
                ok[0] &= !isVar(p.getOperand(), lo) && !isVar(p.getOperand(), hi);
                return true;
            }
        });
        return ok[0];
    }

    /** {@code mid + 1} when raising lo, {@code mid - 1} when lowering hi. */
    private static boolean midStep(Expression raw, IVariableBinding mid, boolean raising) {
        Expression e = strip(raw);
        if (!(e instanceof InfixExpression ie) || ie.hasExtendedOperands()) {
            return false;
        }
        Expression l = strip(ie.getLeftOperand());
        Expression r = strip(ie.getRightOperand());
        if (raising) {
            return ie.getOperator() == InfixExpression.Operator.PLUS
                    && (isVar(l, mid) && Long.valueOf(1).equals(constLong(r)) || isVar(r, mid) && Long.valueOf(1).equals(constLong(l)));
        }
        return ie.getOperator() == InfixExpression.Operator.MINUS && isVar(l, mid) && Long.valueOf(1).equals(constLong(r));
    }

    /** Every path leaves the loop or moves lo/hi (an {@code if} without {@code else} does not count). */
    private static boolean progresses(Statement s, IVariableBinding lo, IVariableBinding hi) {
        switch (s) {
            case ReturnStatement ignored -> {
                return true;
            }
            case BreakStatement ignored -> {
                return true;
            }
            case ThrowStatement ignored -> {
                return true;
            }
            case ExpressionStatement es -> {
                return strip(es.getExpression()) instanceof Assignment a
                        && (isVar(a.getLeftHandSide(), lo) || isVar(a.getLeftHandSide(), hi));
            }
            case Block b -> {
                for (Object o : b.statements()) {
                    if (progresses((Statement) o, lo, hi)) {
                        return true;
                    }
                }
                return false;
            }
            case IfStatement is -> {
                return is.getElseStatement() != null && progresses(is.getThenStatement(), lo, hi)
                        && progresses(is.getElseStatement(), lo, hi);
            }
            default -> {
                return false;
            }
        }
    }


    // ---------- direct recursion ----------

    /**
     * Prices a directly recursive method from its recurrence. Supported: constant work per call, one integer
     * parameter that every recursive call decreases by a constant ({@code n - c}) or divides by a constant
     * ({@code n / d}, {@code n >> k}), no recursive call inside a loop, and a base-case guard before the first
     * recursive call. Anything else is reported as unknown with the reason.
     */
    private Map<String, Object> recursion(Cost work) {
        if (!work.unknown.isEmpty()) {
            work.unknown.add("The method is also recursive.");
            return render(work);
        }
        if (work.monos.stream().anyMatch(m -> !m.vars().isEmpty())) {
            return render(Cost.unknown("Recursive method whose work per call depends on input size: the recurrence is not modelled."));
        }
        Shape shape = recursionShape();
        if (shape.reason != null) {
            return render(Cost.unknown("Recursive method not modelled: " + shape.reason));
        }
        int calls = 0;
        for (Object o : method.getBody().statements()) {
            int c = pathCalls((Statement) o);
            if (c < 0) {
                return render(Cost.unknown("Recursive method not modelled: a recursive call sits in a loop, lambda or unsupported statement."));
            }
            calls += c;
        }
        String p = shape.parameter.getName();
        String time;
        String why;
        if (shape.halving) {
            if (calls == 1) {
                time = "O(log n)";
                why = "each call halves '" + p + "' and recurses once";
            } else if (calls <= shape.amount) {
                time = "O(n)";
                why = calls + " recursive calls on 1/" + shape.amount + " of '" + p + "' each (upper bound)";
            } else {
                return render(Cost.unknown("Recursive method not modelled: more recursive calls per level than the divisor."));
            }
        } else if (calls == 1) {
            time = "O(n)";
            why = "each call decreases '" + p + "' by " + shape.amount + " and recurses once";
        } else {
            time = "O(" + calls + "^n)";
            why = calls + " recursive calls per level, each decreasing '" + p + "'";
        }
        List<String> assumptions = new ArrayList<>();
        assumptions.add("n = value of parameter '" + p + "' (assumed non-negative)");
        assumptions.add("Recursion stops at the base-case guard '" + shape.guard + "' before the first recursive call.");
        assumptions.add("Each call does O(1) work outside the recursive calls.");
        if (calls > 1 && !shape.halving) {
            assumptions.add("The exponential bound is an upper bound: the true growth may be smaller (Fibonacci is Theta(1.618^n), within O(2^n)).");
        }
        assumptions.addAll(work.notes);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", time);
        out.put("confidence", "medium");
        out.put("reason", "Direct recursion: " + why + "; solved as a recurrence over '" + p + "'.");
        out.put("assumptions", assumptions);
        return out;
    }

    private static final class Shape {
        IVariableBinding parameter;
        boolean halving;
        long amount;
        String guard;
        String reason;
    }

    private Shape recursionShape() {
        Shape shape = new Shape();
        List<?> params = method.parameters();
        for (int i = 0; i < params.size(); i++) {
            SingleVariableDeclaration spd = (SingleVariableDeclaration) params.get(i);
            IVariableBinding pv = spd.resolveBinding();
            if (pv == null || !isIntegral(pv.getType()) || countMods(method.getBody(), pv) != 0) {
                continue;
            }
            boolean dec = true;
            boolean half = true;
            long decAmount = Long.MAX_VALUE;
            long halfAmount = -1;
            for (MethodInvocation call : recursiveCalls) {
                if (call.arguments().size() <= i) {
                    dec = false;
                    half = false;
                    break;
                }
                Expression arg = strip((Expression) call.arguments().get(i));
                long[] step = argumentStep(arg, pv);
                dec &= step != null && step[0] == 0;
                half &= step != null && step[0] == 1 && (halfAmount < 0 || halfAmount == step[1]);
                if (step != null && step[0] == 0) {
                    decAmount = Math.min(decAmount, step[1]);
                }
                if (step != null && step[0] == 1) {
                    halfAmount = step[1];
                }
            }
            if (!dec && !half) {
                continue;
            }
            shape.parameter = pv;
            shape.halving = half;
            shape.amount = half ? halfAmount : decAmount;
            shape.guard = guardBefore(pv, half, shape.amount);
            if (shape.guard == null) {
                shape.parameter = null;
                shape.reason = "no base-case guard ('" + pv.getName() + " <= c' / '" + pv.getName()
                        + " < c', returning or throwing) precedes the first recursive call.";
                continue;
            }
            return shape;
        }
        if (shape.reason == null) {
            shape.reason = "no integer parameter is decreased by a constant or divided by a constant in every recursive call.";
        }
        return shape;
    }

    /** {kind, amount}: kind 0 for {@code v - c}, 1 for {@code v / d} or {@code v >> k}; null otherwise. */
    private static long[] argumentStep(Expression arg, IVariableBinding v) {
        if (!(arg instanceof InfixExpression ie) || ie.hasExtendedOperands() || !isVar(ie.getLeftOperand(), v)) {
            return null;
        }
        Long c = constLong(strip(ie.getRightOperand()));
        if (c == null) {
            return null;
        }
        InfixExpression.Operator op = ie.getOperator();
        if (op == InfixExpression.Operator.MINUS && c >= 1) {
            return new long[] {0, c};
        }
        if (op == InfixExpression.Operator.DIVIDE && c >= 2) {
            return new long[] {1, c};
        }
        if ((op == InfixExpression.Operator.RIGHT_SHIFT_SIGNED || op == InfixExpression.Operator.RIGHT_SHIFT_UNSIGNED)
                && c >= 1 && c < 31) {
            return new long[] {1, 1L << c};
        }
        return null;
    }

    /** Text of the guard {@code if (v <= c) return ...;} that precedes the first statement with a recursive call. */
    private String guardBefore(IVariableBinding v, boolean halving, long amount) {
        List<?> statements = method.getBody().statements();
        for (Object o : statements) {
            Statement st = (Statement) o;
            if (containsRecursiveCall(st)) {
                return null;
            }
            if (st instanceof IfStatement is && alwaysExits(is.getThenStatement())
                    && strip(is.getExpression()) instanceof InfixExpression ie && !ie.hasExtendedOperands()) {
                Expression l = strip(ie.getLeftOperand());
                Expression r = strip(ie.getRightOperand());
                InfixExpression.Operator op = ie.getOperator();
                boolean varLeft = isVar(l, v) && constLong(r) != null;
                boolean varRight = isVar(r, v) && constLong(l) != null;
                if (!varLeft && !varRight) {
                    continue;
                }
                boolean upper = varLeft ? op == InfixExpression.Operator.LESS || op == InfixExpression.Operator.LESS_EQUALS
                        : op == InfixExpression.Operator.GREATER || op == InfixExpression.Operator.GREATER_EQUALS;
                // `v == c` ends the recursion only if v steps onto c: exact for a decrement of 1, or a halving onto 0/1
                long limit = varLeft ? constLong(r) : constLong(l);
                boolean exact = op == InfixExpression.Operator.EQUALS && (halving ? limit == 0 || limit == 1 : amount == 1);
                if (upper || exact) {
                    return norm(json.text(is.getExpression()));
                }
            }
        }
        return null;
    }

    private static boolean alwaysExits(Statement s) {
        if (s instanceof Block b) {
            return !b.statements().isEmpty() && alwaysExits((Statement) b.statements().get(b.statements().size() - 1));
        }
        return s instanceof ReturnStatement || s instanceof ThrowStatement;
    }

    private boolean containsRecursiveCall(ASTNode node) {
        boolean[] found = {false};
        node.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation mi) {
                found[0] |= recursiveCalls.contains(mi);
                return true;
            }
        });
        return found[0];
    }

    /** Most recursive calls any single execution path of {@code s} makes; -1 when a call sits somewhere unsupported. */
    private int pathCalls(ASTNode s) {
        if (!containsRecursiveCall(s)) {
            return 0;
        }
        switch (s) {
            case Block b -> {
                int sum = 0;
                for (Object o : b.statements()) {
                    int c = pathCalls((Statement) o);
                    if (c < 0) {
                        return -1;
                    }
                    sum += c;
                }
                return sum;
            }
            case IfStatement is -> {
                int cond = pathCalls(is.getExpression());
                int then = pathCalls(is.getThenStatement());
                int other = is.getElseStatement() == null ? 0 : pathCalls(is.getElseStatement());
                return cond < 0 || then < 0 || other < 0 ? -1 : cond + Math.max(then, other);
            }
            case ConditionalExpression ce -> {
                int cond = pathCalls(ce.getExpression());
                int then = pathCalls(ce.getThenExpression());
                int other = pathCalls(ce.getElseExpression());
                return cond < 0 || then < 0 || other < 0 ? -1 : cond + Math.max(then, other);
            }
            case ReturnStatement rs -> {
                return rs.getExpression() == null ? 0 : pathCalls(rs.getExpression());
            }
            case ExpressionStatement es -> {
                return pathCalls(es.getExpression());
            }
            case VariableDeclarationStatement vds -> {
                int sum = 0;
                for (Object f : vds.fragments()) {
                    Expression init = ((VariableDeclarationFragment) f).getInitializer();
                    int c = init == null ? 0 : pathCalls(init);
                    if (c < 0) {
                        return -1;
                    }
                    sum += c;
                }
                return sum;
            }
            case MethodInvocation mi -> {
                int sum = recursiveCalls.contains(mi) ? 1 : 0;
                if (mi.getExpression() != null) {
                    int c = pathCalls(mi.getExpression());
                    if (c < 0) {
                        return -1;
                    }
                    sum += c;
                }
                for (Object a : mi.arguments()) {
                    int c = pathCalls((Expression) a);
                    if (c < 0) {
                        return -1;
                    }
                    sum += c;
                }
                return sum;
            }
            case InfixExpression ie -> {
                int sum = pathCalls(ie.getLeftOperand());
                if (sum < 0) {
                    return -1;
                }
                int c = pathCalls(ie.getRightOperand());
                if (c < 0) {
                    return -1;
                }
                sum += c;
                for (Object o : ie.extendedOperands()) {
                    c = pathCalls((Expression) o);
                    if (c < 0) {
                        return -1;
                    }
                    sum += c;
                }
                return sum;
            }
            case ParenthesizedExpression pe -> {
                return pathCalls(pe.getExpression());
            }
            case Assignment a -> {
                return pathCalls(a.getRightHandSide());
            }
            default -> {
                return -1;
            }
        }
    }

    // ---------- bounds ----------

    private Bound classify(Expression raw, Map<String, String> env) {
        Expression e = strip(raw);
        Object constant = e.resolveConstantExpressionValue();
        if (constant instanceof Number) {
            return Bound.CONST;
        }
        String text = norm(json.text(e));
        switch (e) {
            case SimpleName n -> {
                if (n.resolveBinding() instanceof IVariableBinding vb && isIntegral(vb.getType())) {
                    if (env.containsKey(vb.getKey())) {
                        return new Bound(Kind.LINEAR, env.get(vb.getKey()), isCollectionKey(env.get(vb.getKey())), true, null);
                    }
                    String key = "var:" + vb.getKey();
                    descriptions.putIfAbsent(key, "value of " + n.getIdentifier() + " (assumed non-negative)");
                    return new Bound(Kind.LINEAR, key, false, false, null);
                }
                return Bound.unknown("Bound '" + text + "' is not an integer variable.");
            }
            case QualifiedName q -> {
                if (q.getName().getIdentifier().equals("length") && q.getQualifier().resolveTypeBinding() != null
                        && q.getQualifier().resolveTypeBinding().isArray()) {
                    return collectionBound(norm(json.text(q.getQualifier())), "length of ");
                }
                return Bound.unknown("Bound '" + text + "' is not a recognised size.");
            }
            case FieldAccess fa -> {
                if (fa.getName().getIdentifier().equals("length") && fa.getExpression().resolveTypeBinding() != null
                        && fa.getExpression().resolveTypeBinding().isArray()) {
                    return collectionBound(norm(json.text(fa.getExpression())), "length of ");
                }
                return Bound.unknown("Bound '" + text + "' is not a recognised size.");
            }
            case MethodInvocation mi -> {
                String name = mi.getName().getIdentifier();
                ITypeBinding rt = mi.getExpression() == null ? null : mi.getExpression().resolveTypeBinding();
                if (mi.arguments().isEmpty() && rt != null && (name.equals("size") || name.equals("length"))
                        && isSized(rt.getErasure().getQualifiedName())) {
                    return collectionBound(norm(json.text(mi.getExpression())), "number of elements in ");
                }
                return Bound.unknown("Bound '" + text + "' is a method call with no known size model.");
            }
            case InfixExpression ie -> {
                InfixExpression.Operator op = ie.getOperator();
                boolean arithmetic = op == InfixExpression.Operator.PLUS || op == InfixExpression.Operator.MINUS
                        || op == InfixExpression.Operator.TIMES || op == InfixExpression.Operator.DIVIDE
                        || op == InfixExpression.Operator.REMAINDER;
                if (!arithmetic) {
                    return Bound.unknown("Bound '" + text + "' is not a recognised size.");
                }
                // JDT keeps "a - b - c" as one node with extended operands: fold left to right
                Bound acc = classify(ie.getLeftOperand(), env);
                List<Expression> rest = new ArrayList<>();
                rest.add(ie.getRightOperand());
                for (Object o : ie.extendedOperands()) {
                    rest.add((Expression) o);
                }
                for (Expression operand : rest) {
                    acc = combine(op, acc, classify(operand, env), text);
                }
                return acc;
            }
            case CastExpression ce -> {
                return classify(ce.getExpression(), env);
            }
            default -> {
                return Bound.unknown("Bound '" + text + "' is not a recognised size.");
            }
        }
    }

    /** {@code l op r} where one side is a constant keeps the other side's size; a difference/quotient keeps the left. */
    private static Bound combine(InfixExpression.Operator op, Bound lb, Bound rb, String text) {
        if (lb.kind() == Kind.CONST && rb.kind() == Kind.CONST) {
            return Bound.CONST;
        }
        if (rb.kind() == Kind.CONST) {
            return lb;
        }
        boolean commutative = op == InfixExpression.Operator.PLUS || op == InfixExpression.Operator.TIMES;
        if (lb.kind() == Kind.CONST && commutative) {
            return rb;
        }
        if ((op == InfixExpression.Operator.MINUS || op == InfixExpression.Operator.DIVIDE) && lb.kind() != Kind.UNKNOWN) {
            return lb;
        }
        return Bound.unknown("Bound '" + text + "' combines several variable sizes.");
    }

    private Bound collectionBound(String text, String what) {
        String key = "coll:" + text;
        descriptions.putIfAbsent(key, what + text);
        return new Bound(Kind.LINEAR, key, true, false, null);
    }

    private static boolean isCollectionKey(String key) {
        return key != null && key.startsWith("coll:");
    }

    private static boolean isIntegral(ITypeBinding t) {
        if (t == null) {
            return false;
        }
        String n = t.getQualifiedName();
        return Set.of("int", "long", "short", "byte", "char", "java.lang.Integer", "java.lang.Long").contains(n);
    }

    private static boolean isSized(String type) {
        return type.equals("java.lang.String") || type.equals("java.lang.StringBuilder") || type.startsWith("java.util.")
                && (family(type) != null);
    }

    /** True if the bound mentions a variable that the loop assigns, or a collection that the loop grows. */
    private boolean mutatedInLoop(Expression limit, Statement loop) {
        boolean[] changed = {false};
        limit.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName n) {
                if (n.resolveBinding() instanceof IVariableBinding vb && countMods(loop, vb) > 0) {
                    changed[0] = true;
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation mi) {
                if (mi.getExpression() != null && growsCollection(loop, norm(json.text(mi.getExpression())))) {
                    changed[0] = true;
                }
                return true;
            }
        });
        return changed[0];
    }

    private boolean growsCollection(ASTNode scope, String receiverText) {
        return mutates(scope, receiverText);
    }

    private boolean mutates(ASTNode scope, String receiverText) {
        boolean[] found = {false};
        scope.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation mi) {
                if (mi.getExpression() != null && MUTATORS.contains(mi.getName().getIdentifier())
                        && norm(json.text(mi.getExpression())).equals(receiverText)) {
                    found[0] = true;
                }
                return true;
            }

            @Override
            public boolean visit(Assignment a) {
                if (norm(json.text(a.getLeftHandSide())).equals(receiverText)) {
                    found[0] = true;
                }
                return true;
            }
        });
        return found[0];
    }

    private static int countMods(ASTNode root, IVariableBinding v) {
        int[] count = {0};
        root.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment a) {
                if (isVar(a.getLeftHandSide(), v)) {
                    count[0]++;
                }
                return true;
            }

            @Override
            public boolean visit(PostfixExpression p) {
                if (isVar(p.getOperand(), v)) {
                    count[0]++;
                }
                return true;
            }

            @Override
            public boolean visit(PrefixExpression p) {
                if ((p.getOperator() == PrefixExpression.Operator.INCREMENT
                        || p.getOperator() == PrefixExpression.Operator.DECREMENT) && isVar(p.getOperand(), v)) {
                    count[0]++;
                }
                return true;
            }
        });
        return count[0];
    }

    private static boolean hasContinue(ASTNode body) {
        boolean[] found = {false};
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(ContinueStatement c) {
                found[0] = true;
                return false;
            }
        });
        return found[0];
    }

    // ---------- expressions and calls ----------

    private Cost expr(ASTNode e) {
        Cost[] total = {Cost.constant()};
        e.accept(new ASTVisitor() {
            @Override
            public boolean visit(LambdaExpression n) {
                return false;
            }

            @Override
            public boolean visit(AnonymousClassDeclaration n) {
                return false;
            }

            @Override
            public boolean visit(MethodInvocation n) {
                total[0] = total[0].plus(callCost(n));
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation n) {
                total[0] = total[0].plus(Cost.unknown("Call to super." + n.getName().getIdentifier() + "() has no known cost."));
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation n) {
                total[0] = total[0].plus(constructorCost(n));
                return true;
            }

            @Override
            public boolean visit(ArrayCreation n) {
                for (Object d : n.dimensions()) {
                    Bound b = classify((Expression) d, Map.of());
                    if (b.kind() == Kind.LINEAR) {
                        total[0] = total[0].plus(Cost.linear(b.key(), false));
                    } else if (b.kind() == Kind.UNKNOWN) {
                        total[0] = total[0].plus(Cost.unknown("Array allocation size '" + norm(json.text((Expression) d)) + "' is unknown."));
                    }
                }
                return true;
            }

            @Override
            public boolean visit(SwitchExpression n) {
                Cost c = Cost.constant();
                for (Object o : n.statements()) {
                    if (o instanceof Statement s) {
                        c = c.plus(stmt(s, Map.of()));
                    }
                }
                total[0] = total[0].plus(c);
                n.getExpression().accept(this);
                return false;
            }
        });
        return total[0];
    }

    private Cost callCost(MethodInvocation mi) {
        IMethodBinding b = mi.resolveMethodBinding();
        String name = mi.getName().getIdentifier();
        if (b == null) {
            return Cost.unknown("Call to " + name + "() could not be resolved.");
        }
        String decl = b.getDeclaringClass().getErasure().getQualifiedName();
        Expression recvExpr = mi.getExpression();
        ITypeBinding rt = recvExpr == null ? null : recvExpr.resolveTypeBinding();
        String recv = rt == null ? decl : rt.getErasure().getQualifiedName();
        String recvText = recvExpr == null ? "this" : norm(json.text(recvExpr));
        int argc = mi.arguments().size();

        if (root && method.resolveBinding() != null
                && method.resolveBinding().getKey().equals(b.getMethodDeclaration().getKey())) {
            recursiveCalls.add(mi);
            return Cost.constant(); // priced by recursion(), never as O(1)
        }
        Cost modeled = model(recv, decl, name, argc, recvText, mi);
        if (modeled != null) {
            return modeled;
        }
        if (isTrivialAccessor(b)) {
            return Cost.constant();
        }
        String shown = decl.substring(decl.lastIndexOf('.') + 1) + "." + name + "()";
        Cost user = userMethodCost(b, shown, mi.arguments(), Map.of());
        if (user == null) {
            user = dispatchCost(b, shown);
        }
        return user != null ? user : Cost.unknown("Call to " + shown + " has no known cost model.");
    }

    /**
     * A call through an interface or an overridable method: O(1) when every implementation the project declares
     * (methods, implicit record accessors) is O(1) on its own. Null when an implementation is not, when the type is
     * a functional interface (a lambda could implement it), or when the project declares no implementation.
     */
    /** A lambda or method reference somewhere in the project has this (functional interface) type. */
    private boolean implementedByLambda(ITypeBinding type) {
        boolean[] found = {false};
        for (AstJson peer : json.peers()) {
            peer.unit().accept(new ASTVisitor() {
                private void check(Expression e) {
                    ITypeBinding t = e.resolveTypeBinding();
                    found[0] |= t == null || t.getErasure().isEqualTo(type.getErasure());
                }

                @Override
                public boolean visit(LambdaExpression e) {
                    check(e);
                    return !found[0];
                }

                @Override
                public boolean visit(ExpressionMethodReference e) {
                    check(e);
                    return !found[0];
                }

                @Override
                public boolean visit(TypeMethodReference e) {
                    check(e);
                    return !found[0];
                }

                @Override
                public boolean visit(SuperMethodReference e) {
                    check(e);
                    return !found[0];
                }

                @Override
                public boolean visit(CreationReference e) {
                    check(e);
                    return !found[0];
                }
            });
        }
        return found[0];
    }

    private Cost dispatchCost(IMethodBinding b, String shown) {
        IMethodBinding target = b.getMethodDeclaration();
        ITypeBinding owner = target.getDeclaringClass();
        if (Modifier.isStatic(target.getModifiers()) || Modifier.isPrivate(target.getModifiers()) || !owner.isFromSource()
                || owner.getFunctionalInterfaceMethod() != null && implementedByLambda(owner)) {
            return null;
        }
        List<MethodDeclaration> implementations = new ArrayList<>();
        int[] recordAccessors = {0};
        for (AstJson peer : json.peers()) {
            peer.unit().accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration md) {
                    IMethodBinding mb = md.resolveBinding();
                    if (mb != null && md.getBody() != null && (mb == target || mb.overrides(target))) {
                        implementations.add(md);
                    }
                    return true;
                }

                @Override
                public boolean visit(RecordDeclaration rd) {
                    ITypeBinding rb = rd.resolveBinding();
                    if (rb != null && target.getParameterTypes().length == 0 && rb.isSubTypeCompatible(owner.getErasure())
                            && java.util.Arrays.stream(rb.getDeclaredFields()).anyMatch(f -> f.getName().equals(target.getName()))
                            && java.util.Arrays.stream(rb.getDeclaredMethods()).noneMatch(m -> m.getName().equals(target.getName())
                                    && m.getParameterTypes().length == 0 && !m.isSynthetic() && !m.isDefaultConstructor()
                                    && m.getJavaElement() != null && rd.bodyDeclarations().stream().anyMatch(d -> d instanceof MethodDeclaration
                                            && ((MethodDeclaration) d).getName().getIdentifier().equals(target.getName())))) {
                        recordAccessors[0]++;
                    }
                    return true;
                }
            });
        }
        if (implementations.isEmpty() && recordAccessors[0] == 0) {
            return null;
        }
        Cost result = Cost.constant();
        for (MethodDeclaration md : implementations) {
            if (active.contains(md) || active.size() >= MAX_CALL_DEPTH) {
                return null;
            }
            AstJson implOwner = ownerOf(md);
            ComplexityAnalyzer sub = new ComplexityAnalyzer(implOwner, implOwner.unit(), md, null, active);
            active.add(md);
            Cost cost;
            try {
                cost = sub.block(md.getBody().statements(), Map.of());
                sub.scanEffects(md.getBody());
            } finally {
                active.remove(md);
            }
            if (sub.impure || !cost.unknown.isEmpty() || !sub.recursiveCalls.isEmpty()
                    || cost.monos.stream().anyMatch(m -> !m.vars().isEmpty())) {
                return null;
            }
            writes.addAll(sub.writes);
            result.notes.addAll(cost.notes);
        }
        int count = implementations.size() + recordAccessors[0];
        result.notes.add("Call to " + shown + " is O(1): each of its " + count + " implementation" + (count == 1 ? "" : "s")
                + " in the project was analyzed as O(1) (dynamic dispatch; classes outside the project are not seen).");
        result.weak = true;
        return result;
    }

    private static final int MAX_CALL_DEPTH = 8;

    /**
     * Cost of a call to a method declared in this file, or {@code null} when it cannot be trusted. The callee is
     * analyzed on its own; the call is O(1) only if that analysis is exactly constant (no loop over input, no
     * unknown call), the callee is not recursive and cannot be overridden, and it does not mutate its arguments.
     * Anything else stays {@code unknown}: a callee's cost depends on its arguments, which are not tracked.
     */
    private Cost userMethodCost(IMethodBinding b, String shown, List<?> arguments, Map<String, String> env) {
        MethodDeclaration callee = declarationOf(b);
        if (callee == null || callee.getBody() == null || callee.isConstructor()
                || active.contains(callee) || active.size() >= MAX_CALL_DEPTH || isOverridden(b)) {
            return null;
        }
        AstJson owner = ownerOf(callee);
        ComplexityAnalyzer sub = new ComplexityAnalyzer(owner, owner.unit(), callee, null, active);
        active.add(callee);
        Cost cost;
        try {
            cost = sub.block(callee.getBody().statements(), Map.of());
            sub.scanEffects(callee.getBody());
        } finally {
            active.remove(callee);
        }
        if (sub.impure || !cost.unknown.isEmpty() || !sub.recursiveCalls.isEmpty()) {
            return null;
        }
        writes.addAll(sub.writes);
        Cost result;
        if (cost.monos.stream().anyMatch(m -> !m.vars().isEmpty())) {
            result = substitute(cost, sub, callee, arguments, env);
            if (result == null) {
                return null;
            }
            result.notes.add("Call to " + shown + " costs the callee's analyzed bound, expressed in the argument sizes.");
            result.weak = true;
        } else {
            result = Cost.constant();
            result.notes.addAll(cost.notes);
            result.notes.add("Call to " + shown + " is O(1): the method is declared in this "
                    + (owner == json ? "file" : "project (" + owner.file() + ")") + " and was analyzed.");
            result.weak = cost.weak;
        }
        return result;
    }

    /**
     * Rewrites a callee cost over its own parameters into this method's variables: {@code coll:<param>} becomes the
     * size of the argument passed for it, {@code var:<param>} the classified bound of that argument. Null when the
     * callee's cost mentions anything else (fields, locals) or an argument size is not recognisable.
     */
    private Cost substitute(Cost cost, ComplexityAnalyzer sub, MethodDeclaration callee, List<?> arguments,
                            Map<String, String> env) {
        Map<String, Bound> mapping = new HashMap<>();
        List<?> params = callee.parameters();
        if (params.size() != arguments.size()) {
            return null;
        }
        for (int i = 0; i < params.size(); i++) {
            SingleVariableDeclaration p = (SingleVariableDeclaration) params.get(i);
            IVariableBinding pb = p.resolveBinding();
            Expression arg = strip((Expression) arguments.get(i));
            if (pb == null) {
                return null;
            }
            ITypeBinding at = arg.resolveTypeBinding();
            if (at != null && (at.isArray() || isSized(at.getErasure().getQualifiedName()))
                    && (arg instanceof Name || arg instanceof FieldAccess)) {
                String text = norm(json.text(arg));
                mapping.put("coll:" + p.getName().getIdentifier(),
                        collectionBound(text, at.isArray() ? "length of " : "number of elements in "));
            } else if (isIntegral(pb.getType())) {
                mapping.put("var:" + pb.getKey(), classify(arg, env));
            }
        }
        Cost out = new Cost();
        out.unknown.addAll(cost.unknown);
        out.notes.addAll(cost.notes);
        out.weak = cost.weak;
        for (Mono m : cost.monos) {
            TreeMap<String, int[]> vars = new TreeMap<>();
            for (Map.Entry<String, int[]> e : m.vars().entrySet()) {
                Bound target = mapping.get(e.getKey());
                if (target == null || target.kind() == Kind.UNKNOWN) {
                    return null;
                }
                if (target.kind() == Kind.CONST) {
                    continue; // a constant argument removes the variable from the bound
                }
                int[] merged = vars.computeIfAbsent(target.key(), k -> new int[2]);
                merged[0] += e.getValue()[0];
                merged[1] += e.getValue()[1];
            }
            out.monos.add(new Mono(vars));
        }
        return out.monos.isEmpty() ? null : out;
    }

    /** The declaration of {@code b} in this file or, in project mode, in any analyzed file. */
    private MethodDeclaration declarationOf(IMethodBinding b) {
        for (AstJson peer : json.peers()) {
            if (peer.unit().findDeclaringNode(b.getMethodDeclaration()) instanceof MethodDeclaration md) {
                return md;
            }
        }
        return null;
    }

    private AstJson ownerOf(MethodDeclaration md) {
        for (AstJson peer : json.peers()) {
            if (peer.unit() == md.getRoot()) {
                return peer;
            }
        }
        return json;
    }

    /**
     * True when another analyzed method overrides {@code b}, so the call target may differ at run time.
     * Only the analyzed files are searched: a subclass outside the project cannot be seen.
     */
    private boolean isOverridden(IMethodBinding b) {
        if (Modifier.isPrivate(b.getModifiers()) || Modifier.isStatic(b.getModifiers())
                || Modifier.isFinal(b.getModifiers())) {
            return false;
        }
        boolean[] found = {false};
        for (AstJson peer : json.peers()) {
            peer.unit().accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration md) {
                    IMethodBinding mb = md.resolveBinding();
                    if (mb != null && mb != b.getMethodDeclaration() && mb.overrides(b.getMethodDeclaration())) {
                        found[0] = true;
                    }
                    return !found[0];
                }
            });
        }
        return found[0];
    }

    /** Records the fields a callee body writes, and flags writes through arguments or unresolvable targets. */
    private void scanEffects(ASTNode body) {
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(LambdaExpression n) {
                return false;
            }

            @Override
            public boolean visit(AnonymousClassDeclaration n) {
                return false;
            }

            @Override
            public boolean visit(Assignment n) {
                recordWrite(n.getLeftHandSide(), false);
                return true;
            }

            @Override
            public boolean visit(PostfixExpression n) {
                recordWrite(n.getOperand(), false);
                return true;
            }

            @Override
            public boolean visit(PrefixExpression n) {
                if (n.getOperator() == PrefixExpression.Operator.INCREMENT
                        || n.getOperator() == PrefixExpression.Operator.DECREMENT) {
                    recordWrite(n.getOperand(), false);
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation n) {
                if (n.getExpression() != null && MUTATORS.contains(n.getName().getIdentifier())) {
                    recordWrite(n.getExpression(), true);
                }
                return true;
            }
        });
    }

    private void recordWrite(Expression target, boolean mutatorCall) {
        Expression t = strip(target);
        if (t instanceof ThisExpression || t instanceof ArrayAccess) {
            return; // `this.add(..)` is a call of this file's own method; array elements never change a loop bound
        }
        IBinding binding = switch (t) {
            case SimpleName n -> n.resolveBinding();
            case QualifiedName n -> n.resolveBinding();
            case FieldAccess n -> n.getName().resolveBinding();
            default -> null;
        };
        if (binding instanceof IVariableBinding vb) {
            if (vb.isField()) {
                writes.add(vb.getKey());
            } else if (vb.isParameter() && mutatorCall) {
                impure = true; // mutator call on an argument: the caller's object changes
            }
            return;
        }
        impure = true;
    }

    /** Name of a field written by a called method that also appears in a loop header of this method, or null. */
    private String writtenLoopBound() {
        if (writes.isEmpty()) {
            return null;
        }
        String[] hit = {null};
        method.getBody().accept(new ASTVisitor() {
            private void header(Expression e) {
                if (e != null && hit[0] == null) {
                    e.accept(new ASTVisitor() {
                        @Override
                        public boolean visit(SimpleName n) {
                            if (hit[0] == null && n.resolveBinding() instanceof IVariableBinding vb && vb.isField()
                                    && writes.contains(vb.getKey())) {
                                hit[0] = vb.getName();
                            }
                            return true;
                        }
                    });
                }
            }

            @Override
            public boolean visit(ForStatement n) {
                header(n.getExpression());
                return true;
            }

            @Override
            public boolean visit(WhileStatement n) {
                header(n.getExpression());
                return true;
            }

            @Override
            public boolean visit(DoStatement n) {
                header(n.getExpression());
                return true;
            }

            @Override
            public boolean visit(EnhancedForStatement n) {
                header(n.getExpression());
                return true;
            }
        });
        return hit[0];
    }

    private Cost constructorCost(ClassInstanceCreation n) {
        ITypeBinding t = n.resolveTypeBinding();
        if (t == null) {
            return Cost.unknown("Constructor call could not be resolved.");
        }
        String name = t.getErasure().getQualifiedName();
        for (ITypeBinding s = t; s != null; s = s.getSuperclass()) {
            if (s.getErasure().getQualifiedName().equals("java.lang.Throwable")) {
                return Cost.constant();
            }
        }
        boolean known = name.equals("java.lang.Object") || name.equals("java.lang.StringBuilder")
                || name.equals("java.lang.String") || name.startsWith("java.util.") && family(name) != null
                || Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Boolean").contains(name);
        if (known) {
            if (n.arguments().size() == 1 && ((Expression) n.arguments().get(0)).resolveTypeBinding() != null
                    && !((Expression) n.arguments().get(0)).resolveTypeBinding().isPrimitive()
                    && !name.equals("java.lang.String") && !name.equals("java.lang.StringBuilder")
                    && !name.startsWith("java.lang.")) {
                return linearIn(norm(json.text((Expression) n.arguments().get(0))));
            }
            return Cost.constant();
        }
        if (Set.of("java.util.Scanner", "java.io.PrintStream", "java.io.InputStreamReader", "java.io.BufferedReader",
                "java.io.OutputStreamWriter", "java.io.PrintWriter", "java.io.BufferedWriter", "java.util.Random",
                "java.util.Formatter").contains(name)) {
            return Cost.constant("Constructors of console readers and writers are treated as O(1).");
        }
        if (ConstructorPurity.isTrivial(n, json.peers().stream().map(AstJson::unit).toList())) {
            Cost trivial = Cost.constant();
            trivial.notes.add("Constructor of " + name.substring(name.lastIndexOf('.') + 1)
                    + " only stores its arguments into fields, so it is O(1).");
            return trivial;
        }
        Cost analyzed = analyzedConstructorCost(n, t);
        if (analyzed != null) {
            return analyzed;
        }
        return Cost.unknown("Constructor of " + name.substring(name.lastIndexOf('.') + 1) + " has no known cost model.");
    }

    /**
     * A project constructor whose body analyzes as O(1), in a class whose field initializers only create objects or
     * hold constants and whose superclass is not a project class (its constructor would run too). Null otherwise.
     */
    private Cost analyzedConstructorCost(ClassInstanceCreation n, ITypeBinding type) {
        IMethodBinding ctor = n.resolveConstructorBinding();
        if (ctor == null || n.getAnonymousClassDeclaration() != null) {
            return null;
        }
        ITypeBinding superclass = type.getSuperclass();
        if (superclass != null && superclass.isFromSource()) {
            return null;
        }
        MethodDeclaration md = declarationOf(ctor);
        if (md == null || md.getBody() == null || !(md.getParent() instanceof AbstractTypeDeclaration holder)) {
            return null;
        }
        for (Object d : holder.bodyDeclarations()) {
            if (d instanceof FieldDeclaration fd && !Modifier.isStatic(fd.getModifiers())) {
                for (Object f : fd.fragments()) {
                    Expression init = ((VariableDeclarationFragment) f).getInitializer();
                    if (init != null && !(init instanceof ClassInstanceCreation c && c.arguments().isEmpty()
                            && c.getAnonymousClassDeclaration() == null) && !(init instanceof NumberLiteral)
                            && !(init instanceof StringLiteral) && !(init instanceof BooleanLiteral) && !(init instanceof NullLiteral)) {
                        return null;
                    }
                }
            } else if (d instanceof Initializer i && !Modifier.isStatic(i.getModifiers())) {
                return null;
            }
        }
        if (active.contains(md) || active.size() >= MAX_CALL_DEPTH) {
            return null;
        }
        AstJson owner = ownerOf(md);
        ComplexityAnalyzer sub = new ComplexityAnalyzer(owner, owner.unit(), md, null, active);
        active.add(md);
        Cost cost;
        try {
            cost = sub.block(md.getBody().statements(), Map.of());
            sub.scanEffects(md.getBody());
        } finally {
            active.remove(md);
        }
        if (!cost.unknown.isEmpty() || !sub.recursiveCalls.isEmpty() || cost.monos.stream().anyMatch(m -> !m.vars().isEmpty())) {
            return null;
        }
        Cost result = Cost.constant();
        result.notes.addAll(cost.notes);
        result.notes.add("Constructor of " + type.getName() + " was analyzed: its body is O(1).");
        result.weak = cost.weak;
        return result;
    }

    /** {@code return field;}, {@code this.f = x;} and record accessors cost O(1). */
    private boolean isTrivialAccessor(IMethodBinding b) {
        ASTNode decl = declarationOf(b);
        if (decl == null) {
            return b.getDeclaringClass().isRecord() && b.getParameterTypes().length == 0;
        }
        if (!(decl instanceof MethodDeclaration md) || md.getBody() == null || md.getBody().statements().size() != 1) {
            return false;
        }
        Statement only = (Statement) md.getBody().statements().get(0);
        Expression value = null;
        if (only instanceof ReturnStatement rs) {
            value = rs.getExpression();
        } else if (only instanceof ExpressionStatement es && es.getExpression() instanceof Assignment a) {
            value = a.getRightHandSide();
            if (!(strip(a.getLeftHandSide()) instanceof Name || strip(a.getLeftHandSide()) instanceof FieldAccess)) {
                return false;
            }
        }
        if (value == null) {
            return only instanceof ReturnStatement;
        }
        value = strip(value);
        return value instanceof Name || value instanceof NumberLiteral || value instanceof StringLiteral
                || value instanceof BooleanLiteral || value instanceof CharacterLiteral || value instanceof NullLiteral || value instanceof ThisExpression
                || value instanceof FieldAccess fa && fa.getExpression() instanceof ThisExpression;
    }

    private static String family(String type) {
        return switch (type) {
            case "java.util.List", "java.util.ArrayList", "java.util.Vector", "java.util.Collection" -> type.endsWith("Collection") ? "COLLECTION" : "LIST";
            case "java.util.LinkedList" -> "LINKED";
            case "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet" -> "HASH_SET";
            case "java.util.TreeSet", "java.util.SortedSet", "java.util.NavigableSet" -> "TREE_SET";
            case "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap" -> "HASH_MAP";
            case "java.util.TreeMap", "java.util.SortedMap", "java.util.NavigableMap" -> "TREE_MAP";
            case "java.util.ArrayDeque", "java.util.Deque", "java.util.Queue" -> "DEQUE";
            case "java.util.PriorityQueue" -> "PQ";
            default -> null;
        };
    }

    private Cost linearIn(String text) {
        String key = "coll:" + text;
        descriptions.putIfAbsent(key, "number of elements in " + text);
        return Cost.linear(key, false);
    }

    private Cost nLogN(String text) {
        String key = "coll:" + text;
        descriptions.putIfAbsent(key, "number of elements in " + text);
        return Cost.linear(key, true);
    }

    private Cost logIn(String text) {
        String key = "coll:" + text;
        descriptions.putIfAbsent(key, "number of elements in " + text);
        Cost c = new Cost();
        c.monos.add(new Mono(new TreeMap<>()).times(key, true));
        return c;
    }

    /** Known standard-library cost models; {@code null} when the call is not modelled. */
    /** {@code computeIfAbsent(key, k -> new ArrayList<>())}: the mapping function is a JDK constructor without arguments. */
    private static boolean createsEmptyJdkCollection(MethodInvocation mi) {
        if (mi.arguments().size() != 2 || !(strip((Expression) mi.arguments().get(1)) instanceof LambdaExpression lambda)
                || lambda.parameters().size() != 1
                || !(lambda.getBody() instanceof ClassInstanceCreation created)
                || !created.arguments().isEmpty() || created.getAnonymousClassDeclaration() != null) {
            return false;
        }
        ITypeBinding type = created.resolveTypeBinding();
        return type != null && type.getErasure().getQualifiedName().startsWith("java.util.");
    }

    private Cost model(String recv, String decl, String name, int argc, String recvText, MethodInvocation mi) {
        switch (decl) {
            case "java.lang.Math", "java.lang.StrictMath", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
                 "java.lang.Boolean", "java.lang.Character", "java.util.Objects" -> {
                return Cost.constant();
            }
            case "java.io.PrintStream" -> {
                return Set.of("println", "print", "printf", "format").contains(name) ? Cost.constant() : null;
            }
            case "java.util.Map.Entry", "java.util.AbstractMap.SimpleEntry", "java.util.AbstractMap.SimpleImmutableEntry" -> {
                return Set.of("getKey", "getValue", "setValue").contains(name) ? Cost.constant() : null;
            }
            case "java.util.Scanner" -> {
                return name.startsWith("next") || name.startsWith("hasNext") || name.equals("close")
                        ? Cost.constant(ASSUME_INPUT) : null;
            }
            case "java.io.BufferedReader" -> {
                return Set.of("readLine", "read", "ready", "close").contains(name) ? Cost.constant(ASSUME_INPUT) : null;
            }
            case "java.lang.String" -> {
                return Set.of("length", "charAt", "isEmpty", "isBlank", "equals", "equalsIgnoreCase", "compareTo",
                        "compareToIgnoreCase", "hashCode", "startsWith", "endsWith", "valueOf", "toString", "trim", "strip",
                        "toLowerCase", "toUpperCase", "contains", "indexOf", "substring", "concat", "replace", "split",
                        "format", "formatted", "join").contains(name) ? Cost.constant(ASSUME_STRING) : null;
            }
            case "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.AbstractStringBuilder" -> {
                return Set.of("append", "length", "charAt", "toString", "setLength").contains(name)
                        ? Cost.constant(ASSUME_STRING) : null;
            }
            case "java.lang.Object" -> {
                return Set.of("equals", "hashCode", "toString", "getClass").contains(name) ? Cost.constant() : null;
            }
            case "java.util.Arrays" -> {
                String arg0 = argc > 0 ? norm(json.text((Expression) mi.arguments().get(0))) : "";
                return switch (name) {
                    case "asList" -> Cost.constant();
                    case "sort" -> nLogN(arg0);
                    case "fill", "copyOf", "copyOfRange", "toString", "equals", "stream" -> linearIn(arg0);
                    case "binarySearch" -> logIn(arg0);
                    default -> null;
                };
            }
            case "java.util.Collections" -> {
                String arg0 = argc > 0 ? norm(json.text((Expression) mi.arguments().get(0))) : "";
                return switch (name) {
                    case "sort" -> nLogN(arg0);
                    case "reverse", "shuffle", "max", "min" -> linearIn(arg0);
                    case "binarySearch" -> logIn(arg0);
                    case "swap" -> Cost.constant(ASSUME_RANDOM_ACCESS);
                    case "emptyList", "emptyMap", "emptySet", "unmodifiableList", "unmodifiableMap", "unmodifiableSet" -> Cost.constant();
                    default -> null;
                };
            }
            default -> {
            }
        }

        String fam = family(recv);
        if (fam == null) {
            return null;
        }
        switch (name) {
            case "size", "isEmpty", "iterator", "keySet", "values", "entrySet", "hasNext", "next" -> {
                return Cost.constant();
            }
            default -> {
            }
        }
        switch (fam) {
            case "LIST" -> {
                switch (name) {
                    case "get", "set", "getFirst", "getLast" -> {
                        return Cost.constant(ASSUME_RANDOM_ACCESS);
                    }
                    case "add" -> {
                        return argc == 1 ? Cost.constant(ASSUME_RANDOM_ACCESS) : linearIn(recvText);
                    }
                    case "contains", "indexOf", "lastIndexOf", "remove", "addAll", "removeAll", "containsAll", "retainAll" -> {
                        return linearIn(recvText);
                    }
                    case "sort" -> {
                        return nLogN(recvText);
                    }
                    default -> {
                        return null;
                    }
                }
            }
            case "LINKED" -> {
                switch (name) {
                    case "add", "addFirst", "addLast", "removeFirst", "removeLast", "poll", "peek", "push", "pop", "offer",
                         "getFirst", "getLast" -> {
                        return Cost.constant();
                    }
                    case "get", "set", "contains", "indexOf", "lastIndexOf", "remove", "addAll" -> {
                        return linearIn(recvText);
                    }
                    default -> {
                        return null;
                    }
                }
            }
            case "HASH_SET" -> {
                return Set.of("add", "contains", "remove").contains(name) ? Cost.constant(ASSUME_HASH) : null;
            }
            case "TREE_SET" -> {
                return Set.of("add", "contains", "remove", "first", "last").contains(name) ? logIn(recvText) : null;
            }
            case "HASH_MAP" -> {
                if (Set.of("get", "put", "containsKey", "remove", "getOrDefault", "putIfAbsent").contains(name)
                        || name.equals("computeIfAbsent") && createsEmptyJdkCollection(mi)) {
                    return Cost.constant(ASSUME_HASH);
                }
                return name.equals("containsValue") ? linearIn(recvText) : null;
            }
            case "TREE_MAP" -> {
                if (Set.of("get", "put", "containsKey", "remove", "getOrDefault", "putIfAbsent", "firstKey", "lastKey").contains(name)) {
                    return logIn(recvText);
                }
                return name.equals("containsValue") ? linearIn(recvText) : null;
            }
            case "DEQUE" -> {
                return Set.of("add", "offer", "poll", "peek", "push", "pop", "addFirst", "addLast", "removeFirst",
                        "removeLast", "pollFirst", "pollLast", "peekFirst", "peekLast").contains(name)
                        ? Cost.constant("Queue/Deque operations are treated as O(1) (ArrayDeque or LinkedList).") : null;
            }
            case "PQ" -> {
                if (Set.of("add", "offer", "poll", "remove").contains(name)) {
                    return logIn(recvText);
                }
                return name.equals("peek") ? Cost.constant() : null;
            }
            default -> {
                return null; // plain Collection: implementation unknown
            }
        }
    }

    // ---------- findings ----------

    private void detectFindings(LoopInfo inner) {
        Factor f = inner.factor();
        if (f.kind() != Kind.LINEAR || !f.collection()) {
            return;
        }
        LoopInfo outer = null;
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i).factor().kind() == Kind.LINEAR) {
                outer = stack.get(i);
                break;
            }
        }
        if (outer == null) {
            return;
        }
        String collection = f.key().substring("coll:".length());
        Statement innerBody = bodyOf(inner.stmt());

        Lookup lookup = findLookup(innerBody, inner.derived());
        List<String> ids = new ArrayList<>(outer.nodeIds());
        ids.addAll(inner.nodeIds());

        if (lookup != null) {
            Map<String, Object> finding = finding("repeated_collection_scan", "warning",
                    "Repeated linear scan of '" + collection + "' inside a loop",
                    "The loop over '" + collection + "' on line " + json.range(inner.stmt()).get("line")
                            + " searches for a matching element, and it runs once per iteration of the enclosing loop. "
                            + "Each search is O(n), so the nested work grows as O(n*m) (O(n^2) when both loops cover the same data).",
                    lookupRecommendation(lookup, collection, outer), inner.stmt(), ids);
            findings.add(finding);
        } else if (outer.factor().key().equals(f.key()) && !f.viaEnv()) {
            findings.add(finding("nested_collection_iteration", "info",
                    "Collection '" + collection + "' is iterated inside a loop over the same collection",
                    "Every element of '" + collection + "' is visited once per element of '" + collection
                            + "', so the work grows quadratically (O(n^2)).",
                    "If this is a search by key, build an index once instead; if all pairs are needed, the quadratic cost is inherent.",
                    inner.stmt(), ids));
        }
    }

    private static final String STRING_CONCAT_NOTE = "A String is extended inside a loop: each concatenation copies the string built so far, "
            + "so the real cost also grows with the final string length (up to quadratic); this estimate counts iterations only.";

    /**
     * Reports {@code s += x} / {@code s = s + x} on a String declared outside the loop (not inside nested loops, which
     * are reported on their own). Returns whether such a concatenation was found.
     */
    private boolean detectStringConcatenation(LoopInfo loop, Statement body) {
        Set<String> declaredInside = new HashSet<>();
        List<Assignment> concatenations = new ArrayList<>();
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(ForStatement n) {
                return false;
            }

            @Override
            public boolean visit(EnhancedForStatement n) {
                return false;
            }

            @Override
            public boolean visit(WhileStatement n) {
                return false;
            }

            @Override
            public boolean visit(DoStatement n) {
                return false;
            }

            @Override
            public boolean visit(VariableDeclarationFragment f) {
                if (f.resolveBinding() != null) {
                    declaredInside.add(f.resolveBinding().getKey());
                }
                return true;
            }

            @Override
            public boolean visit(Assignment a) {
                if (strip(a.getLeftHandSide()) instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding vb
                        && !vb.isField() && vb.getType().getQualifiedName().equals("java.lang.String")) {
                    boolean plusAssign = a.getOperator() == Assignment.Operator.PLUS_ASSIGN;
                    boolean selfConcat = a.getOperator() == Assignment.Operator.ASSIGN
                            && strip(a.getRightHandSide()) instanceof InfixExpression ie
                            && ie.getOperator() == InfixExpression.Operator.PLUS
                            && strip(ie.getLeftOperand()) instanceof SimpleName left && vb.isEqualTo(left.resolveBinding());
                    if ((plusAssign || selfConcat) && !declaredInside.contains(vb.getKey())) {
                        concatenations.add(a);
                    }
                }
                return true;
            }
        });
        if (concatenations.isEmpty()) {
            return false;
        }
        Assignment first = concatenations.get(0);
        String variable = norm(json.text(first.getLeftHandSide()));
        findings.add(finding("string_concat_in_loop", "warning",
                "String '" + variable + "' is extended inside a loop",
                "Strings are immutable: every concatenation on line " + json.range(first).get("line")
                        + " copies all characters collected so far, so building the result takes up to quadratic time in its length.",
                "Collect the pieces in a StringBuilder and call toString() once after the loop (or use String.join / Collectors.joining).",
                first, new ArrayList<>(loop.nodeIds())));
        return true;
    }

    private Map<String, Object> finding(String id, String severity, String message, String explanation,
                                        String recommendation, ASTNode at, List<String> nodeIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("severity", severity);
        m.put("category", "complexity");
        m.put("message", message);
        m.put("explanation", explanation);
        m.put("recommendation", recommendation);
        m.put("source_range", json.range(at));
        m.put("node_ids", nodeIds);
        return m;
    }

    /** An equality test between something derived from the scanned element and something that is not. */
    private record Lookup(boolean firstMatchOnly, String unsafeReason) {
    }

    private Lookup findLookup(Statement innerBody, Set<String> derived) {
        Set<String> inner = new LinkedHashSet<>(derived);
        // locals initialised from the scanned element are aliases of it
        innerBody.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment f) {
                if (f.getInitializer() != null && f.resolveBinding() != null && mentions(f.getInitializer(), inner)) {
                    inner.add(f.resolveBinding().getKey());
                }
                return true;
            }
        });
        Lookup[] result = {null};
        innerBody.accept(new ASTVisitor() {
            @Override
            public boolean visit(IfStatement st) {
                if (result[0] != null) {
                    return false;
                }
                Expression cond = strip(st.getExpression());
                Expression a = null;
                Expression b = null;
                boolean identity = false;
                if (cond instanceof InfixExpression ie && ie.getOperator() == InfixExpression.Operator.EQUALS
                        && !ie.hasExtendedOperands()) {
                    a = strip(ie.getLeftOperand());
                    b = strip(ie.getRightOperand());
                    identity = true;
                } else if (cond instanceof MethodInvocation mi && mi.getName().getIdentifier().equals("equals")
                        && mi.arguments().size() == 1 && mi.getExpression() != null) {
                    a = strip(mi.getExpression());
                    b = strip((Expression) mi.arguments().get(0));
                } else if (cond instanceof MethodInvocation mi && mi.getName().getIdentifier().equals("equals")
                        && mi.arguments().size() == 2 && mi.getExpression() != null
                        && norm(json.text(mi.getExpression())).equals("Objects")) {
                    a = strip((Expression) mi.arguments().get(0));
                    b = strip((Expression) mi.arguments().get(1));
                }
                if (a == null || mentions(a, inner) == mentions(b, inner)) {
                    return true;
                }
                // comparing with a constant (x == 0, s.equals("a")) filters elements; it is not a keyed lookup
                Expression other = mentions(a, inner) ? b : a;
                if (other.resolveConstantExpressionValue() != null || other instanceof NullLiteral) {
                    return true;
                }
                String unsafe = null;
                if (identity) {
                    ITypeBinding ta = a.resolveTypeBinding();
                    ITypeBinding tb = b.resolveTypeBinding();
                    if (ta == null || tb == null) {
                        unsafe = "the compared types are unresolved";
                    } else if (Set.of("float", "double").contains(ta.getName()) || Set.of("float", "double").contains(tb.getName())) {
                        unsafe = "floating-point '==' is not the same as hash-key equality";
                    } else if (!ta.isPrimitive() && !tb.isPrimitive() && !ta.isEnum() && !tb.isEnum()
                            && !(a instanceof NullLiteral) && !(b instanceof NullLiteral)) {
                        unsafe = "reference '==' compares identity, while a Map compares with equals()";
                    }
                }
                result[0] = new Lookup(exitsLoop(st.getThenStatement()), unsafe);
                return false;
            }
        });
        return result[0];
    }

    private String lookupRecommendation(Lookup l, String collection, LoopInfo outer) {
        if (l.unsafeReason() != null) {
            return "A Map index is not recommended here: " + l.unsafeReason() + ".";
        }
        if (mutates(outer.stmt(), collection)) {
            return "A Map index is not recommended here: '" + collection
                    + "' is modified inside the enclosing loop, so an index built beforehand would go stale.";
        }
        if (l.firstMatchOnly()) {
            return "Build a Map keyed by the compared value from '" + collection + "' once, before the outer loop "
                    + "(putIfAbsent keeps the first match), and replace the inner scan by a get(). "
                    + "Assumes keys have consistent equals()/hashCode() and '" + collection + "' is not modified meanwhile.";
        }
        return "The inner loop does not stop at the first match, so index '" + collection
                + "' as a Map<Key, List<Element>> (group by the compared value) built once before the outer loop. "
                + "Assumes keys have consistent equals()/hashCode() and '" + collection + "' is not modified meanwhile.";
    }

    private static boolean exitsLoop(Statement then) {
        boolean[] exits = {false};
        then.accept(new ASTVisitor() {
            @Override
            public boolean visit(BreakStatement b) {
                exits[0] = true;
                return false;
            }

            @Override
            public boolean visit(ReturnStatement r) {
                exits[0] = true;
                return false;
            }

            @Override
            public boolean visit(ForStatement n) {
                return false;
            }

            @Override
            public boolean visit(WhileStatement n) {
                return false;
            }

            @Override
            public boolean visit(EnhancedForStatement n) {
                return false;
            }

            @Override
            public boolean visit(DoStatement n) {
                return false;
            }
        });
        return exits[0];
    }

    private boolean mentions(Expression e, Set<String> variableKeys) {
        boolean[] found = {false};
        e.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName n) {
                if (n.resolveBinding() instanceof IVariableBinding vb && variableKeys.contains(vb.getKey())) {
                    found[0] = true;
                }
                return true;
            }
        });
        return found[0];
    }

    private static Statement bodyOf(Statement loop) {
        return switch (loop) {
            case ForStatement s -> s.getBody();
            case EnhancedForStatement s -> s.getBody();
            case WhileStatement s -> s.getBody();
            default -> ((DoStatement) loop).getBody();
        };
    }

    // ---------- rendering ----------

    private Map<String, Object> render(Cost cost) {
        Map<String, String> symbols = new LinkedHashMap<>();
        for (String key : descriptions.keySet()) {
            for (Mono m : cost.monos) {
                if (m.vars().containsKey(key) && !symbols.containsKey(key)) {
                    symbols.put(key, SYMBOLS[Math.min(symbols.size(), SYMBOLS.length - 1)]);
                }
            }
        }
        boolean unknown = !cost.unknown.isEmpty();

        List<String> terms = new ArrayList<>();
        for (Mono m : cost.monos) {
            terms.add(term(m, symbols));
        }
        String time = unknown ? "unknown" : "O(" + String.join(" + ", terms) + ")";

        List<String> assumptions = new ArrayList<>();
        if (!unknown) {
            symbols.forEach((key, sym) -> assumptions.add(sym + " = " + descriptions.get(key)));
            assumptions.addAll(cost.notes);
            if (cost.monos.stream().anyMatch(m -> m.vars().values().stream().anyMatch(v -> v[1] > 0))) {
                assumptions.add("Logarithmic loops change their counter by a constant factor each iteration.");
            }
        }

        String reason;
        if (unknown) {
            reason = "Complexity cannot be determined: " + String.join(" ", cost.unknown);
        } else if (symbols.isEmpty()) {
            reason = "No loop depends on input size; only constant-cost operations were found.";
        } else {
            List<String> parts = new ArrayList<>();
            Mono dominant = cost.monos.get(0);
            for (Mono m : cost.monos) {
                if (m.vars().size() > dominant.vars().size()
                        || m.vars().values().stream().mapToInt(v -> v[0] + v[1]).sum()
                        > dominant.vars().values().stream().mapToInt(v -> v[0] + v[1]).sum()) {
                    dominant = m;
                }
            }
            for (Map.Entry<String, int[]> e : dominant.vars().entrySet()) {
                String what = descriptions.get(e.getKey()).replaceFirst("^number of elements in ", "")
                        .replaceFirst("^(value|length|initial value) of ", "");
                int p = e.getValue()[0];
                int l = e.getValue()[1];
                if (p >= 2) {
                    parts.add(p + " nested loops over " + what);
                } else if (p == 1) {
                    parts.add("a loop over " + what);
                }
                if (l > 0) {
                    parts.add("a logarithmic loop on " + what);
                }
            }
            reason = "Dominant term " + term(dominant, symbols) + ": " + String.join(" combined with ", parts)
                    + (cost.monos.size() > 1 ? "; independent terms are summed." : "; sequential loops are additive, the largest wins.");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", time);
        out.put("confidence", unknown ? "low" : cost.weak ? "medium" : "high");
        out.put("reason", reason);
        out.put("assumptions", assumptions);
        return out;
    }

    private static String term(Mono m, Map<String, String> symbols) {
        List<String> poly = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        List<Map.Entry<String, int[]>> entries = new ArrayList<>(m.vars().entrySet());
        List<String> order = new ArrayList<>(symbols.keySet());
        entries.sort(java.util.Comparator.comparingInt(e -> order.indexOf(e.getKey())));
        for (Map.Entry<String, int[]> e : entries) {
            String sym = symbols.getOrDefault(e.getKey(), "x");
            int p = e.getValue()[0];
            int l = e.getValue()[1];
            if (p == 1) {
                poly.add(sym);
            } else if (p > 1) {
                poly.add(sym + "^" + p);
            }
            if (l == 1) {
                logs.add("log " + sym);
            } else if (l > 1) {
                logs.add("log^" + l + " " + sym);
            }
        }
        String a = String.join("*", poly);
        String b = String.join(" ", logs);
        if (a.isEmpty() && b.isEmpty()) {
            return "1";
        }
        return a + (a.isEmpty() || b.isEmpty() ? "" : " ") + b;
    }

    // ---------- helpers ----------

    private String cfgNodeId(String statementKind, int start) {
        if (cfg == null) {
            return null;
        }
        for (Object o : (List<?>) cfg.get("nodes")) {
            Map<?, ?> n = (Map<?, ?>) o;
            if (statementKind.equals(n.get("statement_kind")) && !"expression".equals(n.get("kind"))
                    && n.get("source_range") instanceof Map<?, ?> r && ((Number) r.get("start")).intValue() == start
                    && (n.get("kind").equals("branch") || n.get("kind").equals("join"))) {
                return (String) n.get("id");
            }
        }
        return null;
    }

    private static void flatten(Expression e, List<Expression> out) {
        e = strip(e);
        if (e instanceof InfixExpression ie && ie.getOperator() == InfixExpression.Operator.CONDITIONAL_AND) {
            flatten(ie.getLeftOperand(), out);
            flatten(ie.getRightOperand(), out);
            for (Object o : ie.extendedOperands()) {
                flatten((Expression) o, out);
            }
        } else {
            out.add(e);
        }
    }

    private static Expression strip(Expression e) {
        while (e instanceof ParenthesizedExpression p) {
            e = p.getExpression();
        }
        return e;
    }

    private static String norm(String s) {
        return s.replaceAll("\\s+", "");
    }
}
