/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.frontend;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.j2cl.ast.AstUtils;
import com.google.j2cl.ast.BinaryOperator;
import com.google.j2cl.ast.Expression;
import com.google.j2cl.ast.ExpressionStatement;
import com.google.j2cl.ast.FieldDescriptor;
import com.google.j2cl.ast.JavaType.Kind;
import com.google.j2cl.ast.JsInfo;
import com.google.j2cl.ast.JsMemberType;
import com.google.j2cl.ast.JsUtils;
import com.google.j2cl.ast.Method;
import com.google.j2cl.ast.MethodCall;
import com.google.j2cl.ast.MethodDescriptor;
import com.google.j2cl.ast.PostfixOperator;
import com.google.j2cl.ast.PrefixOperator;
import com.google.j2cl.ast.ReturnStatement;
import com.google.j2cl.ast.Statement;
import com.google.j2cl.ast.TypeDescriptor;
import com.google.j2cl.ast.TypeDescriptor.DescriptorFactory;
import com.google.j2cl.ast.TypeDescriptors;
import com.google.j2cl.ast.Variable;
import com.google.j2cl.ast.Visibility;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Utility functions to manipulate JDT internal representations.
 */
public class JdtUtils {
  // JdtUtil members are all package private. Code outside frontend should not be aware of the
  // dependency on JDT.

  /**
   * The nullability of a package, type, class, etc.
   */
  public enum Nullability {
    NULL,
    NOT_NULL
  }

  static String getCompilationUnitPackageName(CompilationUnit compilationUnit) {
    return compilationUnit.getPackage() == null
        ? ""
        : compilationUnit.getPackage().getName().getFullyQualifiedName();
  }

  static FieldDescriptor createFieldDescriptor(IVariableBinding variableBinding) {
    if (isArrayLengthBinding(variableBinding)) {
      return AstUtils.ARRAY_LENGTH_FIELD_DESCRIPTION;
    }

    boolean isStatic = isStatic(variableBinding);
    Visibility visibility = getVisibility(variableBinding);
    TypeDescriptor enclosingClassTypeDescriptor =
        createTypeDescriptor(variableBinding.getDeclaringClass());
    String fieldName = variableBinding.getName();

    TypeDescriptor thisTypeDescriptor =
        createTypeDescriptorWithNullability(
            variableBinding.getType(),
            variableBinding.getAnnotations(),
            getTypeDefaultNullability(variableBinding.getDeclaringClass()));

    JsInfo jsInfo = JsInteropUtils.getJsInfo(variableBinding);
    boolean isRaw = jsInfo.getJsMemberType() == JsMemberType.PROPERTY;
    boolean isJsOverlay = JsInteropUtils.isJsOverlay(variableBinding);
    boolean isCompileTimeConstant = variableBinding.getConstantValue() != null;
    return FieldDescriptor.create(
        isStatic,
        isRaw,
        visibility,
        enclosingClassTypeDescriptor,
        fieldName,
        thisTypeDescriptor,
        isJsOverlay,
        jsInfo,
        isCompileTimeConstant);
  }

  static Variable createVariable(IVariableBinding variableBinding, Nullability defaultNullability) {
    String name = variableBinding.getName();
    TypeDescriptor typeDescriptor =
        createTypeDescriptorWithNullability(
            variableBinding.getType(), variableBinding.getAnnotations(), defaultNullability);
    boolean isFinal = isFinal(variableBinding);
    boolean isParameter = variableBinding.isParameter();
    return new Variable(name, typeDescriptor, isFinal, isParameter);
  }

  public static BinaryOperator getBinaryOperator(InfixExpression.Operator operator) {
    switch (operator.toString()) {
      case "*":
        return BinaryOperator.TIMES;
      case "/":
        return BinaryOperator.DIVIDE;
      case "%":
        return BinaryOperator.REMAINDER;
      case "+":
        return BinaryOperator.PLUS;
      case "-":
        return BinaryOperator.MINUS;
      case "<<":
        return BinaryOperator.LEFT_SHIFT;
      case ">>":
        return BinaryOperator.RIGHT_SHIFT_SIGNED;
      case ">>>":
        return BinaryOperator.RIGHT_SHIFT_UNSIGNED;
      case "<":
        return BinaryOperator.LESS;
      case ">":
        return BinaryOperator.GREATER;
      case "<=":
        return BinaryOperator.LESS_EQUALS;
      case ">=":
        return BinaryOperator.GREATER_EQUALS;
      case "==":
        return BinaryOperator.EQUALS;
      case "!=":
        return BinaryOperator.NOT_EQUALS;
      case "^":
        return BinaryOperator.BIT_XOR;
      case "&":
        return BinaryOperator.BIT_AND;
      case "|":
        return BinaryOperator.BIT_OR;
      case "&&":
        return BinaryOperator.CONDITIONAL_AND;
      case "||":
        return BinaryOperator.CONDITIONAL_OR;
    }
    return null;
  }

  public static Kind getKindFromTypeBinding(ITypeBinding typeBinding) {
    if (typeBinding.isInterface()) {
      return Kind.INTERFACE;
    } else if (typeBinding.isClass() || typeBinding.isEnum() && typeBinding.isAnonymous()) {
      // Enum values that are anonymous inner classes, are not consider enums classes in
      // our AST, but are considered enum classes by JDT.
      return Kind.CLASS;
    } else if (typeBinding.isEnum()) {
      Preconditions.checkArgument(!typeBinding.isAnonymous());
      return Kind.ENUM;
    }

    throw new RuntimeException("Type binding " + typeBinding + " not handled");
  }

  public static BinaryOperator getBinaryOperator(Assignment.Operator operator) {
    switch (operator.toString()) {
      case "=":
        return BinaryOperator.ASSIGN;
      case "+=":
        return BinaryOperator.PLUS_ASSIGN;
      case "-=":
        return BinaryOperator.MINUS_ASSIGN;
      case "*=":
        return BinaryOperator.TIMES_ASSIGN;
      case "/=":
        return BinaryOperator.DIVIDE_ASSIGN;
      case "&=":
        return BinaryOperator.BIT_AND_ASSIGN;
      case "|=":
        return BinaryOperator.BIT_OR_ASSIGN;
      case "^=":
        return BinaryOperator.BIT_XOR_ASSIGN;
      case "%=":
        return BinaryOperator.REMAINDER_ASSIGN;
      case "<<=":
        return BinaryOperator.LEFT_SHIFT_ASSIGN;
      case ">>=":
        return BinaryOperator.RIGHT_SHIFT_SIGNED_ASSIGN;
      case ">>>=":
        return BinaryOperator.RIGHT_SHIFT_UNSIGNED_ASSIGN;
    }
    return null;
  }

  static IMethodBinding getMethodBinding(
      ITypeBinding typeBinding, String methodName, ITypeBinding... parameterTypes) {

    Queue<ITypeBinding> deque = new LinkedList<>();
    deque.add(typeBinding);

    while (!deque.isEmpty()) {
      typeBinding = deque.poll();
      for (IMethodBinding methodBinding : typeBinding.getDeclaredMethods()) {
        if (methodBinding.getName().equals(methodName)
            && Arrays.equals(methodBinding.getParameterTypes(), parameterTypes)) {
          return methodBinding;
        }
      }
      ITypeBinding superclass = typeBinding.getSuperclass();
      if (superclass != null) {
        deque.add(superclass);
      }

      ITypeBinding[] superInterfaces = typeBinding.getInterfaces();
      if (superInterfaces != null) {
        for (ITypeBinding superInterface : superInterfaces) {
          deque.add(superInterface);
        }
      }
    }
    return null;
  }

  /**
   * Returns the methods that are declared by interfaces of {@code typeBinding} and are not
   * implemented by {@code typeBinding}.
   */
  static List<IMethodBinding> getUnimplementedMethodBindings(ITypeBinding typeBinding) {
    List<IMethodBinding> unimplementedMethodBindings = new ArrayList<>();
    // Only abstract class may have unimplemented methods.
    if (!isAbstract(typeBinding)) {
      return unimplementedMethodBindings;
    }
    for (ITypeBinding superInterface : getAllInterfaces(typeBinding)) {
      unimplementedMethodBindings.addAll(
          getUnimplementedMethodBindings(superInterface, typeBinding));
    }
    return unimplementedMethodBindings;
  }

