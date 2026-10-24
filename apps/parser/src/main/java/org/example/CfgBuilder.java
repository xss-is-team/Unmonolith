package org.example;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.LabeledStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.UnionType;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.WhileStatement;

/** Builds a statement-level CFG. Unsupported statements remain visible as opaque blocks. */
final class CfgBuilder {
    private final CompilationUnit unit;
    private final String source;
    private final List<Object> nodes = new ArrayList<>();
    private final List<Object> edges = new ArrayList<>();
    private int nextId;
    private Node exit;

    private record Node(String id) {}
    private record Tail(Node node, String edgeKind) {}
    private record Flow(Node entry, List<Tail> tails) {}
    private record Control(Node breakTarget, Node continueTarget, String label, int finalizerDepth) {}
    private record Handler(Node node, List<ITypeBinding> types, int finalizerDepth) {}
    private record Context(List<Control> controls, List<Handler> catches, List<Block> finalizers) {}
    private record CaseGroup(SwitchCase label, List<Statement> statements) {}

    CfgBuilder(CompilationUnit unit, String source) {
        this.unit = unit;
        this.source = source;
    }

    Map<String, Object> build(String methodId, MethodDeclaration method) {
        Node entry = node("entry", "Вход в метод", method);
        exit = node("exit", "Выход из метода", method);
        Flow body = sequence(method.getBody().statements(), new Context(List.of(), List.of(), List.of()));
        if (body.entry() == null) {
            edge(entry, exit, "next");
        } else {
            edge(entry, body.entry(), "next");
            for (Tail tail : body.tails()) edge(tail.node(), exit, tail.edgeKind());
        }
        return Map.of("method_id", methodId, "nodes", nodes, "edges", edges);
    }

    private Flow sequence(List<?> statements, Context context) {
        Node first = null;
        List<Tail> tails = List.of();
        boolean reachable = true;
        for (Object value : statements) {
            Flow next = build((Statement) value, context);
            if (next.entry() == null) continue;
            if (first == null) first = next.entry();
            if (reachable) {
                for (Tail tail : tails) edge(tail.node(), next.entry(), tail.edgeKind());
                tails = next.tails();
                reachable = !tails.isEmpty();
            }
        }
        return new Flow(first, reachable ? tails : List.of());
    }

    private Flow build(Statement statement, Context context) {
        if (statement instanceof Block block) return sequence(block.statements(), context);
        if (statement instanceof IfStatement branch) return branch(branch, context);
        if (statement instanceof ForStatement loop) return forLoop(loop, context);
        if (statement instanceof EnhancedForStatement loop) return enhancedFor(loop, context);
        if (statement instanceof WhileStatement loop) return whileLoop(loop, context);
        if (statement instanceof DoStatement loop) return doLoop(loop, context);
        if (statement instanceof SwitchStatement selection) return switchStatement(selection, context);
        if (statement instanceof TryStatement guarded) return tryStatement(guarded, context);
        if (statement instanceof ReturnStatement returned) {
            Node node = node("return", text(returned), returned);
            addExceptionEdges(node, returned.getExpression(), context);
            route(node, exit, "return", context, 0);
            return new Flow(node, List.of());
        }
        if (statement instanceof ThrowStatement thrown) {
            Node node = node("throw", text(thrown), thrown);
            routeException(node, thrown.getExpression().resolveTypeBinding(), context);
            return new Flow(node, List.of());
        }
        if (statement instanceof BreakStatement broken) {
            Node node = node("break", text(broken), broken);
            Control target = controlFor(broken.getLabel() == null ? null : broken.getLabel().getIdentifier(), context, false);
            if (target == null) edge(node, exit, "break");
            else route(node, target.breakTarget(), "break", context, target.finalizerDepth());
            return new Flow(node, List.of());
        }
        if (statement instanceof ContinueStatement continued) {
            Node node = node("continue", text(continued), continued);
            Control target = controlFor(continued.getLabel() == null ? null : continued.getLabel().getIdentifier(), context, true);
            if (target == null) edge(node, exit, "continue");
            else route(node, target.continueTarget(), "continue", context, target.finalizerDepth());
            return new Flow(node, List.of());
        }
        if (statement instanceof LabeledStatement labeled) {
            Statement body = labeled.getBody();
            if (body instanceof WhileStatement || body instanceof DoStatement || body instanceof ForStatement || body instanceof EnhancedForStatement) {
                return buildLabeledLoop(labeled, context);
            }
        }
        if (statement instanceof TryStatement) throw new AssertionError("try handled above");
        String kind = statement instanceof SwitchStatement ? "switch" : "statement";
        Node node = node(kind, text(statement), statement);
        addExceptionEdges(node, statement, context);
        return new Flow(node, List.of(new Tail(node, "next")));
    }

