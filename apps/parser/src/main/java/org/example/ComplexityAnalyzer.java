package org.example;

import java.util.ArrayList;
import java.util.HashMap;
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
 * of guessing. Only the method's own AST is inspected: no call graph, no data flow.
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
    private static final String ASSUME_STRING = "String/StringBuilder operations are treated as O(1) (short strings).";

    private final AstJson json;
    private final CompilationUnit cu;
    private final MethodDeclaration method;
    private final Map<String, Object> cfg;
    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final List<LoopInfo> stack = new ArrayList<>();
    private final List<Object> findings = new ArrayList<>();
    private final List<Object> fragments = new ArrayList<>();

    ComplexityAnalyzer(AstJson json, CompilationUnit cu, MethodDeclaration method, Map<String, Object> cfg) {
        this.json = json;
        this.cu = cu;
        this.method = method;
        this.cfg = cfg;
    }

    Map<String, Object> analyze() {
        Cost total = block(method.getBody().statements(), Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("complexity", render(total));
        out.put("fragments", fragments);
        out.put("findings", findings);
        return out;
    }

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
                if (ie.hasExtendedOperands()) {
                    return Bound.unknown("Bound '" + text + "' is too complex.");
                }
                Expression l = strip(ie.getLeftOperand());
                Expression r = strip(ie.getRightOperand());
                InfixExpression.Operator op = ie.getOperator();
                boolean arithmetic = op == InfixExpression.Operator.PLUS || op == InfixExpression.Operator.MINUS
                        || op == InfixExpression.Operator.TIMES || op == InfixExpression.Operator.DIVIDE
                        || op == InfixExpression.Operator.REMAINDER;
                if (!arithmetic) {
                    return Bound.unknown("Bound '" + text + "' is not a recognised size.");
                }
                Bound lb = classify(l, env);
                Bound rb = classify(r, env);
                if (lb.kind() == Kind.CONST && rb.kind() == Kind.CONST) {
                    return Bound.CONST;
                }
                if (rb.kind() == Kind.CONST) {
                    return lb;
                }
                if (lb.kind() == Kind.CONST && op != InfixExpression.Operator.MINUS && op != InfixExpression.Operator.DIVIDE) {
                    return rb;
                }
                if (op == InfixExpression.Operator.MINUS && lb.kind() != Kind.UNKNOWN) {
                    return lb;
                }
                if (op == InfixExpression.Operator.DIVIDE && lb.kind() != Kind.UNKNOWN) {
                    return lb;
                }
                return Bound.unknown("Bound '" + text + "' combines several variable sizes.");
            }
            case CastExpression ce -> {
                return classify(ce.getExpression(), env);
            }
            default -> {
                return Bound.unknown("Bound '" + text + "' is not a recognised size.");
            }
        }
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

        Cost modeled = model(recv, decl, name, argc, recvText, mi);
        if (modeled != null) {
            return modeled;
        }
        if (isTrivialAccessor(b)) {
            return Cost.constant();
        }
        String shown = decl.substring(decl.lastIndexOf('.') + 1) + "." + name + "()";
        return Cost.unknown("Call to " + shown + " has no known cost model.");
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
        return Cost.unknown("Constructor of " + name.substring(name.lastIndexOf('.') + 1) + " has no known cost model.");
    }

    /** {@code return field;}, {@code this.f = x;} and record accessors cost O(1). */
    private boolean isTrivialAccessor(IMethodBinding b) {
        ASTNode decl = cu.findDeclaringNode(b.getMethodDeclaration());
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
    private Cost model(String recv, String decl, String name, int argc, String recvText, MethodInvocation mi) {
        switch (decl) {
            case "java.lang.Math", "java.lang.StrictMath", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
                 "java.lang.Boolean", "java.lang.Character", "java.util.Objects" -> {
                return Cost.constant();
            }
            case "java.io.PrintStream" -> {
                return Set.of("println", "print", "printf", "format").contains(name) ? Cost.constant() : null;
            }
            case "java.lang.String" -> {
                return Set.of("length", "charAt", "isEmpty", "equals", "equalsIgnoreCase", "compareTo", "hashCode",
                        "startsWith", "endsWith", "valueOf", "toString").contains(name) ? Cost.constant(ASSUME_STRING) : null;
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
                    default -> null;
                };
            }
            case "java.util.Collections" -> {
                String arg0 = argc > 0 ? norm(json.text((Expression) mi.arguments().get(0))) : "";
                return switch (name) {
                    case "sort" -> nLogN(arg0);
                    case "reverse", "shuffle", "max", "min" -> linearIn(arg0);
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
                if (Set.of("get", "put", "containsKey", "remove", "getOrDefault", "putIfAbsent").contains(name)) {
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

    private Map<String, Object> finding(String id, String severity, String message, String explanation,
                                        String recommendation, ASTNode at, List<String> nodeIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("severity", severity);
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