  /**
   * Returns the methods that are declared by {@code superTypeBinding} and are not implemented by
   * {@code typeBinding}.
   */
  private static List<IMethodBinding> getUnimplementedMethodBindings(
      ITypeBinding superTypeBinding, final ITypeBinding typeBinding) {
    return filterMethodBindings(
        superTypeBinding.getDeclaredMethods(),
        new Predicate<IMethodBinding>() {
          @Override
          public boolean apply(IMethodBinding methodBinding) {
            return !isStatic(methodBinding)
                && !methodBinding.isConstructor()
                && !isImplementedBy(methodBinding, typeBinding);
          }
        });
  }

  /**
   * Returns true if {@code typeBinding} implements an overridden method of {@code methodBinding}.
   */
  private static boolean isImplementedBy(IMethodBinding methodBinding, ITypeBinding typeBinding) {
    if (isDeclaredBy(methodBinding, typeBinding)) {
      return true;
    }
    ITypeBinding superclassTypeBinding = typeBinding.getSuperclass();
    // implemented by its superclass.
    return superclassTypeBinding != null && isImplementedBy(methodBinding, superclassTypeBinding);
  }

  /**
   * Returns the methods in {@code typeBinding}'s interfaces that are accidentally overridden.
   *
   * <p>'Accidentally overridden' means the type itself does not have its own declared overriding
   * method, and the method it inherits does not really override, but just has the same signature
   * of the overridden method.
   */
  static List<IMethodBinding> getAccidentalOverriddenMethodBindings(ITypeBinding typeBinding) {
    List<IMethodBinding> accidentalOverriddenMethods = new ArrayList<>();
    for (ITypeBinding superInterface :
        Sets.difference(
            getAllInterfaces(typeBinding), getAllInterfaces(typeBinding.getSuperclass()))) {
      accidentalOverriddenMethods.addAll(getUndeclaredMethodBindings(superInterface, typeBinding));
    }
    return accidentalOverriddenMethods;
  }

  /**
   * Returns the methods that are declared by {@code superTypeBinding} but are not declared by
   * {@code typeBinding}.
   */
  private static List<IMethodBinding> getUndeclaredMethodBindings(
      ITypeBinding superTypeBinding, final ITypeBinding typeBinding) {
    return filterMethodBindings(
        superTypeBinding.getDeclaredMethods(),
        new Predicate<IMethodBinding>() {
          @Override
          public boolean apply(IMethodBinding methodBinding) {
            return !isDeclaredBy(methodBinding, typeBinding);
          }
        });
  }

  /**
   * Returns true if {@code typeBinding} declares a method with the same signature of
   * {@code methodBinding} in its body.
   */
  private static boolean isDeclaredBy(IMethodBinding methodBinding, ITypeBinding typeBinding) {
    return hasOverrideEquivalentMethod(
        methodBinding, Arrays.asList(typeBinding.getDeclaredMethods()));
  }

  /**
   * Returns true if the given list of method bindings contains a method with the same signature of
   * {@code methodBinding}.
   */
  public static boolean hasOverrideEquivalentMethod(
      IMethodBinding method, List<IMethodBinding> methodBindings) {
    for (IMethodBinding methodBinding : methodBindings) {
      if (areMethodsOverrideEquivalent(method, methodBinding)) {
        return true;
      }
    }
    return false;
  }

  private static List<IMethodBinding> filterMethodBindings(
      IMethodBinding[] methodBindings, Predicate<IMethodBinding> predicate) {
    return FluentIterable.from(methodBindings).filter(predicate).toList();
  }

  /**
   * Returns all the interfaces {@code typeBinding} implements.
   */
  static Set<ITypeBinding> getAllInterfaces(ITypeBinding typeBinding) {
    Set<ITypeBinding> interfaces = new LinkedHashSet<>();
    if (typeBinding == null) {
      return interfaces;
    }
    for (ITypeBinding superInterface : typeBinding.getInterfaces()) {
      interfaces.add(superInterface);
      interfaces.addAll(getAllInterfaces(superInterface));
    }
    ITypeBinding superclassTypeBinding = typeBinding.getSuperclass();
    if (superclassTypeBinding != null) {
      interfaces.addAll(getAllInterfaces(superclassTypeBinding));
    }
    return interfaces;
  }

  /**
   * Returns the nearest method in the super classes of {code typeBinding} that overrides
   * (regularly or accidentally) {@code methodBinding}.
   */
  static IMethodBinding getOverridingMethodInSuperclasses(
      IMethodBinding methodBinding, ITypeBinding typeBinding) {
    ITypeBinding superclass = typeBinding.getSuperclass();
    while (superclass != null) {
      for (IMethodBinding methodInSuperclass : superclass.getDeclaredMethods()) {
        // TODO: excludes package private method, and add a test for it.
        if (areMethodsOverrideEquivalent(methodInSuperclass, methodBinding)) {
          return methodInSuperclass;
        }
      }
      superclass = superclass.getSuperclass();
    }
    return null;
  }

  public static PrefixOperator getPrefixOperator(PrefixExpression.Operator operator) {
    switch (operator.toString()) {
      case "++":
        return PrefixOperator.INCREMENT;
      case "--":
        return PrefixOperator.DECREMENT;
      case "+":
        return PrefixOperator.PLUS;
      case "-":
        return PrefixOperator.MINUS;
      case "~":
        return PrefixOperator.COMPLEMENT;
      case "!":
        return PrefixOperator.NOT;
    }
    return null;
  }

  public static PostfixOperator getPostfixOperator(PostfixExpression.Operator operator) {
    switch (operator.toString()) {
      case "++":
        return PostfixOperator.INCREMENT;
      case "--":
        return PostfixOperator.DECREMENT;
    }
    return null;
  }

  static boolean isStatic(BodyDeclaration bodyDeclaration) {
    return Modifier.isStatic(bodyDeclaration.getModifiers());
  }

  static boolean isArrayLengthBinding(IVariableBinding variableBinding) {
    return variableBinding.getName().equals("length")
        && variableBinding.isField()
        && variableBinding.getDeclaringClass() == null;
  }

  static boolean isJsOverride(IMethodBinding methodBinding) {
    // If the JsMethod is the first in the override chain, it does not override any methods.
    return isOverride(methodBinding) && !isFirstJsMember(methodBinding);
  }

  /**
   * Returns true if the method is the first JsMember in the override chain (does not override any
   * other JsMembers).
   */
  static boolean isFirstJsMember(IMethodBinding methodBinding) {
    return JsInteropUtils.isJsMember(methodBinding)
        && getOverriddenJsMembers(methodBinding).isEmpty();
  }

  static boolean isOverride(IMethodBinding overridingMethod) {
    ITypeBinding type = overridingMethod.getDeclaringClass();

    // Check immediate super class and interfaces for overridden method.
    if (type.getSuperclass() != null && isOveriddenInType(overridingMethod, type.getSuperclass())) {
      return true;
    }
    for (ITypeBinding interfaceBinding : type.getInterfaces()) {
      if (isOveriddenInType(overridingMethod, interfaceBinding)) {
        return true;
      }
    }

    return false;
  }

  private static boolean isOveriddenInType(IMethodBinding overridingMethod, ITypeBinding type) {
    for (IMethodBinding method : type.getDeclaredMethods()) {
      // exposed overriding is not real overriding in JavaScript because the two methods
      // have different method names and they are connected by dispatch method,
      if (overridingMethod.overrides(method.getMethodDeclaration())
          && (!upgradesPackagePrivateVisibility(overridingMethod, method.getMethodDeclaration()))
          && areMethodsOverrideEquivalent(overridingMethod, method.getMethodDeclaration())) {
        return true;
      }
    }

    // Recurse into immediate super class and interfaces for overridden method.
    if (type.getSuperclass() != null && isOveriddenInType(overridingMethod, type.getSuperclass())) {
      return true;
    }
    for (ITypeBinding interfaceBinding : type.getInterfaces()) {
      if (isOveriddenInType(overridingMethod, interfaceBinding)) {
        return true;
      }
    }

    return false;
  }

