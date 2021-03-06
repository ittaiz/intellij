/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.sync.model.idea;

import com.android.builder.model.SourceProvider;
import com.android.sdklib.AndroidVersion;
import com.android.tools.idea.model.AndroidModel;
import com.android.tools.idea.model.ClassJarProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.idea.blaze.android.manifest.ManifestParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * Contains Android-Blaze related state necessary for configuring an IDEA project based on a
 * user-selected build variant.
 */
public class BlazeAndroidModel implements AndroidModel {
  private static final Logger LOG = Logger.getInstance(BlazeAndroidModel.class);

  private Project project;
  private Module module;
  private final File rootDirPath;
  private final SourceProvider sourceProvider;
  private final List<SourceProvider> sourceProviders; // Singleton list of sourceProvider
  private final File moduleManifest;
  private final String resourceJavaPackage;
  private final int androidSdkApiLevel;

  /**
   * Creates a new {@link BlazeAndroidModel}.
   */
  public BlazeAndroidModel(
    Project project,
    Module module,
    File rootDirPath,
    SourceProvider sourceProvider,
    File moduleManifest,
    String resourceJavaPackage,
    int androidSdkApiLevel) {
    this.project = project;
    this.module = module;
    this.rootDirPath = rootDirPath;
    this.sourceProvider = sourceProvider;
    this.sourceProviders = ImmutableList.of(sourceProvider);
    this.moduleManifest = moduleManifest;
    this.resourceJavaPackage = resourceJavaPackage;
    this.androidSdkApiLevel = androidSdkApiLevel;
  }

  @NotNull
  @Override
  public SourceProvider getDefaultSourceProvider() {
    return sourceProvider;
  }

  @NotNull
  @Override
  public List<SourceProvider> getActiveSourceProviders() {
    return sourceProviders;
  }

  @NotNull
  @Override
  public List<SourceProvider> getTestSourceProviders() {
    return sourceProviders;
  }

  @NotNull
  @Override
  public List<SourceProvider> getAllSourceProviders() {
    return sourceProviders;
  }

  @Override
  @NotNull
  public String getApplicationId() {
    String result = null;
    Manifest manifest = ManifestParser.getInstance(project).getManifest(moduleManifest);
    if (manifest != null) {
      result = manifest.getPackage().getValue();
    }
    if (result == null) {
      result = resourceJavaPackage;
    }
    return result;
  }

  @NotNull
  @Override
  public Set<String> getAllApplicationIds() {
    Set<String> applicationIds = Sets.newHashSet();
    applicationIds.add(getApplicationId());
    return applicationIds;
  }

  @Override
  public boolean overridesManifestPackage() {
    return false;
  }

  @Override
  public Boolean isDebuggable() {
    return true;
  }

  @Override
  @Nullable
  public AndroidVersion getMinSdkVersion() {
    return new AndroidVersion(androidSdkApiLevel, null);
  }

  @Nullable
  @Override
  public AndroidVersion getRuntimeMinSdkVersion() {
    return getMinSdkVersion();
  }

  @Nullable
  @Override
  public AndroidVersion getTargetSdkVersion() {
    return null;
  }

  @Nullable
  @Override
  public Integer getVersionCode() {
    return null;
  }

  @NotNull
  @Override
  public File getRootDirPath() {
    return rootDirPath;
  }

  @Override
  public boolean isGenerated(@NotNull VirtualFile file) {
    return false;
  }

  @NotNull
  @Override
  public VirtualFile getRootDir() {
    File rootDirPath = getRootDirPath();
    VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByIoFile(rootDirPath);
    assert virtualFile != null;
    return virtualFile;
  }

  @Override
  public boolean getDataBindingEnabled() {
    return false;
  }

  @Override
  @NotNull
  public ClassJarProvider getClassJarProvider() {
    return new NullClassJarProvider();
  }

  @Override
  @Nullable
  public Long getLastBuildTimestamp(@NotNull Project project) {
    // TODO(jvoung): Coordinate with blaze build actions to be able determine last build time.
    return null;
  }
}