    private Flow branch(IfStatement branch, Context context) {
        Node condition = node("if", text(branch.getExpression()), branch.getExpression());
        addExceptionEdges(condition, branch.getExpression(), context);
        Flow yes = build(branch.getThenStatement(), context);
        Flow no = branch.getElseStatement() == null ? new Flow(null, List.of()) : build(branch.getElseStatement(), context);
        List<Tail> tails = new ArrayList<>();
        if (yes.entry() == null) tails.add(new Tail(condition, "true"));
        else {
            edge(condition, yes.entry(), "true");
            tails.addAll(yes.tails());
        }
        if (no.entry() == null) tails.add(new Tail(condition, "false"));
        else {
            edge(condition, no.entry(), "false");
            tails.addAll(no.tails());
        }
        return new Flow(condition, tails);
    }

    private Flow whileLoop(WhileStatement loop, Context outer) {
        Node condition = node("while", text(loop.getExpression()), loop.getExpression());
        addExceptionEdges(condition, loop.getExpression(), outer);
        Node after = node("merge", "После цикла", loop);
        Context context = withControl(outer, new Control(after, condition, null, outer.finalizers().size()));
        Flow body = build(loop.getBody(), context);
        edge(condition, body.entry() == null ? condition : body.entry(), "true");
        edge(condition, after, "false");
        for (Tail tail : body.tails()) edge(tail.node(), condition, "back");
        return new Flow(condition, List.of(new Tail(after, "next")));
    }

    private Flow doLoop(DoStatement loop, Context outer) {
        Node condition = node("do_while", text(loop.getExpression()), loop.getExpression());
        addExceptionEdges(condition, loop.getExpression(), outer);
        Node after = node("merge", "После цикла", loop);
        Context context = withControl(outer, new Control(after, condition, null, outer.finalizers().size()));
        Flow body = build(loop.getBody(), context);
        Node entry = body.entry() == null ? condition : body.entry();
        for (Tail tail : body.tails()) edge(tail.node(), condition, "next");
        edge(condition, entry, "back_true");
        edge(condition, after, "false");
        return new Flow(entry, List.of(new Tail(after, "next")));
    }

    private Flow enhancedFor(EnhancedForStatement loop, Context outer) {
        Node condition = node("foreach", text(loop.getParameter()) + " : " + text(loop.getExpression()), loop);
        addExceptionEdges(condition, loop.getExpression(), outer);
        Node after = node("merge", "После цикла", loop);
        Context context = withControl(outer, new Control(after, condition, null, outer.finalizers().size()));
        Flow body = build(loop.getBody(), context);
        edge(condition, body.entry() == null ? condition : body.entry(), "true");
        edge(condition, after, "false");
        for (Tail tail : body.tails()) edge(tail.node(), condition, "back");
        return new Flow(condition, List.of(new Tail(after, "next")));
    }