  static boolean upgradesPackagePrivateVisibility(
      IMethodBinding overridingMethod, IMethodBinding overriddenMethod) {
    Preconditions.checkArgument(overridingMethod.overrides(overriddenMethod));
    Visibility overriddenMethodVisibility = getVisibility(overriddenMethod);
    Visibility overridingMethodVisibility = getVisibility(overridingMethod);
    return overriddenMethodVisibility.isPackagePrivate()
        && (overridingMethodVisibility.isPublic() || overridingMethodVisibility.isProtected());
  }

  /**
   * Two methods are parameter erasure equal if the erasure of their parameters' types are equal.
   * Parameter erasure equal means that they are overriding signature equal, which means that they
   * are real overriding/overridden or accidental overriding/overridden.
   */
  static boolean areMethodsOverrideEquivalent(
      IMethodBinding leftMethod, IMethodBinding rightMethod) {
    return getMethodSignature(leftMethod).equals(getMethodSignature(rightMethod));
  }

  static IMethodBinding findSamMethodBinding(ITypeBinding typeBinding) {
    // TODO: there maybe an issue in which case it inherits a default method from an interface
    // and inherits an abstract method with the same signature from another interface. Add an
    // example to address the potential issue.
    Preconditions.checkArgument(typeBinding.isInterface());
    for (IMethodBinding method : typeBinding.getDeclaredMethods()) {
      if (isAbstract(method)) {
        return method;
      }
    }
    for (ITypeBinding superInterface : typeBinding.getInterfaces()) {
      IMethodBinding samMethodFromSuperInterface = findSamMethodBinding(superInterface);
      if (samMethodFromSuperInterface != null) {
        return samMethodFromSuperInterface;
      }
    }
    return null;
  }

  static Method createSamMethod(
      ITypeBinding lambdaInterfaceBinding, MethodDescriptor lambdaMethodDescriptor) {
    IMethodBinding samMethodBinding = findSamMethodBinding(lambdaInterfaceBinding);
    Preconditions.checkNotNull(samMethodBinding);
    MethodDescriptor samMethodDescriptor = createMethodDescriptor(samMethodBinding);
    List<Variable> parameters = new ArrayList<>();
    List<Expression> arguments = new ArrayList<>();
    List<TypeDescriptor> parameterTypes =
        lambdaMethodDescriptor.getDeclarationMethodDescriptor().getParameterTypeDescriptors();
    for (int i = 0; i < parameterTypes.size(); i++) {
      Variable parameter =
          new Variable("arg" + i, parameterTypes.get(i).getRawTypeDescriptor(), false, true);
      parameters.add(parameter);
      arguments.add(parameter.getReference());
    }
    Expression callLambda = MethodCall.createMethodCall(null, lambdaMethodDescriptor, arguments);
    Statement statement =
        lambdaMethodDescriptor
                .getReturnTypeDescriptor()
                .equalsIgnoreNullability(TypeDescriptors.get().primitiveVoid)
            ? new ExpressionStatement(callLambda)
            : new ReturnStatement(callLambda, samMethodDescriptor.getReturnTypeDescriptor());

    return Method.Builder.fromDefault()
        .setMethodDescriptor(samMethodDescriptor)
        .setParameters(parameters)
        .addStatements(statement)
        .setIsOverride(true)
        .setIsFinal(true)
        .build();
  }

  /**
   * Returns whether {@code subTypeBinding} is a subtype of {@code superTypeBinding} either because
   * subTypeBinding is a child class of class superTypeBinding or because subTypeBinding implements
   * interface superTypeBinding. As 'subtype' is transitive and reflective, a type is subtype of
   * itself.
   */
  static boolean isSubType(ITypeBinding subTypeBinding, ITypeBinding superTypeBinding) {
    if (areSameErasedType(superTypeBinding, subTypeBinding)) {
      return true;
    }
    // Check if it's a child class.
    ITypeBinding superClass = subTypeBinding.getSuperclass();
    if (superClass != null && isSubType(superClass, superTypeBinding)) {
      return true;
    }
    // Check if it implements the interface.
    for (ITypeBinding superInterface : subTypeBinding.getInterfaces()) {
      if (isSubType(superInterface, superTypeBinding)) {
        return true;
      }
    }
    return false;
  }

  static boolean areSameErasedType(ITypeBinding typeBinding, ITypeBinding otherTypeBinding) {
    return typeBinding.getErasure().isEqualTo(otherTypeBinding.getErasure());
  }

  /**
   * Helper method to work around JDT habit of returning raw collections.
   */
  @SuppressWarnings("rawtypes")
  public static <T> List<T> asTypedList(List jdtRawCollection) {
    @SuppressWarnings("unchecked")
    List<T> typedList = jdtRawCollection;
    return typedList;
  }

  /**
   * Returns the method binding of the immediately enclosing method, whether that be an actual
   * method or a lambda expression.
   */
  public static IMethodBinding findCurrentMethodBinding(org.eclipse.jdt.core.dom.ASTNode node) {
    while (true) {
      if (node == null) {
        return null;
      } else if (node instanceof MethodDeclaration) {
        return ((MethodDeclaration) node).resolveBinding();
      } else if (node instanceof LambdaExpression) {
        return ((LambdaExpression) node).resolveMethodBinding();
      }
      node = node.getParent();
    }
  }

  /**
   * Returns the type binding of the immediately enclosing type.
   */
  public static ITypeBinding findCurrentTypeBinding(org.eclipse.jdt.core.dom.ASTNode node) {
    while (true) {
      if (node == null) {
        return null;
      } else if (node instanceof AbstractTypeDeclaration) {
        return ((AbstractTypeDeclaration) node).resolveBinding();
      } else if (node instanceof AnonymousClassDeclaration) {
        return ((AnonymousClassDeclaration) node).resolveBinding();
      }
      node = node.getParent();
    }
  }

  /**
   * Returns whether the ASTNode is in a static context.
   */
  public static boolean isInStaticContext(org.eclipse.jdt.core.dom.ASTNode node) {
    org.eclipse.jdt.core.dom.ASTNode currentNode = node.getParent();
    while (currentNode != null) {
      switch (currentNode.getNodeType()) {
        case ASTNode.FIELD_DECLARATION:
          if (findCurrentTypeBinding(currentNode).isInterface()) {
            // Field declarations in interface are implicitly static.
            return true;
          }
        case ASTNode.METHOD_DECLARATION:
        case ASTNode.INITIALIZER:
          return isStatic((BodyDeclaration) currentNode);
        case ASTNode.ENUM_CONSTANT_DECLARATION: // enum constants are implicitly static.
          return true;
      }
      currentNode = currentNode.getParent();
    }
    return false;
  }

  /**
   * Creates a TypeDescriptor from a JDT TypeBinding.
   */
  // TODO(simionato): Delete this method and make all the callers use
  // createTypeDescriptorWithNullability.
  public static TypeDescriptor createTypeDescriptor(ITypeBinding typeBinding) {
    return createTypeDescriptorWithNullability(
        typeBinding, new IAnnotationBinding[0], Nullability.NULL);
  }

  /**
   * Creates a type descriptor for the given type binding, taking into account nullability.
   *
   * @param typeBinding the type binding, used to create the type descriptor.
   * @param elementAnnotations the annotations on the element
   */
  public static TypeDescriptor createTypeDescriptorWithNullability(
      ITypeBinding typeBinding,
      IAnnotationBinding[] elementAnnotations,
      Nullability defaultNullabilityForCompilationUnit) {
    if (typeBinding == null) {
      return null;
    }
    TypeDescriptor descriptor;
    if (typeBinding.isArray()) {
      TypeDescriptor leafTypeDescriptor =
          createTypeDescriptorWithNullability(
              typeBinding.getElementType(),
              new IAnnotationBinding[0],
              defaultNullabilityForCompilationUnit);
      descriptor = TypeDescriptors.getForArray(leafTypeDescriptor, typeBinding.getDimensions());
    } else if (typeBinding.isParameterizedType()) {
      List<TypeDescriptor> typeArgumentsDescriptors = new ArrayList<>();
      for (ITypeBinding typeArgumentBinding : typeBinding.getTypeArguments()) {
        typeArgumentsDescriptors.add(
            createTypeDescriptorWithNullability(
                typeArgumentBinding,
                new IAnnotationBinding[0],
                defaultNullabilityForCompilationUnit));
      }
      descriptor = createTypeDescriptor(typeBinding, typeArgumentsDescriptors);
    } else {
      descriptor = createTypeDescriptor(typeBinding, null);
    }

    if (isNullable(typeBinding, elementAnnotations, defaultNullabilityForCompilationUnit)) {
      return TypeDescriptors.toNullable(descriptor);
    }
    return TypeDescriptors.toNonNullable(descriptor);
  }

