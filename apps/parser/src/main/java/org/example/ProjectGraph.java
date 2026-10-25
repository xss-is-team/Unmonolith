package org.example;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.dom.*;

/**
 * Architectural overview of one compilation unit: user-defined types and callables plus the
 * ownership, inheritance, call, construction and type-dependency edges between them. It is built
 * from resolved JDT bindings; calls whose binding is missing or recovered are listed as unresolved,
 * never turned into edges. Control flow stays in the per-method CFG, linked by {@code method_id}.
 */
final class ProjectGraph {
    /** What AstJson emitted for a method: its {@code methods[].id} and, if it has a body, the CFG entry node id. */
    record Link(String methodId, String cfgEntry) {
    }

    private final List<AstJson> units;
    private final Map<ASTNode, AstJson> jsonByUnit = new IdentityHashMap<>();
    private final Map<MethodDeclaration, Link> links;

    private final List<Object> nodes = new ArrayList<>();
    private final Map<String, Map<String, Object>> edges = new LinkedHashMap<>();
    private final List<Object> unresolved = new ArrayList<>();
    private final Map<ASTNode, String> ids = new IdentityHashMap<>();
    private final Map<ASTNode, Map<String, Object>> typeNodes = new IdentityHashMap<>();
    private final Map<String, String> typeByKey = new HashMap<>();
    private final Map<String, String> callableByKey = new HashMap<>();
    private int types;
    private int callables;
    private int internalCalls;
    private int externalCalls;

    /** Graph of one compilation unit. */
    ProjectGraph(AstJson json, CompilationUnit cu, Map<MethodDeclaration, Link> links) {
        this(List.of(json), links);
    }

    /** Graph over several compilation units that were bound together, so cross-file references resolve. */
    ProjectGraph(List<AstJson> units, Map<MethodDeclaration, Link> links) {
        this.units = units;
        this.links = links;
        for (AstJson unit : units) {
            jsonByUnit.put(unit.unit(), unit);
        }
    }

    private Map<String, Object> range(ASTNode node) {
        return jsonByUnit.get(node.getRoot()).range(node);
    }

    private String text(ASTNode node) {
        return jsonByUnit.get(node.getRoot()).text(node);
    }

    private String fileOf(ASTNode node) {
        return jsonByUnit.get(node.getRoot()).file();
    }

