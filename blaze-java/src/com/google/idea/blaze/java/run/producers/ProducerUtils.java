/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.java.run.producers;

import com.intellij.execution.Location;
import com.intellij.execution.junit.JUnitUtil;
import com.intellij.execution.junit2.PsiMemberParameterizedLocation;
import com.intellij.execution.junit2.info.MethodLocation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;

/**
 * Copy of {@link org.jetbrains.plugins.gradle.execution.test.runner.TestRunnerUtils}.
 * <p/>
 * <p>Do not modify.
 */
public class ProducerUtils {
  @Nullable
  public static Location<PsiMethod> getMethodLocation(@NotNull Location contextLocation) {
    Location<PsiMethod> methodLocation = getTestMethod(contextLocation);
    if (methodLocation == null) {
      return null;
    }

    if (contextLocation instanceof PsiMemberParameterizedLocation) {
      PsiClass containingClass =
        ((PsiMemberParameterizedLocation)contextLocation).getContainingClass();
      if (containingClass != null) {
        methodLocation = MethodLocation
          .elementInClass(methodLocation.getPsiElement(), containingClass);
      }
    }
    return methodLocation;
  }

  @Nullable
  public static Location<PsiMethod> getTestMethod(final Location<?> location) {
    for (Iterator<Location<PsiMethod>> iterator = location.getAncestors(PsiMethod.class, false);
         iterator.hasNext(); ) {
      final Location<PsiMethod> methodLocation = iterator.next();
      if (JUnitUtil.isTestMethod(methodLocation, false)) {
        return methodLocation;
      }
    }
    return null;
  }
}