  /**
   * Returns whether the given type binding should be nullable, according to the annotations on it
   * and if nullability is enabled for the package containing the binding.
   */
  private static boolean isNullable(
      ITypeBinding typeBinding,
      IAnnotationBinding[] elementAnnotations,
      Nullability defaultNullabilityForCompilationUnit) {
    Preconditions.checkNotNull(defaultNullabilityForCompilationUnit);
    if (typeBinding.isPrimitive()) {
      return false;
    }
    if (typeBinding.getQualifiedName().equals("java.lang.Void")) {
      // Void is always nullable.
      return true;
    }
    Iterable<IAnnotationBinding> allAnnotations =
        Iterables.concat(
            Arrays.asList(elementAnnotations),
            Arrays.asList(typeBinding.getTypeAnnotations()),
            Arrays.asList(typeBinding.getAnnotations()));
    for (IAnnotationBinding annotation : allAnnotations) {
      String annotationName = annotation.getName();
      // TODO(simionato): Replace those annotations with J2CL-specific annotations
      if (annotationName.equals("Nullable") || annotationName.equals("NullableType")) {
        return true;
      }
      if (annotationName.equalsIgnoreCase("Nonnull")) {
        return false;
      }
    }

    return defaultNullabilityForCompilationUnit == Nullability.NULL
        && !typeBinding.isTypeVariable();
  }

  /**
   * Gets the default nullability for the given type by examining the package-info file in the
   * type's package in the same class path entry.
   */
  public static Nullability getTypeDefaultNullability(ITypeBinding typeBinding) {
    ITypeBinding topLevelTypeBinding = toTopLevelTypeBinding(typeBinding);

    if (topLevelTypeBinding.isFromSource()) {
      // Let the PackageInfoCache know that this class is Source, otherwise it would have to rummage
      // around in the class path to figure it out and it might even come up with the wrong answer
      // for example if this class has also been globbed into some other library that is a
      // dependency of this one.
      PackageInfoCache.get().markAsSource(topLevelTypeBinding.getBinaryName());
    }

    // For a top level class the binary and source name are the same.
    String sourceName = topLevelTypeBinding.getBinaryName();

    boolean nullabilityEnabled = PackageInfoCache.get().isNullabilityEnabled(sourceName);
    return nullabilityEnabled ? Nullability.NOT_NULL : Nullability.NULL;
  }

  /**
   * Incase the given type binding is nested, return the outermost possible enclosing type binding.
   */
  public static ITypeBinding toTopLevelTypeBinding(ITypeBinding typeBinding) {
    ITypeBinding topLevelClass = typeBinding;
    while (topLevelClass.getDeclaringClass() != null) {
      topLevelClass = topLevelClass.getDeclaringClass();
    }
    return topLevelClass;
  }

  private static boolean isIntersectionType(ITypeBinding binding) {
    boolean isIntersectionType =
        !binding.isPrimitive()
            && !binding.isCapture()
            && !binding.isArray()
            && !binding.isTypeVariable()
            && !binding.isWildcardType()
            && binding.getPackage() == null;
    if (isIntersectionType) {
      checkArgument(
          (binding.getSuperclass() != null && binding.getInterfaces().length >= 1)
              || (binding.getSuperclass() == null && binding.getInterfaces().length >= 2));
    }
    return isIntersectionType;
  }

  /**
   * This is a hack to get intersection types working temporarily. We simply take the first type in
   * the intersection and return it. TODO: Find out how to support intersection types in the
   * jscompiler type system and fix this.
   */
  public static ITypeBinding getTypeForIntersectionType(ITypeBinding binding) {
    checkArgument(isIntersectionType(binding));
    return binding.getInterfaces()[0];
  }

  /**
   * Creates a TypeDescriptor from a JDT TypeBinding.
   */
  public static TypeDescriptor createTypeDescriptor(
      ITypeBinding typeBinding, List<TypeDescriptor> typeArgumentDescriptors) {
    if (typeBinding == null) {
      return null;
    }
    if (isIntersectionType(typeBinding)) {
      ITypeBinding intersectionType = getTypeForIntersectionType(typeBinding);
      return createTypeDescriptor(intersectionType, getTypeArgumentTypeDescriptors(typeBinding));
    }
    if (typeBinding.isArray()) {
      TypeDescriptor leafTypeDescriptor = createTypeDescriptor(typeBinding.getElementType());
      return TypeDescriptors.getForArray(leafTypeDescriptor, typeBinding.getDimensions());
    }
    return createForType(typeBinding, typeArgumentDescriptors);
  }

  public static List<String> getPackageComponents(ITypeBinding typeBinding) {
    List<String> packageComponents = new ArrayList<>();
    if (typeBinding.getPackage() != null) {
      packageComponents = Arrays.asList(typeBinding.getPackage().getNameComponents());
    }
    return packageComponents;
  }

  public static List<String> getClassComponents(ITypeBinding typeBinding) {
    List<String> classComponents = new LinkedList<>();
    if (typeBinding.isWildcardType() || typeBinding.isCapture()) {
      return Arrays.asList("?");
    }
    ITypeBinding currentType = typeBinding;
    while (currentType != null) {
      String simpleName;
      if (currentType.isLocal()) {
        // JDT binary name for local class is like package.components.EnclosingClass$1SimpleName
        // Extract the name after the last '$' as the class component here.
        String binaryName = currentType.getErasure().getBinaryName();
        simpleName = binaryName.substring(binaryName.lastIndexOf('$') + 1);
      } else if (currentType.isTypeVariable()) {
        if (currentType.getDeclaringClass() != null) {
          // If it is a class-level type variable, use the simple name (with prefix "C_") as the
          // current name component.
          simpleName = AstUtils.TYPE_VARIABLE_IN_TYPE_PREFIX + currentType.getName();
        } else {
          // If it is a method-level type variable, use the simple name (with prefix "M_") as the
          // current name component, and add declaringClass_declaringMethod as the next name
          // component, and set currentType to declaringClass for the next iteration.
          classComponents.add(0, AstUtils.TYPE_VARIABLE_IN_METHOD_PREFIX + currentType.getName());
          simpleName =
              currentType.getDeclaringMethod().getDeclaringClass().getName()
                  + "_"
                  + currentType.getDeclaringMethod().getName();
          currentType = currentType.getDeclaringMethod().getDeclaringClass();
        }
      } else {
        simpleName = currentType.getErasure().getName();
      }
      classComponents.add(0, simpleName);
      currentType = currentType.getDeclaringClass();
    }
    return classComponents;
  }

  public static List<TypeDescriptor> getTypeArgumentTypeDescriptors(ITypeBinding typeBinding) {
    List<TypeDescriptor> typeArgumentDescriptors = new ArrayList<>();
    if (isIntersectionType(typeBinding)) {
      ITypeBinding intersectionTypeBinding = getTypeForIntersectionType(typeBinding);
      return getTypeArgumentTypeDescriptors(intersectionTypeBinding);
    } else if (typeBinding.isParameterizedType()) {
      typeArgumentDescriptors.addAll(createTypeDescriptors(typeBinding.getTypeArguments()));
    } else {
      typeArgumentDescriptors.addAll(createTypeDescriptors(typeBinding.getTypeParameters()));
      boolean isInstanceNestedClass =
          typeBinding.isNested() && !Modifier.isStatic(typeBinding.getModifiers());
      if (isInstanceNestedClass) {
        if (typeBinding.getDeclaringMethod() != null) {
          typeArgumentDescriptors.addAll(
              createTypeDescriptors(typeBinding.getDeclaringMethod().getTypeParameters()));
        }
        if (typeBinding.getDeclaringMember() == null
            || !Modifier.isStatic(typeBinding.getDeclaringMember().getModifiers())) {
          typeArgumentDescriptors.addAll(
              createTypeDescriptor(typeBinding.getDeclaringClass()).getTypeArgumentDescriptors());
        }
      }
    }
    return typeArgumentDescriptors;
  }