    Map<String, Object> build() {
        for (AstJson unit : units) {
            unit.unit().accept(new Declarations());
        }
        for (AstJson unit : units) {
            unit.unit().accept(new Relations());
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("resolved_internal_calls", internalCalls);
        stats.put("external_calls", externalCalls);
        stats.put("unresolved_calls", unresolved.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", 1);
        out.put("scope", units.size() == 1 && units.get(0).file() == null ? "compilation_unit" : "project");
        out.put("nodes", nodes);
        out.put("edges", new ArrayList<>(edges.values()));
        out.put("unresolved_calls", unresolved);
        out.put("stats", stats);
        return out;
    }

    /** Pass 1: assigns ids in source order, so that edges can point forward. */
    private final class Declarations extends ASTVisitor {
        @Override
        public boolean visit(AnonymousClassDeclaration node) {
            return false;
        }

        @Override
        public boolean visit(TypeDeclaration node) {
            return type(node, node.isInterface() ? "interface" : "class");
        }

        @Override
        public boolean visit(EnumDeclaration node) {
            return type(node, "enum");
        }

        @Override
        public boolean visit(RecordDeclaration node) {
            return type(node, "record");
        }

        @Override
        public boolean visit(AnnotationTypeDeclaration node) {
            return type(node, "annotation_type");
        }

        private boolean type(AbstractTypeDeclaration node, String kind) {
            String id = "t" + types++;
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", id);
            n.put("kind", kind);
            n.put("name", node.getName().getIdentifier());
            ITypeBinding tb = node.resolveBinding();
            n.put("qualified_name", tb == null ? null : tb.getQualifiedName());
            n.put("source_range", range(node));
            if (fileOf(node) != null) {
                n.put("file", fileOf(node));
            }
            nodes.add(n);
            ids.put(node, id);
            typeNodes.put(node, n);
            if (tb != null) {
                typeByKey.put(tb.getTypeDeclaration().getKey(), id);
            }
            return true;
        }

        @Override
        public boolean visit(MethodDeclaration node) {
            String id = "c" + callables++;
            Link link = links.get(node);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", id);
            n.put("kind", node.isConstructor() ? "constructor" : "method");
            n.put("name", node.getName().getIdentifier());
            n.put("method_id", link == null ? null : link.methodId());
            n.put("cfg_entry", link == null ? null : link.cfgEntry());
            n.put("source_range", range(node));
            if (fileOf(node) != null) {
                n.put("file", fileOf(node));
            }
            nodes.add(n);
            ids.put(node, id);
            IMethodBinding mb = node.resolveBinding();
            if (mb != null) {
                callableByKey.put(mb.getMethodDeclaration().getKey(), id);
            }
            return true;
        }
    }

    /** Pass 2: edges. The innermost callable (else type) owns a call; the innermost type owns a dependency. */
    private final class Relations extends ASTVisitor {
        private final Deque<String> owners = new ArrayDeque<>();
        private final Deque<String> typeStack = new ArrayDeque<>();

        @Override
        public boolean visit(TypeDeclaration node) {
            return enter(node);
        }

        @Override
        public void endVisit(TypeDeclaration node) {
            leave(node);
        }

        @Override
        public boolean visit(EnumDeclaration node) {
            return enter(node);
        }

        @Override
        public void endVisit(EnumDeclaration node) {
            leave(node);
        }

        @Override
        public boolean visit(RecordDeclaration node) {
            return enter(node);
        }

        @Override
        public void endVisit(RecordDeclaration node) {
            leave(node);
        }

        @Override
        public boolean visit(AnnotationTypeDeclaration node) {
            return enter(node);
        }

        @Override
        public void endVisit(AnnotationTypeDeclaration node) {
            leave(node);
        }

        @Override
        public boolean visit(MethodDeclaration node) {
            return enter(node);
        }

        @Override
        public void endVisit(MethodDeclaration node) {
            leave(node);
        }

        private boolean enter(ASTNode node) {
            String id = ids.get(node);
            if (id == null) {
                return true; // declared inside an anonymous class: attributed to the enclosing owner
            }
            if (!owners.isEmpty()) {
                edge(owners.peek(), id, "contains", null);
            }
            if (node instanceof AbstractTypeDeclaration td) {
                supertypes(td, id);
                typeStack.push(id);
            }
            owners.push(id);
            return true;
        }

        private void leave(ASTNode node) {
            String id = ids.get(node);
            if (id != null) {
                owners.pop();
                if (node instanceof AbstractTypeDeclaration) {
                    typeStack.pop();
                }
            }
        }

        private void supertypes(AbstractTypeDeclaration td, String id) {
            List<Type> parents = new ArrayList<>();
            boolean isInterface = td instanceof TypeDeclaration t && t.isInterface();
            if (td instanceof TypeDeclaration t) {
                if (t.getSuperclassType() != null) {
                    parents.add(t.getSuperclassType());
                }
                parents.addAll(t.superInterfaceTypes());
            } else if (td instanceof EnumDeclaration e) {
                parents.addAll(e.superInterfaceTypes());
            } else if (td instanceof RecordDeclaration r) {
                parents.addAll(r.superInterfaceTypes());
            }
            List<Object> external = new ArrayList<>();
            for (Type parent : parents) {
                ITypeBinding pb = parent.resolveBinding();
                boolean resolved = pb != null && !pb.isRecovered();
                String target = resolved ? typeByKey.get(pb.getTypeDeclaration().getKey()) : null;
                boolean superclass = parent.getLocationInParent() == TypeDeclaration.SUPERCLASS_TYPE_PROPERTY;
                if (target != null) {
                    edge(id, target, superclass || isInterface ? "extends" : "implements", parent);
                } else {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("name", resolved ? pb.getTypeDeclaration().getQualifiedName() : text(parent));
                    e.put("relation", superclass || isInterface ? "extends" : "implements");
                    e.put("resolved", resolved);
                    external.add(e);
                }
            }
            if (!external.isEmpty()) {
                typeNodes.get(td).put("external_supertypes", external);
            }
        }

        @Override
        public boolean visit(SimpleName node) {
            if (node.resolveBinding() instanceof ITypeBinding tb && !typeStack.isEmpty()) {
                dependency(tb, node);
            }
            return false;
        }

        @Override
        public boolean visit(MethodInvocation node) {
            call(node, "method_call", node.getName().getIdentifier(), node.resolveMethodBinding());
            return true;
        }

        @Override
        public boolean visit(SuperMethodInvocation node) {
            call(node, "method_call", node.getName().getIdentifier(), node.resolveMethodBinding());
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation node) {
            call(node, "constructor_call", text(node.getType()), node.resolveConstructorBinding());
            return true;
        }

        @Override
        public boolean visit(ConstructorInvocation node) {
            call(node, "constructor_call", "this", node.resolveConstructorBinding());
            return true;
        }

        @Override
        public boolean visit(SuperConstructorInvocation node) {
            call(node, "constructor_call", "super", node.resolveConstructorBinding());
            return true;
        }

        private void call(ASTNode node, String kind, String name, IMethodBinding b) {
            String from = owners.peek();
            if (from == null) {
                return;
            }
            if (b == null || b.isRecovered()) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("id", "u" + unresolved.size());
                u.put("from", from);
                u.put("kind", kind);
                u.put("name", name);
                u.put("expression", text(node));
                u.put("source_range", range(node));
                u.put("reason", "unresolved_binding");
                unresolved.add(u);
                return;
            }
            boolean construct = kind.equals("constructor_call");
            String target = callableByKey.get(b.getMethodDeclaration().getKey());
            String owner = typeByKey.get(b.getDeclaringClass().getTypeDeclaration().getKey());
            if (target == null && construct) {
                target = owner; // implicit default constructor: the edge ends at the class itself
            }
            if (target == null) {
                externalCalls++;
                return;
            }
            internalCalls++;
            edge(from, target, construct ? "constructs" : "calls", node);
            if (owner != null) {
                dependency(owner, node);
            }
        }

        private void dependency(ITypeBinding tb, ASTNode site) {
            String target = typeByKey.get(tb.getTypeDeclaration().getKey());
            if (target != null) {
                dependency(target, site);
            }
        }

        private void dependency(String target, ASTNode site) {
            if (!typeStack.isEmpty() && !typeStack.peek().equals(target)) {
                edge(typeStack.peek(), target, "depends_on", site);
            }
        }
    }

    /** Edges are keyed by (from, to, kind); every occurrence adds a source range to {@code sites}. */
    @SuppressWarnings("unchecked")
    private void edge(String from, String to, String kind, ASTNode site) {
        Map<String, Object> e = edges.computeIfAbsent(from + ">" + to + ">" + kind, k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", from);
            m.put("to", to);
            m.put("kind", kind);
            m.put("count", 0);
            m.put("sites", new ArrayList<Object>());
            return m;
        });
        e.put("count", (Integer) e.get("count") + 1);
        if (site != null) {
            ((List<Object>) e.get("sites")).add(range(site));
        }
    }
}