    private Flow forLoop(ForStatement loop, Context outer) {
        Node condition = loop.getExpression() == null
                ? node("for", "true", loop)
                : node("for", text(loop.getExpression()), loop.getExpression());
        addExceptionEdges(condition, loop.getExpression(), outer);
        Node after = node("merge", "После цикла", loop);

        List<ASTNode> updates = new ArrayList<>();
        for (Object update : loop.updaters()) updates.add((ASTNode) update);
        Flow updateFlow = operationSequence(updates, outer);
        Node continueTarget = updateFlow.entry() == null ? condition : updateFlow.entry();
        Context context = withControl(outer, new Control(after, continueTarget, null, outer.finalizers().size()));
        Flow body = build(loop.getBody(), context);
        Node bodyTarget = body.entry() == null ? continueTarget : body.entry();
        edge(condition, bodyTarget, "true");
        if (loop.getExpression() != null) edge(condition, after, "false");
        for (Tail tail : body.tails()) edge(tail.node(), continueTarget, "next");
        for (Tail tail : updateFlow.tails()) edge(tail.node(), condition, "back");

        List<ASTNode> initializers = new ArrayList<>();
        for (Object initializer : loop.initializers()) initializers.add((ASTNode) initializer);
        Flow initFlow = operationSequence(initializers, outer);
        if (initFlow.entry() == null) return new Flow(condition, List.of(new Tail(after, "next")));
        for (Tail tail : initFlow.tails()) edge(tail.node(), condition, "next");
        return new Flow(initFlow.entry(), List.of(new Tail(after, "next")));
    }

    private Flow operationSequence(List<? extends ASTNode> expressions, Context context) {
        Node first = null;
        List<Tail> tails = List.of();
        for (ASTNode expression : expressions) {
            Node current = node("statement", text(expression), expression);
            addExceptionEdges(current, expression, context);
            if (first == null) first = current;
            for (Tail tail : tails) edge(tail.node(), current, tail.edgeKind());
            tails = List.of(new Tail(current, "next"));
        }
        return new Flow(first, tails);
    }

    private Flow switchStatement(SwitchStatement selection, Context outer) {
        Node selector = node("switch", text(selection.getExpression()), selection.getExpression());
        addExceptionEdges(selector, selection.getExpression(), outer);
        Node after = node("merge", "После switch", selection);
        List<CaseGroup> groups = new ArrayList<>();
        CaseGroup current = null;
        for (Object value : selection.statements()) {
            Statement item = (Statement) value;
            if (item instanceof SwitchCase label) {
                current = new CaseGroup(label, new ArrayList<>());
                groups.add(current);
            } else if (current != null) current.statements().add(item);
        }
        Context context = withControl(outer, new Control(after, null, null, outer.finalizers().size()));
        List<Tail> previousTails = List.of();
        boolean hasDefault = false;
        for (CaseGroup group : groups) {
            SwitchCase label = group.label();
            Node caseNode = node("case", text(label), label);
            boolean isDefault = label.isDefault();
            hasDefault |= isDefault;
            List<?> labels = label.expressions();
            if (isDefault) edge(selector, caseNode, "default");
            else if (labels.isEmpty()) edge(selector, caseNode, "case");
            else for (Object expression : labels) edge(selector, caseNode, "case: " + text((ASTNode) expression));
            for (Tail tail : previousTails) edge(tail.node(), caseNode, label.isSwitchLabeledRule() ? tail.edgeKind() : "next");

            Flow body = sequence(group.statements(), context);
            if (body.entry() == null) previousTails = List.of(new Tail(caseNode, "next"));
            else {
                edge(caseNode, body.entry(), "next");
                previousTails = body.tails();
            }
            if (label.isSwitchLabeledRule()) {
                for (Tail tail : previousTails) edge(tail.node(), after, tail.edgeKind());
                previousTails = List.of();
            }
        }
        for (Tail tail : previousTails) edge(tail.node(), after, tail.edgeKind());
        if (!hasDefault) edge(selector, after, "no-match");
        return new Flow(selector, List.of(new Tail(after, "next")));
    }