  public static Visibility getVisibility(IBinding binding) {
    return getVisibility(binding.getModifiers());
  }

  public static Visibility getVisibility(int modifiers) {
    if (Modifier.isPublic(modifiers)) {
      return Visibility.PUBLIC;
    } else if (Modifier.isProtected(modifiers)) {
      return Visibility.PROTECTED;
    } else if (Modifier.isPrivate(modifiers)) {
      return Visibility.PRIVATE;
    } else {
      return Visibility.PACKAGE_PRIVATE;
    }
  }

  public static boolean isDefaultMethod(IMethodBinding binding) {
    return Modifier.isDefault(binding.getModifiers());
  }

  public static boolean isAbstract(IBinding binding) {
    return Modifier.isAbstract(binding.getModifiers());
  }

  public static boolean isFinal(IBinding binding) {
    return Modifier.isFinal(binding.getModifiers());
  }

  public static boolean isStatic(IBinding binding) {
    return Modifier.isStatic(binding.getModifiers());
  }

  public static boolean isInstanceMemberClass(ITypeBinding typeBinding) {
    return typeBinding.isMember() && !Modifier.isStatic(typeBinding.getModifiers());
  }

  public static boolean isInstanceNestedClass(ITypeBinding typeBinding) {
    return typeBinding.isNested() && !Modifier.isStatic(typeBinding.getModifiers());
  }

  /**
   * Creates a MethodDescriptor directly based on the given JDT method binding.
   */
  public static MethodDescriptor createMethodDescriptor(IMethodBinding methodBinding) {
    int modifiers = methodBinding.getModifiers();
    boolean isStatic = Modifier.isStatic(modifiers);
    Visibility visibility = getVisibility(methodBinding);
    boolean isNative = Modifier.isNative(modifiers);
    TypeDescriptor enclosingClassTypeDescriptor =
        createTypeDescriptor(methodBinding.getDeclaringClass());
    boolean isConstructor = methodBinding.isConstructor();
    String methodName =
        isConstructor
            ? createTypeDescriptor(methodBinding.getDeclaringClass()).getBinaryClassName()
            : methodBinding.getName();
    final Nullability defaultNullabilityForPackage =
        getTypeDefaultNullability(methodBinding.getDeclaringClass());

    JsInfo jsInfo = computeJsInfo(methodBinding);

    TypeDescriptor returnTypeDescriptor =
        createTypeDescriptorWithNullability(
            methodBinding.getReturnType(),
            methodBinding.getAnnotations(),
            defaultNullabilityForPackage);

    // generate parameters type descriptors.
    List<TypeDescriptor> parameterTypeDescriptors = new ArrayList<>();
    for (int i = 0; i < methodBinding.getParameterTypes().length; i++) {
      TypeDescriptor descriptor =
          createTypeDescriptorWithNullability(
              methodBinding.getParameterTypes()[i],
              methodBinding.getParameterAnnotations(i),
              defaultNullabilityForPackage);
      parameterTypeDescriptors.add(descriptor);
    }

    MethodDescriptor declarationMethodDescriptor = null;
    if (methodBinding.getMethodDeclaration() != methodBinding) {
      declarationMethodDescriptor = createMethodDescriptor(methodBinding.getMethodDeclaration());
    }

    // generate type parameters declared in the method.
    Iterable<TypeDescriptor> typeParameterTypeDescriptors =
        FluentIterable.from(methodBinding.getTypeParameters())
            .transform(
                new Function<ITypeBinding, TypeDescriptor>() {
                  @Override
                  public TypeDescriptor apply(ITypeBinding typeBinding) {
                    return createTypeDescriptor(typeBinding);
                  }
                });

    return MethodDescriptor.Builder.fromDefault()
        .setEnclosingClassTypeDescriptor(enclosingClassTypeDescriptor)
        .setMethodName(methodName)
        .setDeclarationMethodDescriptor(declarationMethodDescriptor)
        .setReturnTypeDescriptor(returnTypeDescriptor)
        .setParameterTypeDescriptors(parameterTypeDescriptors)
        .setTypeParameterTypeDescriptors(typeParameterTypeDescriptors)
        .setJsInfo(jsInfo)
        .setVisibility(visibility)
        .setIsStatic(isStatic)
        .setIsConstructor(isConstructor)
        .setIsNative(isNative)
        .setIsDefault(Modifier.isDefault(methodBinding.getModifiers()))
        .setIsVarargs(methodBinding.isVarargs())
        .build();
  }

  public static boolean isOrOverridesJsMember(IMethodBinding methodBinding) {
    return JsInteropUtils.isJsMember(methodBinding)
        || !getOverriddenJsMembers(methodBinding).isEmpty();
  }

  /**
   * Checks overriding chain to compute JsInfo.
   */
  static JsInfo computeJsInfo(IMethodBinding methodBinding) {
    List<JsInfo> jsInfoList = new ArrayList<>();
    // Add the JsInfo of the method and all the overridden methods to the list.
    JsInfo jsInfo = JsInteropUtils.getJsInfo(methodBinding);
    if (!jsInfo.isNone()) {
      jsInfoList.add(jsInfo);
    }
    for (IMethodBinding overriddenMethod : getOverriddenMethods(methodBinding)) {
      JsInfo inheritedJsInfo = JsInteropUtils.getJsInfo(overriddenMethod);
      if (!inheritedJsInfo.isNone()) {
        jsInfoList.add(inheritedJsInfo);
      }
    }

    if (jsInfoList.isEmpty()) {
      return JsInfo.NONE;
    }

    // TODO: Do the same for JsProperty?
    if (jsInfoList.get(0).getJsMemberType() == JsMemberType.METHOD) {
      // Return the first JsInfo with a Js name specified.
      for (JsInfo jsInfoElement : jsInfoList) {
        if (jsInfoElement.getJsName() != null) {
          return jsInfoElement;
        }
      }
    }
    return jsInfoList.get(0);
  }

  public static Set<IMethodBinding> getOverriddenJsMembers(IMethodBinding methodBinding) {
    return Sets.filter(
        getOverriddenMethods(methodBinding),
        new Predicate<IMethodBinding>() {
          @Override
          public boolean apply(IMethodBinding overriddenMethod) {
            return JsInteropUtils.isJsMember(overriddenMethod);
          }
        });
  }

  /**
   * Returns the method signature, which identifies a method up to overriding.
   */
  public static String getMethodSignature(IMethodBinding methodBinding) {
    StringBuilder signatureBuilder = new StringBuilder("");

    Visibility methodVisibility = getVisibility(methodBinding);
    if (methodVisibility.isPackagePrivate()) {
      signatureBuilder.append(":pp:");
      signatureBuilder.append(methodBinding.getDeclaringClass().getPackage());
      signatureBuilder.append(":");
    } else if (methodVisibility.isPrivate()) {
      signatureBuilder.append(":p:");
      signatureBuilder.append(methodBinding.getDeclaringClass().getBinaryName());
      signatureBuilder.append(":");
    }

    signatureBuilder.append(methodBinding.getName());
    signatureBuilder.append("(");

    String separator = "";
    for (ITypeBinding parameterType : methodBinding.getParameterTypes()) {
      signatureBuilder.append(separator);
      signatureBuilder.append(parameterType.getErasure().getBinaryName());
      separator = ";";
    }
    signatureBuilder.append(")");
    return signatureBuilder.toString();
  }

  public static Set<IMethodBinding> getOverriddenMethods(IMethodBinding methodBinding) {
    Set<IMethodBinding> overriddenMethods = new HashSet<>();
    ITypeBinding enclosingClass = methodBinding.getDeclaringClass();
    ITypeBinding superClass = enclosingClass.getSuperclass();
    if (superClass != null) {
      overriddenMethods.addAll(getOverriddenMethodsInType(methodBinding, superClass));
    }
    for (ITypeBinding superInterface : enclosingClass.getInterfaces()) {
      overriddenMethods.addAll(getOverriddenMethodsInType(methodBinding, superInterface));
    }
    return overriddenMethods;
  }

