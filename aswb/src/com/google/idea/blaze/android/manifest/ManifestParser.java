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
package com.google.idea.blaze.android.manifest;

import com.google.common.collect.Maps;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.settings.BlazeImportSettings;
import com.google.idea.blaze.base.sync.SyncListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.util.ArrayUtil;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.util.AndroidUtils;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

/**
 * Parses manifests from the project.
 */
public class ManifestParser {
  private static final Logger LOG = Logger.getInstance(ManifestParser.class);
  private final Project project;
  private Map<File, Manifest> manifestFileMap = Maps.newHashMap();

  public static ManifestParser getInstance(Project project) {
    return ServiceManager.getService(project, ManifestParser.class);
  }

  public ManifestParser(Project project) {
    this.project = project;
  }

  @Nullable
  public Manifest getManifest(File file) {
    if (!file.exists()) {
      return null;
    }
    Manifest manifest = manifestFileMap.get(file);
    if (manifest != null) {
      return manifest;
    }
    final VirtualFile virtualFile;
    if (ApplicationManager.getApplication().isDispatchThread()) {
       virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
    } else {
       virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file);
    }
    if (virtualFile == null) {
      LOG.error("Could not find manifest: " + file);
      return null;
    }
    manifest = AndroidUtils.loadDomElement(project, virtualFile, Manifest.class);
    manifestFileMap.put(file, manifest);
    return manifest;
  }

  public void refreshManifests(Collection<File> manifestFiles) {
    List<VirtualFile> manifestVirtualFiles = manifestFiles.stream()
      .map(file -> VfsUtil.findFileByIoFile(file, false))
      .filter(Objects::nonNull)
      .collect(Collectors.toList());

    VfsUtil.markDirtyAndRefresh(false, false, false, ArrayUtil.toObjectArray(manifestVirtualFiles, VirtualFile.class));
    ApplicationManager.getApplication().invokeAndWait(
      () -> PsiDocumentManager.getInstance(project).commitAllDocuments(),
      ModalityState.any()
    );
  }

  public static class ClearManifestParser extends SyncListener.Adapter {
    @Override
    public void onSyncComplete(Project project,
                               BlazeImportSettings importSettings,
                               ProjectViewSet projectViewSet,
                               BlazeProjectData blazeProjectData) {
      getInstance(project).manifestFileMap.clear();
    }
  }
}
