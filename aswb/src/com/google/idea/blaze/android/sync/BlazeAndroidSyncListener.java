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
package com.google.idea.blaze.android.sync;

import com.android.tools.idea.res.ResourceFolderRegistry;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.settings.BlazeImportSettings;
import com.google.idea.blaze.base.sync.SyncListener;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;

/**
 * Android-specific hooks to run after a blaze sync.
 */
public class BlazeAndroidSyncListener implements SyncListener {
  @Override
  public void onSyncStart(Project project) {
  }

  @Override
  public void onSyncComplete(Project project,
                             BlazeImportSettings importSettings,
                             ProjectViewSet projectViewSet,
                             BlazeProjectData blazeProjectData) {
  }

  @Override
  public void afterSync(Project project, boolean successful) {
    if (successful) {
      DumbService dumbService = DumbService.getInstance(project);
      dumbService.queueTask(new ResourceFolderRegistry.PopulateCachesTask(project));
    }
  }
}