    private Flow tryStatement(TryStatement guarded, Context outer) {
        Node start = node("try", "try", guarded);
        Node after = node("merge", "После try", guarded);
        List<CatchClause> clauses = new ArrayList<>();
        List<Handler> localHandlers = new ArrayList<>();
        for (Object value : guarded.catchClauses()) {
            CatchClause clause = (CatchClause) value;
            clauses.add(clause);
            Node catcher = node("catch", "catch (" + text(clause.getException()) + ")", clause);
            localHandlers.add(new Handler(catcher, catchTypes(clause.getException().getType()), outer.finalizers().size() + 1));
        }
        List<Handler> handlers = new ArrayList<>(localHandlers);
        handlers.addAll(outer.catches());
        List<Block> finalizers = new ArrayList<>(outer.finalizers());
        if (guarded.getFinally() != null) finalizers.add(guarded.getFinally());
        Context protectedContext = new Context(outer.controls(), handlers, finalizers);
        Flow body = sequence(guarded.getBody().statements(), protectedContext);
        List<Tail> normalTails = new ArrayList<>();
        if (body.entry() == null) normalTails.add(new Tail(start, "next"));
        else {
            edge(start, body.entry(), "next");
            normalTails.addAll(body.tails());
        }
        for (int i = 0; i < clauses.size(); i++) {
            CatchClause clause = clauses.get(i);
            Context catchContext = new Context(outer.controls(), outer.catches(), finalizers);
            Flow catchBody = sequence(clause.getBody().statements(), catchContext);
            Node catchNode = localHandlers.get(i).node();
            if (catchBody.entry() == null) normalTails.add(new Tail(catchNode, "next"));
            else {
                edge(catchNode, catchBody.entry(), "next");
                normalTails.addAll(catchBody.tails());
            }
        }
        if (guarded.getFinally() != null) {
            Flow finallyBody = sequence(guarded.getFinally().statements(), outer);
            if (finallyBody.entry() == null) {
                for (Tail tail : normalTails) edge(tail.node(), after, tail.edgeKind());
            } else {
                for (Tail tail : normalTails) edge(tail.node(), finallyBody.entry(), "finally");
                for (Tail tail : finallyBody.tails()) edge(tail.node(), after, tail.edgeKind());
            }
        } else for (Tail tail : normalTails) edge(tail.node(), after, tail.edgeKind());
        return new Flow(start, List.of(new Tail(after, "next")));
    }

    private List<ITypeBinding> catchTypes(Type type) {
        List<?> alternatives = type instanceof UnionType union ? union.types() : List.of(type);
        List<ITypeBinding> result = new ArrayList<>();
        for (Object alternative : alternatives) {
            ITypeBinding binding = ((Type) alternative).resolveBinding();
            if (binding != null) result.add(binding);
        }
        return result;
    }

    private void addExceptionEdges(Node sourceNode, ASTNode root, Context context) {
        if (root == null || context.catches().isEmpty()) return;
        List<ITypeBinding> thrown = new ArrayList<>();
        boolean[] unresolved = { false };
        root.accept(new ASTVisitor() {
            private void collect(IMethodBinding binding) {
                if (binding == null || binding.isRecovered()) unresolved[0] = true;
                else thrown.addAll(List.of(binding.getExceptionTypes()));
            }

            @Override public boolean visit(MethodInvocation call) { collect(call.resolveMethodBinding()); return true; }
            @Override public boolean visit(SuperMethodInvocation call) { collect(call.resolveMethodBinding()); return true; }
            @Override public boolean visit(ClassInstanceCreation call) { collect(call.resolveConstructorBinding()); return true; }
            @Override public boolean visit(org.eclipse.jdt.core.dom.LambdaExpression lambda) { return false; }
            @Override public boolean visit(org.eclipse.jdt.core.dom.AnonymousClassDeclaration declaration) { return false; }
        });
        for (ITypeBinding type : thrown) routeException(sourceNode, type, context);
        if (unresolved[0]) {
            for (Handler handler : context.catches()) routeTo(sourceNode, handler.node(), "exception", context, handler.finalizerDepth());
            route(sourceNode, exit, "exception", context, 0);
        }
    }

