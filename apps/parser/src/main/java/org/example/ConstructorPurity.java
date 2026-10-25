package org.example;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.List;

/**
 * Decides, from source alone, whether {@code new T(...)} of a class declared in the analyzed project only stores its
 * arguments into fields: O(1), cannot throw, cannot touch other objects. Anything else (validation, calls, loops,
 * initializers, a superclass other than {@code Object}, a type outside the project) is not trivial, because the
 * constructor could then run arbitrary code.
 */
final class ConstructorPurity {
    private ConstructorPurity() {
    }

    static boolean isTrivial(ClassInstanceCreation creation, List<CompilationUnit> units) {
        if (creation.getAnonymousClassDeclaration() != null) {
            return false;
        }
        IMethodBinding ctor = creation.resolveConstructorBinding();
        ITypeBinding type = creation.resolveTypeBinding();
        if (ctor == null || type == null || type.isRecovered() || !type.isClass() || type.isAnonymous()) {
            return false;
        }
        type = type.getTypeDeclaration();
        ITypeBinding superclass = type.getSuperclass();
        if (superclass == null || !superclass.getQualifiedName().equals("java.lang.Object")) {
            return false;
        }
        if (!(find(type, units) instanceof TypeDeclaration declaration) || !hasOnlyInertMembers(declaration)) {
            return false;
        }
        if (ctor.isDefaultConstructor()) {
            return true;
        }
        return find(ctor.getMethodDeclaration(), units) instanceof MethodDeclaration md && isStoringConstructor(md, type);
    }

    private static ASTNode find(ITypeBinding type, List<CompilationUnit> units) {
        for (CompilationUnit unit : units) {
            ASTNode node = unit.findDeclaringNode(type);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    private static ASTNode find(IMethodBinding method, List<CompilationUnit> units) {
        for (CompilationUnit unit : units) {
            ASTNode node = unit.findDeclaringNode(method);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    /** No instance initializer blocks and no instance field initializer that is more than a literal. */
    private static boolean hasOnlyInertMembers(TypeDeclaration declaration) {
        for (Object o : declaration.bodyDeclarations()) {
            BodyDeclaration member = (BodyDeclaration) o;
            boolean isStatic = Modifier.isStatic(member.getModifiers());
            if (member instanceof Initializer && !isStatic) {
                return false;
            }
            if (member instanceof FieldDeclaration field && !isStatic) {
                for (Object f : field.fragments()) {
                    Expression init = ((VariableDeclarationFragment) f).getInitializer();
                    if (init != null && !isLiteral(init)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isStoringConstructor(MethodDeclaration md, ITypeBinding type) {
        if (md.getBody() == null) {
            return false;
        }
        for (Object o : md.getBody().statements()) {
            Statement s = (Statement) o;
            if (s instanceof SuperConstructorInvocation sci && sci.arguments().isEmpty()) {
                continue;
            }
            if (!(s instanceof ExpressionStatement es) || !(es.getExpression() instanceof Assignment a)
                    || a.getOperator() != Assignment.Operator.ASSIGN
                    || !isOwnField(a.getLeftHandSide(), type) || !isStorable(a.getRightHandSide())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOwnField(Expression target, ITypeBinding type) {
        Expression t = AstJson.strip(target);
        IVariableBinding vb = null;
        if (t instanceof SimpleName n && n.resolveBinding() instanceof IVariableBinding b) {
            vb = b;
        } else if (t instanceof FieldAccess fa && fa.getExpression() instanceof ThisExpression) {
            vb = fa.resolveFieldBinding();
        }
        return vb != null && vb.isField() && !Modifier.isStatic(vb.getModifiers())
                && vb.getDeclaringClass() != null && vb.getDeclaringClass().getTypeDeclaration().isEqualTo(type);
    }

    /** A literal, or a plain constructor parameter (cannot throw, has no side effects). */
    private static boolean isStorable(Expression value) {
        Expression v = AstJson.strip(value);
        if (isLiteral(v)) {
            return true;
        }
        return v instanceof SimpleName n && n.resolveBinding() instanceof IVariableBinding vb && vb.isParameter();
    }

    private static boolean isLiteral(Expression e) {
        Expression v = AstJson.strip(e);
        return v instanceof NumberLiteral || v instanceof StringLiteral || v instanceof BooleanLiteral
                || v instanceof CharacterLiteral || v instanceof NullLiteral;
    }
}
