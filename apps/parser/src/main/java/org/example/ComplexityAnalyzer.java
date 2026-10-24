package org.example;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchExpression;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.TypeDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

/** Computes method-level source metrics and the source ranges that contribute to them. */
final class ComplexityAnalyzer {
    private ComplexityAnalyzer() {}

    record Metrics(int cyclomaticComplexity, int cognitiveComplexity, int maxNestingDepth,
            List<Hotspot> hotspots) {}

    record Hotspot(int start, int length, int line, String kind, int cyclomaticIncrement,
            int cognitiveIncrement, int nestingDepth) {}

    static Map<String, Object> toJson(Metrics metrics) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cyclomatic_complexity", metrics.cyclomaticComplexity());
        result.put("cognitive_complexity", metrics.cognitiveComplexity());
        result.put("max_nesting_depth", metrics.maxNestingDepth());
        List<Object> hotspots = new ArrayList<>();
        for (Hotspot hotspot : metrics.hotspots()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source_range", Map.of("start", hotspot.start(), "length", hotspot.length(), "line", hotspot.line()));
            item.put("kind", hotspot.kind());
            item.put("cyclomatic_increment", hotspot.cyclomaticIncrement());
            item.put("cognitive_increment", hotspot.cognitiveIncrement());
            item.put("nesting_depth", hotspot.nestingDepth());
            hotspots.add(item);
        }
        result.put("hotspots", hotspots);
        return result;
    }

    static Metrics measure(CompilationUnit cu, MethodDeclaration method) {
        MetricVisitor visitor = new MetricVisitor(cu);
        if (method.getBody() != null) visitor.scan(method.getBody());
        return visitor.result();
    }

    private record HotspotKey(int start, int length, String kind) {}

    private static final class MutableHotspot {
        private final int start;
        private final int length;
        private final int line;
        private final String kind;
        private int cyclomaticIncrement;
        private int cognitiveIncrement;
        private int nestingDepth;

        private MutableHotspot(int start, int length, int line, String kind) {
            this.start = start;
            this.length = length;
            this.line = line;
            this.kind = kind;
        }

        private Hotspot freeze() {
            return new Hotspot(start, length, line, kind, cyclomaticIncrement, cognitiveIncrement, nestingDepth);
        }
    }

    private static final class MetricVisitor extends ASTVisitor {
        private final CompilationUnit cu;
        private final Map<HotspotKey, MutableHotspot> hotspots = new LinkedHashMap<>();
        private final Set<InfixExpression> countedLogicalExpressions = new HashSet<>();
        private int cyclomatic = 1;
        private int cognitive;
        private int cognitiveNesting;
        private int flowNesting;
        private int maxNestingDepth;

        private MetricVisitor(CompilationUnit cu) {
            this.cu = cu;
        }

        private Metrics result() {
            List<Hotspot> values = hotspots.values().stream()
                    .map(MutableHotspot::freeze)
                    .sorted((left, right) -> Integer.compare(left.start(), right.start()))
                    .toList();
            return new Metrics(cyclomatic, cognitive, maxNestingDepth, values);
        }

        private void scan(ASTNode node) {
            if (node != null) node.accept(this);
        }

        @Override
        public boolean visit(MethodDeclaration node) {
            return false;
        }

        @Override
        public boolean visit(TypeDeclarationStatement node) {
            return false;
        }

        @Override
        public boolean visit(AnonymousClassDeclaration node) {
            return false;
        }

        private void add(ASTNode node, String kind, int cyclomaticIncrement, int cognitiveIncrement, int nesting) {
            int start = node.getStartPosition();
            if (start < 0) return;
            int length = Math.max(0, node.getLength());
            HotspotKey key = new HotspotKey(start, length, kind);
            MutableHotspot hotspot = hotspots.computeIfAbsent(key,
                    ignored -> new MutableHotspot(start, length, Math.max(0, cu.getLineNumber(start)), kind));
            hotspot.cyclomaticIncrement += cyclomaticIncrement;
            hotspot.cognitiveIncrement += cognitiveIncrement;
            hotspot.nestingDepth = Math.max(hotspot.nestingDepth, nesting);
        }

        private void enterControl() {
            cognitiveNesting++;
            flowNesting++;
            maxNestingDepth = Math.max(maxNestingDepth, flowNesting);
        }

        private void leaveControl() {
            cognitiveNesting--;
            flowNesting--;
        }

        private void visitControlBody(ASTNode body) {
            if (body == null) return;
            enterControl();
            scan(body);
            leaveControl();
        }

        @Override
        public boolean visit(IfStatement node) {
            boolean elseIf = node.getParent() instanceof IfStatement parent && parent.getElseStatement() == node;
            int cognitiveIncrement = elseIf ? 1 : cognitiveNesting + 1;
            cyclomatic++;
            cognitive += cognitiveIncrement;
            add(node, elseIf ? "else_if" : "if", 1, cognitiveIncrement, cognitiveNesting);
            scan(node.getExpression());
            visitControlBody(node.getThenStatement());
            if (node.getElseStatement() instanceof IfStatement elseBranch) {
                scan(elseBranch);
            } else if (node.getElseStatement() != null) {
                cognitive++;
                add(node.getElseStatement(), "else", 0, 1, cognitiveNesting);
                visitControlBody(node.getElseStatement());
            }
            return false;
        }

        @Override
        public boolean visit(ForStatement node) {
            cyclomatic++;
            cognitive += cognitiveNesting + 1;
            add(node, "for", 1, cognitiveNesting + 1, cognitiveNesting);
            enterControl();
            node.initializers().forEach(value -> scan((ASTNode) value));
            scan(node.getExpression());
            node.updaters().forEach(value -> scan((ASTNode) value));
            scan(node.getBody());
            leaveControl();
            return false;
        }

        @Override
        public boolean visit(EnhancedForStatement node) {
            cyclomatic++;
            cognitive += cognitiveNesting + 1;
            add(node, "enhanced_for", 1, cognitiveNesting + 1, cognitiveNesting);
            enterControl();
            scan(node.getExpression());
            scan(node.getBody());
            leaveControl();
            return false;
        }

        @Override
        public boolean visit(WhileStatement node) {
            cyclomatic++;
            cognitive += cognitiveNesting + 1;
            add(node, "while", 1, cognitiveNesting + 1, cognitiveNesting);
            enterControl();
            scan(node.getExpression());
            scan(node.getBody());
            leaveControl();
            return false;
        }

        @Override
        public boolean visit(DoStatement node) {
            cyclomatic++;
            cognitive += cognitiveNesting + 1;
            add(node, "do_while", 1, cognitiveNesting + 1, cognitiveNesting);
            enterControl();
            scan(node.getBody());
            scan(node.getExpression());
            leaveControl();
            return false;
        }

        @Override
        public boolean visit(SwitchStatement node) {
            cognitive += cognitiveNesting + 1;
            add(node, "switch", 0, cognitiveNesting + 1, cognitiveNesting);
            enterControl();
            scan(node.getExpression());
            node.statements().forEach(value -> scan((ASTNode) value));
            leaveControl();
            return false;
        }

        @Override
        public boolean visit(SwitchExpression node) {
            cognitive += cognitiveNesting + 1;
            add(node, "switch_expression", 0, cognitiveNesting + 1, cognitiveNesting);
            enterControl();
            scan(node.getExpression());
            node.statements().forEach(value -> scan((ASTNode) value));
            leaveControl();
            return false;
        }

        @Override
        public boolean visit(SwitchCase node) {
            if (!node.isDefault()) {
                cyclomatic++;
                add(node, "case", 1, 0, cognitiveNesting);
            }
            return true;
        }

        @Override
        public boolean visit(TryStatement node) {
            node.resources().forEach(value -> scan((ASTNode) value));
            scan(node.getBody());
            if (!node.catchClauses().isEmpty()) {
                for (Object value : node.catchClauses()) {
                    CatchClause clause = (CatchClause) value;
                    cyclomatic++;
                    cognitive += cognitiveNesting + 1;
                    add(clause, "catch", 1, cognitiveNesting + 1, cognitiveNesting);
                }
                enterControl();
                for (Object value : node.catchClauses()) {
                    CatchClause clause = (CatchClause) value;
                    scan(clause.getBody());
                }
                leaveControl();
            }
            scan(node.getFinally());
            return false;
        }

        @Override
        public boolean visit(ConditionalExpression node) {
            cyclomatic++;
            cognitive += cognitiveNesting + 1;
            add(node, "ternary", 1, cognitiveNesting + 1, cognitiveNesting);
            enterControl();
            scan(node.getExpression());
            scan(node.getThenExpression());
            scan(node.getElseExpression());
            leaveControl();
            return false;
        }

        @Override
        public boolean visit(InfixExpression node) {
            if (isLogical(node) && !countedLogicalExpressions.contains(node) && !hasLogicalParent(node)) {
                List<InfixExpression.Operator> operators = new ArrayList<>();
                collectLogicalOperators(node, operators);
                int runs = 0;
                InfixExpression.Operator previous = null;
                for (InfixExpression.Operator operator : operators) {
                    cyclomatic++;
                    if (!operator.equals(previous)) runs++;
                    previous = operator;
                }
                cognitive += runs;
                add(node, "logical_sequence", operators.size(), runs, cognitiveNesting);
                countedLogicalExpressions.addAll(collectLogicalNodes(node));
            }
            return true;
        }

        @Override
        public boolean visit(BreakStatement node) {
            if (node.getLabel() != null) {
                cognitive++;
                add(node, "labeled_break", 0, 1, cognitiveNesting);
            }
            return true;
        }

        @Override
        public boolean visit(ContinueStatement node) {
            if (node.getLabel() != null) {
                cognitive++;
                add(node, "labeled_continue", 0, 1, cognitiveNesting);
            }
            return true;
        }

        @Override
        public boolean visit(LambdaExpression node) {
            cognitiveNesting++;
            scan(node.getBody());
            cognitiveNesting--;
            return false;
        }

        private boolean hasLogicalParent(InfixExpression node) {
            ASTNode parent = node.getParent();
            while (parent instanceof ParenthesizedExpression) parent = parent.getParent();
            return parent instanceof InfixExpression parentExpression && isLogical(parentExpression);
        }

        private void collectLogicalOperators(InfixExpression node, List<InfixExpression.Operator> operators) {
            collectLogicalPart(node.getLeftOperand(), operators);
            operators.add(node.getOperator());
            collectLogicalPart(node.getRightOperand(), operators);
            for (Object value : node.extendedOperands()) {
                operators.add(node.getOperator());
                collectLogicalPart((ASTNode) value, operators);
            }
        }

        private void collectLogicalPart(ASTNode node, List<InfixExpression.Operator> operators) {
            while (node instanceof ParenthesizedExpression parenthesized) node = parenthesized.getExpression();
            if (node instanceof InfixExpression expression && isLogical(expression)) {
                collectLogicalOperators(expression, operators);
            }
        }

        private Set<InfixExpression> collectLogicalNodes(InfixExpression root) {
            Set<InfixExpression> nodes = new HashSet<>();
            collectLogicalNodes(root, nodes);
            return nodes;
        }

        private void collectLogicalNodes(InfixExpression node, Set<InfixExpression> nodes) {
            nodes.add(node);
            collectLogicalNode(node.getLeftOperand(), nodes);
            collectLogicalNode(node.getRightOperand(), nodes);
            for (Object value : node.extendedOperands()) collectLogicalNode((ASTNode) value, nodes);
        }

        private void collectLogicalNode(ASTNode node, Set<InfixExpression> nodes) {
            while (node instanceof ParenthesizedExpression parenthesized) node = parenthesized.getExpression();
            if (node instanceof InfixExpression expression && isLogical(expression)) collectLogicalNodes(expression, nodes);
        }

        private static boolean isLogical(InfixExpression node) {
            return InfixExpression.Operator.CONDITIONAL_AND.equals(node.getOperator())
                    || InfixExpression.Operator.CONDITIONAL_OR.equals(node.getOperator());
        }
    }

}
