/*
 * Copyright 2021 Google Inc.
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
package com.google.j2cl.transpiler.passes;

import com.google.j2cl.transpiler.ast.CompilationUnit;
import com.google.j2cl.transpiler.ast.Expression;
import com.google.j2cl.transpiler.ast.IntersectionTypeDescriptor;
import com.google.j2cl.transpiler.ast.PostfixExpression;
import com.google.j2cl.transpiler.ast.PostfixOperator;
import com.google.j2cl.transpiler.ast.TypeDescriptor;
import com.google.j2cl.transpiler.ast.TypeVariable;
import com.google.j2cl.transpiler.ast.UnionTypeDescriptor;
import com.google.j2cl.transpiler.passes.ConversionContextVisitor.ContextRewriter;

/**
 * Inserts NOT_NULL_ASSERTION (!!) in Kotlin for nullable expressions in places where Kotlin
 * requires a non-null value.
 */
public final class InsertNotNullAssertions extends NormalizationPass {
  @Override
  public void applyTo(CompilationUnit compilationUnit) {
    compilationUnit.accept(
        new ConversionContextVisitor(
            new ContextRewriter() {
              @Override
              public Expression rewriteTypeConversionContext(
                  TypeDescriptor inferredTypeDescriptor,
                  TypeDescriptor actualTypeDescriptor,
                  Expression expression) {
                return !inferredTypeDescriptor.isNullable()
                    ? insertNotNullAssertionIfNeeded(expression)
                    : expression;
              }
            }));
  }

  private static Expression insertNotNullAssertionIfNeeded(Expression expression) {
    return isInstanceNullable(expression.getTypeDescriptor())
        ? PostfixExpression.newBuilder()
            .setOperand(expression)
            .setOperator(PostfixOperator.NOT_NULL_ASSERTION)
            .build()
        : expression;
  }

  private static boolean isInstanceNullable(TypeDescriptor typeDescriptor) {
    if (typeDescriptor.isNullable()) {
      return true;
    }

    if (typeDescriptor instanceof TypeVariable) {
      TypeVariable typeVariable = (TypeVariable) typeDescriptor;
      return typeVariable.getUpperBoundTypeDescriptor().isNullable();
    }

    if (typeDescriptor instanceof UnionTypeDescriptor) {
      UnionTypeDescriptor unionTypeDescriptor = (UnionTypeDescriptor) typeDescriptor;
      return unionTypeDescriptor.getUnionTypeDescriptors().stream()
          .anyMatch(InsertNotNullAssertions::isInstanceNullable);
    }

    if (typeDescriptor instanceof IntersectionTypeDescriptor) {
      IntersectionTypeDescriptor intersectionTypeDescriptor =
          (IntersectionTypeDescriptor) typeDescriptor;
      return intersectionTypeDescriptor.getIntersectionTypeDescriptors().stream()
          .allMatch(InsertNotNullAssertions::isInstanceNullable);
    }

    return false;
  }
}