    private void routeException(Node sourceNode, ITypeBinding thrown, Context context) {
        Handler match = null;
        if (thrown != null) {
            for (Handler handler : context.catches()) {
                boolean compatible = handler.types().isEmpty();
                for (ITypeBinding caught : handler.types()) {
                    compatible |= thrown.isAssignmentCompatible(caught) || thrown.isSubTypeCompatible(caught);
                }
                if (compatible) { match = handler; break; }
            }
        }
        if (thrown == null) {
            for (Handler handler : context.catches()) {
                routeTo(sourceNode, handler.node(), "exception", context, handler.finalizerDepth());
            }
            route(sourceNode, exit, "exception", context, 0);
        } else if (match == null) route(sourceNode, exit, "exception", context, 0);
        else routeTo(sourceNode, match.node(), "exception", context, match.finalizerDepth());
    }

    private void routeTo(Node sourceNode, Node target, String kind, Context context, int targetFinalizerDepth) {
        routeThroughFinalizers(sourceNode, target, kind, context, targetFinalizerDepth, context.finalizers().size() - 1);
    }

    private void route(Node sourceNode, Node target, String kind, Context context, int targetFinalizerDepth) {
        routeTo(sourceNode, target, kind, context, targetFinalizerDepth);
    }

    private void routeThroughFinalizers(Node sourceNode, Node target, String kind, Context context,
            int targetDepth, int index) {
        if (index < targetDepth) {
            edge(sourceNode, target, kind);
            return;
        }
        Block finalizer = context.finalizers().get(index);
        List<Handler> visibleHandlers = context.catches().stream()
                .filter(handler -> handler.finalizerDepth() <= index)
                .toList();
        Context finalizerContext = new Context(context.controls(), visibleHandlers, context.finalizers().subList(0, index));
        Flow flow = sequence(finalizer.statements(), finalizerContext);
        if (flow.entry() == null) {
            routeThroughFinalizers(sourceNode, target, kind, context, targetDepth, index - 1);
            return;
        }
        edge(sourceNode, flow.entry(), "finally");
        for (Tail tail : flow.tails()) {
            routeThroughFinalizers(tail.node(), target, kind, context, targetDepth, index - 1);
        }
    }

    private Flow buildLabeledLoop(LabeledStatement labeled, Context outer) {
        Statement loop = labeled.getBody();
        // Java labeled continue/break also work through nested loops. The label is attached
        // to the nearest loop context while preserving the enclosing controls.
        return build(loop, withPendingLabel(outer, labeled.getLabel().getIdentifier()));
    }

    private String pendingLabel;

    private Context withPendingLabel(Context context, String label) {
        pendingLabel = label;
        return context;
    }

    private Control controlFor(String label, Context context, boolean continuing) {
        for (int i = context.controls().size() - 1; i >= 0; i--) {
            Control control = context.controls().get(i);
            if (label == null || label.equals(control.label())) {
                if (continuing && control.continueTarget() == null) continue;
                return control;
            }
        }
        return null;
    }

    private Context withControl(Context context, Control control) {
        List<Control> controls = new ArrayList<>(context.controls());
        if (pendingLabel != null) {
            control = new Control(control.breakTarget(), control.continueTarget(), pendingLabel, control.finalizerDepth());
            pendingLabel = null;
        }
        controls.add(control);
        return new Context(controls, context.catches(), context.finalizers());
    }

    private Node node(String kind, String label, ASTNode syntax) {
        String id = "n" + (++nextId);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("kind", kind);
        value.put("label", compact(label));
        value.put("source_range", range(syntax));
        nodes.add(value);
        return new Node(id);
    }

    private void edge(Node source, Node target, String kind) {
        if (source == null || target == null) return;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", source.id());
        value.put("target", target.id());
        value.put("kind", kind);
        edges.add(value);
    }

    private Map<String, Object> range(ASTNode syntax) {
        int start = Math.max(0, syntax.getStartPosition());
        int length = Math.max(0, syntax.getLength());
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("start", start);
        range.put("length", length);
        int line = unit.getLineNumber(start);
        if (line > 0) range.put("line", line);
        return range;
    }

    private String text(ASTNode syntax) {
        int start = syntax.getStartPosition();
        int end = start + syntax.getLength();
        if (start < 0 || end > source.length()) return syntax.toString();
        return source.substring(start, end);
    }

    private String compact(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "…";
    }
}