  public static Set<IMethodBinding> getOverriddenMethodsInType(
      IMethodBinding methodBinding, ITypeBinding typeBinding) {
    Set<IMethodBinding> overriddenMethods = new HashSet<>();
    for (IMethodBinding declaredMethod : typeBinding.getDeclaredMethods()) {
      if (methodBinding.overrides(declaredMethod) && !methodBinding.isConstructor()) {
        checkArgument(!Modifier.isStatic(methodBinding.getModifiers()));
        overriddenMethods.add(declaredMethod);
      }
    }
    // Recurse into immediate super class and interfaces for overridden method.
    if (typeBinding.getSuperclass() != null) {
      overriddenMethods.addAll(
          getOverriddenMethodsInType(methodBinding, typeBinding.getSuperclass()));
    }
    for (ITypeBinding interfaceBinding : typeBinding.getInterfaces()) {
      overriddenMethods.addAll(getOverriddenMethodsInType(methodBinding, interfaceBinding));
    }
    return overriddenMethods;
  }

  public static boolean isLocal(ITypeBinding typeBinding) {
    return typeBinding.isLocal();
  }

  /**
   * Returns true if {@code typeBinding} is a class that implements a JsFunction interface.
   */
  public static boolean isJsFunctionImplementation(ITypeBinding typeBinding) {
    if (typeBinding == null || !typeBinding.isClass()) {
      return false;
    }
    for (ITypeBinding superInterface : typeBinding.getInterfaces()) {
      if (JsInteropUtils.isJsFunction(superInterface)) {
        return true;
      }
    }
    return false;
  }

  public static boolean isEnumOrSubclass(ITypeBinding typeBinding) {
    if (typeBinding == null) {
      return false;
    }
    return typeBinding.isEnum() || isEnumOrSubclass(typeBinding.getSuperclass());
  }

  /**
   * Returns true if the given type has a JsConstructor.
   */
  public static boolean isJsConstructorClass(ITypeBinding typeBinding) {
    if (typeBinding == null || !typeBinding.isClass()) {
      return false;
    }
    Collection<IMethodBinding> constructors =
        Collections2.filter(
            Arrays.asList(typeBinding.getDeclaredMethods()),
            new Predicate<IMethodBinding>() {
              @Override
              public boolean apply(IMethodBinding method) {
                return method.isConstructor();
              }
            });
    if (constructors.isEmpty()
        && Modifier.isPublic(typeBinding.getModifiers())
        && !typeBinding.isEnum()) {
      // A public JsType with default constructor is a JsConstructor class.
      return JsInteropUtils.isJsType(typeBinding);
    }
    return !Collections2.filter(
            constructors,
            new Predicate<IMethodBinding>() {
              @Override
              public boolean apply(IMethodBinding constructor) {
                return JsInteropUtils.isJsConstructor(constructor);
              }
            })
        .isEmpty();
  }

  /**
   * Returns true if the given type has a JsConstructor, or it is a successor of a class that has a
   * JsConstructor.
   */
  public static boolean isOrSubclassesJsConstructorClass(ITypeBinding typeBinding) {
    if (typeBinding == null) {
      return false;
    }
    return isJsConstructorClass(typeBinding)
        || isOrSubclassesJsConstructorClass(typeBinding.getSuperclass());
  }

  /**
   * Returns the MethodDescriptor for the SAM method in JsFunction interface.
   */
  public static MethodDescriptor getJsFunctionMethodDescriptor(ITypeBinding typeBinding) {
    IMethodBinding samInJsFunctionInterface = getSAMInJsFunctionInterface(typeBinding);
    return samInJsFunctionInterface == null
        ? null
        : createMethodDescriptor(samInJsFunctionInterface.getMethodDeclaration());
  }

  /**
   * Returns the MethodDescriptor for the concrete JsFunction method implementation.
   */
  public static MethodDescriptor getConcreteJsFunctionMethodDescriptor(ITypeBinding typeBinding) {
    IMethodBinding samInJsFunctionInterface = getSAMInJsFunctionInterface(typeBinding);
    if (samInJsFunctionInterface == null) {
      return null;
    }
    if (typeBinding.isInterface()) {
      return createMethodDescriptor(samInJsFunctionInterface);
    }
    for (IMethodBinding methodBinding : typeBinding.getDeclaredMethods()) {
      if (methodBinding.isSynthetic()) {
        // skip the synthetic method.
        continue;
      }
      if (methodBinding.overrides(samInJsFunctionInterface)) {
        return createMethodDescriptor(methodBinding);
      }
    }
    return null;
  }

  /**
   * Returns JsFunction method in JsFunction interface.
   */
  private static IMethodBinding getSAMInJsFunctionInterface(ITypeBinding typeBinding) {
    if (!isJsFunctionImplementation(typeBinding) && !JsInteropUtils.isJsFunction(typeBinding)) {
      return null;
    }
    ITypeBinding jsFunctionInterface =
        typeBinding.isInterface() ? typeBinding : typeBinding.getInterfaces()[0];
    Preconditions.checkArgument(
        jsFunctionInterface.getDeclaredMethods().length == 1,
        "Type %s should have one and only one method.",
        jsFunctionInterface.getName());
    return jsFunctionInterface.getDeclaredMethods()[0];
  }

  public static List<TypeDescriptor> createTypeDescriptors(ITypeBinding[] typeBindings) {
    List<TypeDescriptor> typeDescriptors = new ArrayList<>();
    for (ITypeBinding typeBinding : typeBindings) {
      typeDescriptors.add(createTypeDescriptor(typeBinding));
    }
    return typeDescriptors;
  }

  public static void initTypeDescriptors(AST ast) {
    TypeDescriptors typeDescriptors = TypeDescriptors.get();

    // initialize primitive types.
    typeDescriptors.primitiveBoolean =
        createTypeDescriptor(ast.resolveWellKnownType(TypeDescriptors.BOOLEAN_TYPE_NAME));
    typeDescriptors.primitiveByte =
        createTypeDescriptor(ast.resolveWellKnownType(TypeDescriptors.BYTE_TYPE_NAME));
    typeDescriptors.primitiveChar =
        createTypeDescriptor(ast.resolveWellKnownType(TypeDescriptors.CHAR_TYPE_NAME));
    typeDescriptors.primitiveDouble =
        createTypeDescriptor(ast.resolveWellKnownType(TypeDescriptors.DOUBLE_TYPE_NAME));
    typeDescriptors.primitiveFloat =
        createTypeDescriptor(ast.resolveWellKnownType(TypeDescriptors.FLOAT_TYPE_NAME));
    typeDescriptors.primitiveInt =
        createTypeDescriptor(ast.resolveWellKnownType(TypeDescriptors.INT_TYPE_NAME));
    typeDescriptors.primitiveLong =
        createTypeDescriptor(ast.resolveWellKnownType(TypeDescriptors.LONG_TYPE_NAME));
    typeDescriptors.primitiveShort =
        createTypeDescriptor(ast.resolveWellKnownType(TypeDescriptors.SHORT_TYPE_NAME));
    typeDescriptors.primitiveVoid =
        createTypeDescriptor(ast.resolveWellKnownType(TypeDescriptors.VOID_TYPE_NAME));

    // initialize boxed types.
    typeDescriptors.javaLangBoolean =
        createTypeDescriptor(ast.resolveWellKnownType("java.lang.Boolean"));
    typeDescriptors.javaLangByte = createTypeDescriptor(ast.resolveWellKnownType("java.lang.Byte"));
    typeDescriptors.javaLangCharacter =
        createTypeDescriptor(ast.resolveWellKnownType("java.lang.Character"));
    typeDescriptors.javaLangDouble =
        createTypeDescriptor(ast.resolveWellKnownType("java.lang.Double"));
    typeDescriptors.javaLangFloat =
        createTypeDescriptor(ast.resolveWellKnownType("java.lang.Float"));
    typeDescriptors.javaLangInteger =
        createTypeDescriptor(ast.resolveWellKnownType("java.lang.Integer"));
    typeDescriptors.javaLangLong = createTypeDescriptor(ast.resolveWellKnownType("java.lang.Long"));
    typeDescriptors.javaLangShort =
        createTypeDescriptor(ast.resolveWellKnownType("java.lang.Short"));
    typeDescriptors.javaLangString =
        createTypeDescriptor(ast.resolveWellKnownType("java.lang.String"));

    typeDescriptors.javaLangClass =
        createTypeDescriptor(ast.resolveWellKnownType("java.lang.Class"));
    typeDescriptors.javaLangObject =
        createTypeDescriptor(ast.resolveWellKnownType("java.lang.Object"));
    typeDescriptors.javaLangThrowable =
        createTypeDescriptor(ast.resolveWellKnownType("java.lang.Throwable"));

    typeDescriptors.javaLangNumber = createJavaLangNumber(ast);
    typeDescriptors.javaLangComparable = createJavaLangComparable(ast);
    typeDescriptors.javaLangCharSequence = createJavaLangCharSequence(ast);

    typeDescriptors.unknownType =
        TypeDescriptors.createExactly(
            Collections.emptyList(),
            Lists.newArrayList("$$unknown$$"),
            false,
            Collections.emptyList());

    initBoxedPrimitiveTypeMapping(typeDescriptors);
  }

