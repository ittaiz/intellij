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
package com.google.idea.blaze.base.util;

import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import org.jetbrains.annotations.NotNull;

/**
 * Calculates package prefix from workspace paths.
 */
public final class PackagePrefixCalculator {

  public static String packagePrefixOf(@NotNull WorkspacePath workspacePath) {
    int skipIndex = 0;

    skipIndex = skipIndex == 0 ? skip(workspacePath, "java/") : skipIndex;
    skipIndex = skipIndex == 0 ? skip(workspacePath, "javatests/") : skipIndex;

    return workspacePath.relativePath().substring(skipIndex).replace('/', '.');
  }

  private static int skip(@NotNull WorkspacePath workspacePath, @NotNull String skipString) {
    if (workspacePath.relativePath().startsWith(skipString)) {
      return skipString.length();
    }
    return 0;
  }
}