  public static void initBoxedPrimitiveTypeMapping(TypeDescriptors typeDescriptors) {
    TypeDescriptors.addBoxedTypeMapping(
        typeDescriptors.primitiveBoolean, typeDescriptors.javaLangBoolean);
    TypeDescriptors.addBoxedTypeMapping(
        typeDescriptors.primitiveByte, typeDescriptors.javaLangByte);
    TypeDescriptors.addBoxedTypeMapping(
        typeDescriptors.primitiveChar, typeDescriptors.javaLangCharacter);
    TypeDescriptors.addBoxedTypeMapping(
        typeDescriptors.primitiveDouble, typeDescriptors.javaLangDouble);
    TypeDescriptors.addBoxedTypeMapping(
        typeDescriptors.primitiveFloat, typeDescriptors.javaLangFloat);
    TypeDescriptors.addBoxedTypeMapping(
        typeDescriptors.primitiveInt, typeDescriptors.javaLangInteger);
    TypeDescriptors.addBoxedTypeMapping(
        typeDescriptors.primitiveLong, typeDescriptors.javaLangLong);
    TypeDescriptors.addBoxedTypeMapping(
        typeDescriptors.primitiveShort, typeDescriptors.javaLangShort);
  }

  /**
   * Create TypeDescriptor for java.lang.Number, which is not a well known type by JDT.
   */
  public static TypeDescriptor createJavaLangNumber(AST ast) {
    ITypeBinding javaLangInteger = ast.resolveWellKnownType("java.lang.Integer");
    checkNotNull(javaLangInteger);
    TypeDescriptor numberDescriptor = createTypeDescriptor(javaLangInteger.getSuperclass());
    Preconditions.checkState("java.lang.Number".equals(numberDescriptor.getSourceName()));
    return numberDescriptor;
  }

  /**
   * Create TypeDescriptor for java.lang.Comparable, which is not a well known type by JDT.
   */
  public static TypeDescriptor createJavaLangComparable(AST ast) {
    ITypeBinding javaLangInteger = ast.resolveWellKnownType("java.lang.Integer");
    checkNotNull(javaLangInteger);
    ITypeBinding[] interfaces = javaLangInteger.getInterfaces();
    checkArgument(interfaces.length == 1);
    TypeDescriptor comparableDescriptor = createTypeDescriptor(interfaces[0].getErasure());
    Preconditions.checkState("java.lang.Comparable".equals(comparableDescriptor.getSourceName()));
    return comparableDescriptor;
  }

  /**
   * Create TypeDescriptor for java.lang.CharSequence, which is not a well known type by JDT.
   */
  private static TypeDescriptor createJavaLangCharSequence(AST ast) {
    ITypeBinding javaLangString = ast.resolveWellKnownType("java.lang.String");
    checkNotNull(javaLangString);
    ITypeBinding[] interfaces = javaLangString.getInterfaces();
    checkArgument(interfaces.length == 3);
    for (ITypeBinding i : interfaces) {
      if (i.getBinaryName().equals("java.lang.CharSequence")) {
        TypeDescriptor charSequenceDescriptor = createTypeDescriptor(i);
        Preconditions.checkState(
            "java.lang.CharSequence".equals(charSequenceDescriptor.getSourceName()));
        return charSequenceDescriptor;
      }
    }
    return null;
  }

  public static TypeDescriptor createLambda(
      final TypeDescriptor enclosingClassTypeDescriptor,
      String lambdaBinaryName,
      final ITypeBinding lambdaInterfaceBinding) {
    DescriptorFactory<MethodDescriptor> concreteJsFunctionMethodDescriptorFactory =
        new DescriptorFactory<MethodDescriptor>() {
          @Override
          public MethodDescriptor create(TypeDescriptor selfTypeDescriptor) {
            return getConcreteJsFunctionMethodDescriptor(lambdaInterfaceBinding);
          }
        };
    DescriptorFactory<MethodDescriptor> jsFunctionMethodDescriptorFactory =
        new DescriptorFactory<MethodDescriptor>() {
          @Override
          public MethodDescriptor create(TypeDescriptor selfTypeDescriptor) {
            return getJsFunctionMethodDescriptor(lambdaInterfaceBinding);
          }
        };
    DescriptorFactory<TypeDescriptor> rawTypeDescriptorFactory =
        new DescriptorFactory<TypeDescriptor>() {
          @Override
          public TypeDescriptor create(TypeDescriptor selfTypeDescriptor) {
            return TypeDescriptors.replaceTypeArgumentDescriptors(
                selfTypeDescriptor, Collections.<TypeDescriptor>emptyList());
          }
        };
    DescriptorFactory<TypeDescriptor> superTypeDescriptorFactory =
        new DescriptorFactory<TypeDescriptor>() {
          @Override
          public TypeDescriptor create(TypeDescriptor selfTypeDescriptor) {
            return TypeDescriptors.get().javaLangObject;
          }
        };
    DescriptorFactory<TypeDescriptor> enclosingTypeDescriptorFactory =
        new DescriptorFactory<TypeDescriptor>() {
          @Override
          public TypeDescriptor create(TypeDescriptor selfTypeDescriptor) {
            return enclosingClassTypeDescriptor;
          }
        };
    DescriptorFactory<List<TypeDescriptor>> interfacesDescriptorsFactory =
        new DescriptorFactory<List<TypeDescriptor>>() {
          @Override
          public List<TypeDescriptor> create(TypeDescriptor selfTypeDescriptor) {
            return ImmutableList.of(createTypeDescriptor(lambdaInterfaceBinding));
          }
        };

    // Compute these first since they're reused in other calculations.
    List<String> classComponents =
        ImmutableList.copyOf(
            Iterables.concat(
                enclosingClassTypeDescriptor.getClassComponents(),
                Arrays.asList(lambdaBinaryName)));
    List<String> packageComponents = enclosingClassTypeDescriptor.getPackageComponents();
    String simpleName = Iterables.getLast(classComponents);

    // Compute everything else.
    String binaryName =
        Joiner.on(".")
            .join(
                Iterables.concat(
                    packageComponents,
                    Collections.singleton(Joiner.on("$").join(classComponents))));
    String packageName = Joiner.on(".").join(packageComponents);
    String sourceName = Joiner.on(".").join(Iterables.concat(packageComponents, classComponents));

    List<TypeDescriptor> typeArgumentDescriptors =
        new ArrayList<>(createTypeDescriptor(lambdaInterfaceBinding).getAllTypeVariables());
    return new TypeDescriptor.Builder()
        .setBinaryName(binaryName)
        .setClassComponents(classComponents)
        .setConcreteJsFunctionMethodDescriptorFactory(concreteJsFunctionMethodDescriptorFactory)
        .setEnclosingTypeDescriptorFactory(enclosingTypeDescriptorFactory)
        .setIsInstanceNestedClass(true)
        .setIsJsFunctionImplementation(JsInteropUtils.isJsFunction(lambdaInterfaceBinding))
        .setIsLocal(true)
        .setIsNullable(true)
        .setJsFunctionMethodDescriptorFactory(jsFunctionMethodDescriptorFactory)
        .setPackageComponents(packageComponents)
        .setPackageName(packageName)
        .setRawTypeDescriptorFactory(rawTypeDescriptorFactory)
        .setSimpleName(simpleName)
        .setSourceName(sourceName)
        .setInterfacesTypeDescriptorsFactory(interfacesDescriptorsFactory)
        .setSuperTypeDescriptorFactory(superTypeDescriptorFactory)
        .setTypeArgumentDescriptors(typeArgumentDescriptors)
        .setVisibility(Visibility.PRIVATE)
        .build();
  }

  private JdtUtils() {}

  // This is only used by TypeProxyUtils, and cannot be used elsewhere. Because to create a
  // TypeDescriptor from a TypeBinding, it should go through the path to check array type.
  public static TypeDescriptor createForType(
      final ITypeBinding typeBinding, List<TypeDescriptor> overrideTypeArgumentDescriptors) {
    checkArgument(!typeBinding.isArray());

    PackageInfoCache packageInfoCache = PackageInfoCache.get();

    ITypeBinding topLevelTypeBinding = toTopLevelTypeBinding(typeBinding);
    if (topLevelTypeBinding.isFromSource()) {
      // Let the PackageInfoCache know that this class is Source, otherwise it would have to rummage
      // around in the class path to figure it out and it might even come up with the wrong answer
      // for example if this class has also been globbed into some other library that is a
      // dependency of this one.
      PackageInfoCache.get().markAsSource(topLevelTypeBinding.getBinaryName());
    }

    DescriptorFactory<MethodDescriptor> concreteJsFunctionMethodDescriptorFactory =
        new DescriptorFactory<MethodDescriptor>() {
          @Override
          public MethodDescriptor create(TypeDescriptor selfTypeDescriptor) {
            return getConcreteJsFunctionMethodDescriptor(typeBinding);
          }
        };
    DescriptorFactory<TypeDescriptor> enclosingTypeDescriptorFactory =
        new DescriptorFactory<TypeDescriptor>() {
          @Override
          public TypeDescriptor create(TypeDescriptor selfTypeDescriptor) {
            return createTypeDescriptor(typeBinding.getDeclaringClass());
          }
        };
    DescriptorFactory<MethodDescriptor> jsFunctionMethodDescriptorFactory =
        new DescriptorFactory<MethodDescriptor>() {
          @Override
          public MethodDescriptor create(TypeDescriptor selfTypeDescriptor) {
            return getJsFunctionMethodDescriptor(typeBinding);
          }
        };
    DescriptorFactory<TypeDescriptor> rawTypeDescriptorFactory =
        new DescriptorFactory<TypeDescriptor>() {
          @Override
          public TypeDescriptor create(TypeDescriptor selfTypeDescriptor) {
            TypeDescriptor rawTypeDescriptor = createTypeDescriptor(typeBinding.getErasure());
            if (rawTypeDescriptor.isParameterizedType()) {
              return TypeDescriptors.replaceTypeArgumentDescriptors(
                  rawTypeDescriptor, ImmutableList.<TypeDescriptor>of());
            }
            return rawTypeDescriptor;
          }
        };
    DescriptorFactory<TypeDescriptor> superTypeDescriptorFactory =
        new DescriptorFactory<TypeDescriptor>() {
          @Override
          public TypeDescriptor create(TypeDescriptor selfTypeDescriptor) {
            return createTypeDescriptorWithNullability(
                typeBinding.getSuperclass(),
                new IAnnotationBinding[0],
                getTypeDefaultNullability(typeBinding));
          }
        };
    DescriptorFactory<List<TypeDescriptor>> interfacesDescriptorsFactory =
        new DescriptorFactory<List<TypeDescriptor>>() {
          @Override
          public List<TypeDescriptor> create(TypeDescriptor selfTypeDescriptor) {
            ImmutableList.Builder<TypeDescriptor> typeDescriptors = ImmutableList.builder();
            for (ITypeBinding interfaceBinding : typeBinding.getInterfaces()) {
              TypeDescriptor interfaceType =
                  createTypeDescriptorWithNullability(
                      interfaceBinding,
                      new IAnnotationBinding[0],
                      getTypeDefaultNullability(typeBinding));
              typeDescriptors.add(interfaceType);
            }
            return typeDescriptors.build();
          }
        };

    // Compute these first since they're reused in other calculations.
    List<String> classComponents = getClassComponents(typeBinding);
    List<String> packageComponents = getPackageComponents(typeBinding);
    boolean isPrimitive = typeBinding.isPrimitive();
    boolean isTypeVariable = typeBinding.isTypeVariable();
    IAnnotationBinding jsTypeAnnotation = JsInteropAnnotationUtils.getJsTypeAnnotation(typeBinding);
    String simpleName = Iterables.getLast(classComponents);

    // Compute everything else.
    String binaryName =
        Joiner.on(".")
            .join(
                Iterables.concat(
                    packageComponents,
                    Collections.singleton(Joiner.on("$").join(classComponents))));

    if (isTypeVariable) {
      binaryName = binaryName + ":" + typeBinding.getErasure().getBinaryName();
    }

    boolean isNative = JsInteropAnnotationUtils.isNative(jsTypeAnnotation);
    boolean isNullable = !typeBinding.isPrimitive() && !typeBinding.isTypeVariable();
    String jsName = JsInteropAnnotationUtils.getJsName(jsTypeAnnotation);
    String jsNamespace = null;

    // If a package-info file has specified a JsPackage namespace then it is sugar for setting the
    // jsNamespace of all top level types in that package.
    boolean isTopLevelType = typeBinding.getDeclaringClass() == null;
    if (isTopLevelType) {
      String jsPackageNamespace =
          packageInfoCache.getJsNamespace(toTopLevelTypeBinding(typeBinding).getBinaryName());
      if (jsPackageNamespace != null) {
        jsNamespace = jsPackageNamespace;
      }
    }

    String jsTypeNamespace = JsInteropAnnotationUtils.getJsNamespace(jsTypeAnnotation);
    if (jsTypeNamespace != null) {
      jsNamespace = jsTypeNamespace;
    }

    String packageName = Joiner.on(".").join(packageComponents);
    String sourceName = Joiner.on(".").join(Iterables.concat(packageComponents, classComponents));
    List<TypeDescriptor> typeArgumentDescriptors =
        overrideTypeArgumentDescriptors != null
            ? overrideTypeArgumentDescriptors
            : getTypeArgumentTypeDescriptors(typeBinding);

    // Compute these even later
    boolean isExtern = isNative && JsUtils.isGlobal(jsNamespace);
    return new TypeDescriptor.Builder()
        .setBinaryName(binaryName)
        .setClassComponents(classComponents)
        .setConcreteJsFunctionMethodDescriptorFactory(concreteJsFunctionMethodDescriptorFactory)
        .setEnclosingTypeDescriptorFactory(enclosingTypeDescriptorFactory)
        .setInterfacesTypeDescriptorsFactory(interfacesDescriptorsFactory)
        .setIsEnumOrSubclass(isEnumOrSubclass(typeBinding))
        .setIsExtern(isExtern)
        .setIsInstanceMemberClass(isInstanceMemberClass(typeBinding))
        .setIsInstanceNestedClass(isInstanceNestedClass(typeBinding))
        .setIsInterface(typeBinding.isInterface())
        .setIsJsFunction(JsInteropUtils.isJsFunction(typeBinding))
        .setIsJsFunctionImplementation(isJsFunctionImplementation(typeBinding))
        .setIsJsType(jsTypeAnnotation != null)
        .setIsLocal(isLocal(typeBinding))
        .setIsNative(isNative)
        .setIsNullable(isNullable)
        .setIsPrimitive(isPrimitive)
        .setIsRawType(typeBinding.isRawType())
        .setIsTypeVariable(isTypeVariable)
        .setIsWildCard(typeBinding.isWildcardType() || typeBinding.isCapture())
        .setJsFunctionMethodDescriptorFactory(jsFunctionMethodDescriptorFactory)
        .setJsName(jsName)
        .setJsNamespace(jsNamespace)
        .setPackageComponents(packageComponents)
        .setPackageName(packageName)
        .setRawTypeDescriptorFactory(rawTypeDescriptorFactory)
        .setSimpleName(simpleName)
        .setSourceName(sourceName)
        .setIsOrSubclassesJsConstructorClass(isOrSubclassesJsConstructorClass(typeBinding))
        .setSuperTypeDescriptorFactory(superTypeDescriptorFactory)
        .setTypeArgumentDescriptors(typeArgumentDescriptors)
        .setVisibility(getVisibility(typeBinding))
        .build();
  }
}
