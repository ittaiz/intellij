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
package com.google.idea.blaze.base.sync;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.idea.blaze.base.async.AsyncUtil;
import com.google.idea.blaze.base.async.FutureUtil;
import com.google.idea.blaze.base.async.executor.BlazeExecutor;
import com.google.idea.blaze.base.experiments.ExperimentScope;
import com.google.idea.blaze.base.ideinfo.RuleIdeInfo;
import com.google.idea.blaze.base.metrics.Action;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.SyncState;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.TargetExpression;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.projectview.ProjectViewManager;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.projectview.ProjectViewVerifier;
import com.google.idea.blaze.base.projectview.section.sections.TargetSection;
import com.google.idea.blaze.base.rulemaps.ReverseDependencyMap;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.Scope;
import com.google.idea.blaze.base.scope.output.IssueOutput;
import com.google.idea.blaze.base.scope.output.PrintOutput;
import com.google.idea.blaze.base.scope.output.StatusOutput;
import com.google.idea.blaze.base.scope.scopes.*;
import com.google.idea.blaze.base.settings.Blaze;
import com.google.idea.blaze.base.settings.BlazeImportSettings;
import com.google.idea.blaze.base.settings.BlazeUserSettings;
import com.google.idea.blaze.base.sync.BlazeSyncParams.SyncMode;
import com.google.idea.blaze.base.sync.BlazeSyncPlugin.ModuleEditor;
import com.google.idea.blaze.base.sync.aspects.BlazeIdeInterface;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManagerImpl;
import com.google.idea.blaze.base.sync.projectstructure.ContentEntryEditor;
import com.google.idea.blaze.base.sync.projectstructure.ModuleDataStorage;
import com.google.idea.blaze.base.sync.projectstructure.ModuleEditorImpl;
import com.google.idea.blaze.base.sync.projectstructure.ModuleEditorProvider;
import com.google.idea.blaze.base.sync.projectview.LanguageSupport;
import com.google.idea.blaze.base.sync.projectview.WorkspaceLanguageSettings;
import com.google.idea.blaze.base.sync.workspace.*;
import com.google.idea.blaze.base.util.SaveUtil;
import com.google.idea.blaze.base.vcs.BlazeVcsHandler;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Progressive;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ex.ProjectRootManagerEx;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.StandardFileSystems;
import com.intellij.openapi.vfs.VirtualFileManager;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;


/**
 * Syncs the project with blaze.
 */
final class BlazeSyncTask implements Progressive {

  private final static Logger LOG = Logger.getInstance(BlazeSyncTask.class);

  private final Project project;
  private final BlazeImportSettings importSettings;
  private final WorkspaceRoot workspaceRoot;
  private final BlazeSyncParams syncParams;
  private final boolean expandSyncToWorkingSet;
  private final boolean showPerformanceWarnings;
  private long syncStartTime;

  BlazeSyncTask(
    Project project,
    BlazeImportSettings importSettings,
    final BlazeSyncParams syncParams) {
    this.project = project;
    this.importSettings = importSettings;
    this.workspaceRoot = WorkspaceRoot.fromImportSettings(importSettings);
    this.syncParams = syncParams;
    this.expandSyncToWorkingSet = BlazeUserSettings.getInstance().getExpandSyncToWorkingSet()
                                  && ExpandWorkingSetTargetsExperiment.ENABLE_EXPAND_WORKING_SET_TARGETS.getValue();
    this.showPerformanceWarnings = BlazeUserSettings.getInstance().getShowPerformanceWarnings();
  }


  @Override
  public void run(final ProgressIndicator indicator) {
    Scope.root((BlazeContext context) -> {
      context.push(new ExperimentScope());
      if (showPerformanceWarnings) {
        context.push(new PerformanceWarningScope());
      }
      context
        .push(new ProgressIndicatorScope(indicator))
        .push(new TimingScope("Sync"))
        .push(new LoggedTimingScope(project, Action.SYNC_TOTAL_TIME))
      ;

      if (!syncParams.backgroundSync) {
        context
          .push(new BlazeConsoleScope.Builder(project, indicator).build())
          .push(new IssuesScope(project))
          .push(new NotificationScope(
            project,
            "Sync",
            "Sync project",
            "Sync successful",
            "Sync failed"
          ))
        ;
      }

      context.output(new StatusOutput("Syncing project..."));
      syncProject(context);
    });
  }

  /**
   * Returns true if sync successfully completed
   */
  @VisibleForTesting
  boolean syncProject(BlazeContext context) {
    boolean success = false;
    try {
      SaveUtil.saveAllFiles();
      onSyncStart(project);
      success = doSyncProject(context);
    } catch (AssertionError|Exception e) {
      LOG.error(e);
      IssueOutput.error("Internal error: " + e.getMessage()).submit(context);
    } finally {
      afterSync(project, success);
    }
    return success;
  }

  /**
   * @return true if sync successfully completed
   */
  private boolean doSyncProject(final BlazeContext context) {
    this.syncStartTime = System.currentTimeMillis();

    if (importSettings.getProjectViewFile() == null) {
      IssueOutput.error(
        "This project looks like it's been opened from an old version of ASwB. "
        + "That is unfortunately not supported. Please reimport your project."
      ).submit(context);
      return false;
    }

    @Nullable BlazeProjectData oldBlazeProjectData = null;
    if (syncParams.syncMode != SyncMode.FULL) {
      oldBlazeProjectData = BlazeProjectDataManagerImpl.getImpl(project).loadProjectRoot(context, importSettings);
    }

    BlazeVcsHandler vcsHandler = null;
    for (BlazeVcsHandler candidate : BlazeVcsHandler.EP_NAME.getExtensions()) {
      if (candidate.handlesProject(project, workspaceRoot)) {
        vcsHandler = candidate;
        break;
      }
    }
    if (vcsHandler == null) {
      IssueOutput.error("Could not find a VCS handler").submit(context);
      return false;
    }

    ListeningExecutorService executor = BlazeExecutor.getInstance().getExecutor();
    ListenableFuture<BlazeRoots> blazeRootsFuture = BlazeRoots.compute(project, workspaceRoot, context);
    ListenableFuture<WorkingSet> workingSetFuture = vcsHandler.getWorkingSet(project, workspaceRoot, executor);

    BlazeRoots blazeRoots = FutureUtil.waitForFuture(context, blazeRootsFuture)
      .timed(Blaze.buildSystemName(project) + "Roots")
      .withProgressMessage(String.format("Running %s info...", Blaze.buildSystemName(project)))
      .onError(String.format("Could not get %s roots", Blaze.buildSystemName(project)))
      .run()
      .result();
    if (blazeRoots == null) {
      return false;
    }

    WorkspacePathResolverAndProjectView workspacePathResolverAndProjectView = computeWorkspacePathResolverAndProjectView(
      context,
      blazeRoots,
      vcsHandler,
      executor
    );
    if (workspacePathResolverAndProjectView == null) {
      return false;
    }
    WorkspacePathResolver workspacePathResolver = workspacePathResolverAndProjectView.workspacePathResolver;
    ProjectViewSet projectViewSet = workspacePathResolverAndProjectView.projectViewSet;

    WorkspaceLanguageSettings workspaceLanguageSettings = LanguageSupport.createWorkspaceLanguageSettings(context, projectViewSet);
    if (workspaceLanguageSettings == null) {
      return false;
    }

    if (!ProjectViewVerifier.verifyProjectView(context, workspaceRoot, projectViewSet, workspaceLanguageSettings)) {
      return false;
    }

    final BlazeProjectData newBlazeProjectData;

    WorkingSet workingSet = FutureUtil.waitForFuture(context, workingSetFuture)
      .timed("WorkingSet")
      .withProgressMessage("Computing VCS working set...")
      .onError("Could not compute working set")
      .run()
      .result();
    if (!context.shouldContinue()) {
      return false;
    }

    if (workingSet != null) {
      printWorkingSet(context, workingSet);
    }

    boolean ideResolveErrors = false;
    if (syncParams.syncMode != SyncMode.RESTORE_EPHEMERAL_STATE || oldBlazeProjectData == null) {
      SyncState.Builder syncStateBuilder = new SyncState.Builder();
      SyncState previousSyncState = oldBlazeProjectData != null ? oldBlazeProjectData.syncState : null;
      List<TargetExpression> allTargets = projectViewSet.listItems(TargetSection.KEY);
      if (expandSyncToWorkingSet && workingSet != null) {
        allTargets.addAll(getWorkingSetTargets(workingSet));
      }

      boolean syncPluginRequiresBuild = false;
      boolean requiresAndroidSdk = false;
      for (BlazeSyncPlugin syncPlugin : BlazeSyncPlugin.EP_NAME.getExtensions()) {
        syncPluginRequiresBuild |= syncPlugin.requiresResolveIdeArtifacts();
        requiresAndroidSdk |= syncPlugin.requiresAndroidSdk(workspaceLanguageSettings);
      }

      final BlazeIdeInterface.IdeResult ideQueryResult = getIdeQueryResult(
        project,
        context,
        projectViewSet,
        allTargets,
        workspaceLanguageSettings,
        new ArtifactLocationDecoder(blazeRoots, workspacePathResolver),
        syncStateBuilder,
        previousSyncState,
        requiresAndroidSdk
      );
      if (ideQueryResult == null) {
        if (workingSet != null && !workingSet.isEmpty() && expandSyncToWorkingSet && !context.isCancelled()) {
          String msg = String.format("If you have broken targets in your VCS working set, uncheck '%s > Expand Sync to Working Set'" +
                                     " and try again.", Blaze.buildSystemName(project));
          context.output(new PrintOutput(msg, PrintOutput.OutputType.ERROR));
        }
        return false;
      }
      ImmutableMap<Label, RuleIdeInfo> ruleMap = ideQueryResult.ruleMap;

      ListenableFuture<ImmutableMultimap<Label, Label>> reverseDependenciesFuture =
        BlazeExecutor.getInstance().submit(() -> ReverseDependencyMap.createRdepsMap(ruleMap));

      boolean doBuild = syncPluginRequiresBuild || (syncParams.doBuild || oldBlazeProjectData == null);
      if (doBuild) {
        List<TargetExpression> targetExpressions = Lists.newArrayList(syncParams.targetExpressions);
        if (targetExpressions.isEmpty()) {
          targetExpressions.addAll(allTargets);
        }
        ideResolveErrors = !resolveIdeArtifacts(project, context, workspaceRoot, projectViewSet, targetExpressions);
        LocalFileSystem.getInstance().refresh(true);
        if (context.isCancelled()) {
          return false;
        }
      }

      Scope.push(context, (childContext) -> {
        childContext.push(new TimingScope("UpdateSyncState"));
        for (BlazeSyncPlugin syncPlugin : BlazeSyncPlugin.EP_NAME.getExtensions()) {
          syncPlugin.updateSyncState(
            project,
            childContext,
            workspaceRoot,
            projectViewSet,
            workspaceLanguageSettings,
            blazeRoots,
            workingSet,
            workspacePathResolver,
            ruleMap,
            ideQueryResult.androidPlatformDirectory,
            syncStateBuilder,
            previousSyncState);
        }
      });

      ImmutableMultimap<Label, Label> reverseDependencies = FutureUtil.waitForFuture(context, reverseDependenciesFuture)
        .timed("ReverseDependencies")
        .onError("Failed to compute reverse dependency map")
        .run()
        .result();
      if (reverseDependencies == null) {
        return false;
      }

      newBlazeProjectData = new BlazeProjectData(
        syncStartTime,
        ruleMap,
        blazeRoots,
        workingSet,
        workspacePathResolver,
        workspaceLanguageSettings,
        syncStateBuilder.build(),
        reverseDependencies
      );
    } else {
      // Restore project based on old blaze project data
      newBlazeProjectData = oldBlazeProjectData;
    }

    boolean success = updateProject(project, context, projectViewSet, oldBlazeProjectData, newBlazeProjectData);
    if (!success) {
      return false;
    }

    if (ideResolveErrors) {
      context.output(new PrintOutput(
        "Sync was successful, but there were compilation errors. The project may not fully resolve until fixed.",
        PrintOutput.OutputType.ERROR
      ));
    }

    onSyncComplete(project, context, projectViewSet, newBlazeProjectData);
    return true;
  }

  static class WorkspacePathResolverAndProjectView {
    final WorkspacePathResolver workspacePathResolver;
    final ProjectViewSet projectViewSet;
    public WorkspacePathResolverAndProjectView(WorkspacePathResolver workspacePathResolver,
                                               ProjectViewSet projectViewSet) {
      this.workspacePathResolver = workspacePathResolver;
      this.projectViewSet = projectViewSet;
    }
  }
  private WorkspacePathResolverAndProjectView computeWorkspacePathResolverAndProjectView(BlazeContext context,
                                                                                         BlazeRoots blazeRoots,
                                                                                         BlazeVcsHandler vcsHandler,
                                                                                         ListeningExecutorService executor) {
    for (int i = 0; i < 3; ++i) {
      WorkspacePathResolver vcsWorkspacePathResolver = null;
      BlazeVcsHandler.BlazeVcsSyncHandler vcsSyncHandler = vcsHandler.createSyncHandler(project, workspaceRoot);
      if (vcsSyncHandler != null) {
        boolean ok = Scope.push(context, (childContext) -> {
          childContext.push(new TimingScope("UpdateVcs"));
          return vcsSyncHandler.update(context, blazeRoots, executor);
        });
        if (!ok) {
          return null;
        }
        vcsWorkspacePathResolver = vcsSyncHandler.getWorkspacePathResolver();
      }

      WorkspacePathResolver workspacePathResolver = vcsWorkspacePathResolver != null
                                                    ? vcsWorkspacePathResolver
                                                    : new WorkspacePathResolverImpl(workspaceRoot, blazeRoots);

      ProjectViewSet projectViewSet = ProjectViewManager.getInstance(project).reloadProjectView(context, workspacePathResolver);
      if (projectViewSet == null) {
        return null;
      }

      if (vcsSyncHandler != null) {
        BlazeVcsHandler.BlazeVcsSyncHandler.ValidationResult validationResult =
          vcsSyncHandler.validateProjectView(context, projectViewSet);
        switch (validationResult) {
          case OK:
            // Fall-through and return
            break;
          case Error:
            return null;
          case RestartSync:
            continue;
          default:
            // Cannot happen
            return null;
        }
      }

      return new WorkspacePathResolverAndProjectView(workspacePathResolver, projectViewSet);
    }
    return null;
  }

  private void printWorkingSet(BlazeContext context, WorkingSet workingSet) {
    List<String> messages = Lists.newArrayList();
    messages.addAll(workingSet.addedFiles.stream().map(file -> file.relativePath() + " (added)").collect(Collectors.toList()));
    messages.addAll(workingSet.modifiedFiles.stream().map(file -> file.relativePath() + " (modified)").collect(Collectors.toList()));
    Collections.sort(messages);

    if (messages.isEmpty()) {
      context.output(new PrintOutput("Your working set is empty"));
      return;
    }
    int maxFiles = 20;
    for (String message : Iterables.limit(messages, maxFiles)) {
      context.output(new PrintOutput("  " + message));
    }
    if (messages.size() > maxFiles) {
      context.output(new PrintOutput(String.format("  (and %d more)", messages.size() - maxFiles)));
    }
  }

  private Collection<? extends TargetExpression> getWorkingSetTargets(WorkingSet workingSet) {
    List<TargetExpression> result = Lists.newArrayList();
    for (WorkspacePath workspacePath : Iterables.concat(workingSet.addedFiles, workingSet.modifiedFiles)) {
      File buildFile = workspaceRoot.fileForPath(workspacePath);
      if (buildFile.getName().equals("BUILD")) {
        result.add(TargetExpression.allFromPackageNonRecursive(workspaceRoot.workspacePathFor(buildFile.getParentFile())));
      }
    }
    return result;
  }


  private boolean updateProject(Project project,
                                BlazeContext parentContext,
                                ProjectViewSet projectViewSet,
                                @Nullable BlazeProjectData oldBlazeProjectData,
                                BlazeProjectData newBlazeProjectData) {
    return Scope.push(parentContext, context -> {
      context
        .push(new LoggedTimingScope(project, Action.SYNC_IMPORT_DATA_TIME))
        .push(new TimingScope("UpdateProjectStructure"));
      context.output(new StatusOutput("Committing project structure..."));

      return updateProject(
        context,
        importSettings,
        projectViewSet,
        oldBlazeProjectData,
        newBlazeProjectData
      );
    });
  }

  @Nullable
  private BlazeIdeInterface.IdeResult getIdeQueryResult(
    Project project,
    BlazeContext parentContext,
    ProjectViewSet projectViewSet,
    List<TargetExpression> targets,
    WorkspaceLanguageSettings workspaceLanguageSettings,
    ArtifactLocationDecoder artifactLocationDecoder,
    SyncState.Builder syncStateBuilder,
    @Nullable SyncState previousSyncState,
    boolean requiresAndroidSdk) {

    return Scope.push(parentContext, context -> {
      context.push(new TimingScope("IdeQuery"));

      BlazeIdeInterface blazeIdeInterface = BlazeIdeInterface.getInstance();
      return blazeIdeInterface.updateBlazeIdeState(
        project,
        context,
        workspaceRoot,
        projectViewSet,
        targets,
        workspaceLanguageSettings,
        artifactLocationDecoder,
        syncStateBuilder,
        previousSyncState,
        requiresAndroidSdk
      );
    });
  }

  private static boolean resolveIdeArtifacts(
    Project project,
    BlazeContext parentContext,
    WorkspaceRoot workspaceRoot,
    ProjectViewSet projectViewSet,
    List<TargetExpression> targetExpressions) {
    return Scope.push(parentContext, context -> {
      context
        .push(new LoggedTimingScope(project, Action.BLAZE_BUILD_DURING_SYNC))
        .push(new TimingScope("BlazeBuild"))
      ;
      context.output(new StatusOutput("Building project dependencies..."));

      // We don't want errors propagated for the build step - compilation errors shouldn't be interpreted
      // as "Sync failed"
      context.setPropagatesErrors(false);

      if (!targetExpressions.isEmpty()) {
        BlazeIdeInterface blazeIdeInterface = BlazeIdeInterface.getInstance();
        blazeIdeInterface.resolveIdeArtifacts(project, context, workspaceRoot, projectViewSet, targetExpressions);
      }

      return !context.hasErrors();
    });
  }

  private boolean updateProject(
    BlazeContext context,
    BlazeImportSettings importSettings,
    ProjectViewSet projectViewSet,
    @Nullable BlazeProjectData oldBlazeProjectData,
    BlazeProjectData newBlazeProjectData) {

    try {
      AsyncUtil.executeProjectChangeAction(() -> ProjectRootManagerEx.getInstanceEx(project).mergeRootsChangesDuring(() -> {
        updateSdk(
          context,
          projectViewSet,
          newBlazeProjectData
        );
        updateProjectStructure(
          context,
          importSettings,
          projectViewSet,
          oldBlazeProjectData,
          newBlazeProjectData
        );
      }));
    } catch (Throwable t) {
      IssueOutput.error("Internal error. Please issue a bug at go/aswbbug. Error: " + t)
        .submit(context);
      LOG.error(t);
      return false;
    }

    BlazeProjectDataManagerImpl.getImpl(project).saveProject(importSettings, newBlazeProjectData);
    return true;
  }

  private void updateSdk(BlazeContext context,
                         ProjectViewSet projectViewSet,
                         BlazeProjectData newBlazeProjectData) {
    for (BlazeSyncPlugin syncPlugin : BlazeSyncPlugin.EP_NAME.getExtensions()) {
      syncPlugin.updateSdk(project, context, projectViewSet, newBlazeProjectData);
    }
  }

  private void updateProjectStructure(
    BlazeContext context,
    BlazeImportSettings importSettings,
    ProjectViewSet projectViewSet,
    @Nullable BlazeProjectData oldBlazeProjectData,
    BlazeProjectData newBlazeProjectData) {

    ModuleEditorImpl moduleEditor = ModuleEditorProvider.getInstance().getModuleEditor(project, importSettings);

    ModuleType workspaceModuleType = null;
    for (BlazeSyncPlugin syncPlugin : BlazeSyncPlugin.EP_NAME.getExtensions()) {
      workspaceModuleType = syncPlugin.getWorkspaceModuleType(newBlazeProjectData.workspaceLanguageSettings.getWorkspaceType());
      if (workspaceModuleType != null) {
        break;
      }
    }
    if (workspaceModuleType == null) {
      workspaceModuleType = ModuleType.EMPTY;
      IssueOutput.warn("Could not set module type for workspace module.").submit(context);
    }

    Module workspaceModule = moduleEditor.createModule(ModuleDataStorage.WORKSPACE_MODULE_NAME, workspaceModuleType);
    ModifiableRootModel workspaceModifiableModel = moduleEditor.editModule(workspaceModule);

    ContentEntryEditor.createContentEntries(
      project,
      context,
      workspaceRoot,
      projectViewSet,
      newBlazeProjectData,
      workspaceModifiableModel
    );

    for (BlazeSyncPlugin blazeSyncPlugin : BlazeSyncPlugin.EP_NAME.getExtensions()) {
      blazeSyncPlugin.updateProjectStructure(
        project,
        context,
        workspaceRoot,
        projectViewSet,
        newBlazeProjectData,
        oldBlazeProjectData,
        moduleEditor,
        workspaceModule,
        workspaceModifiableModel);
    }

    createProjectDataDirectoryModule(moduleEditor, new File(importSettings.getProjectDataDirectory()), workspaceModuleType);

    moduleEditor.commitWithGc(context);
  }

  /**
   * Creates a module that includes the user's data directory.
   *
   * This is useful to be able to edit the project view without IntelliJ complaining it's outside the project.
   */
  private void createProjectDataDirectoryModule(ModuleEditor moduleEditor,
                                                File projectDataDirectory,
                                                ModuleType moduleType) {
    Module module = moduleEditor.createModule(ModuleDataStorage.PROJECT_DATA_DIR_MODULE_NAME, moduleType);
    ModifiableRootModel modifiableModel = moduleEditor.editModule(module);
    ContentEntry rootContentEntry = modifiableModel.addContentEntry(pathToUrl(projectDataDirectory));
    rootContentEntry.addExcludeFolder(pathToUrl(new File(projectDataDirectory, ".idea")));
    rootContentEntry.addExcludeFolder(pathToUrl(new File(projectDataDirectory, ModuleDataStorage.DATA_SUBDIRECTORY)));
  }

  private static String pathToUrl(File path) {
    String filePath = FileUtil.toSystemIndependentName(path.getPath());
    return VirtualFileManager.constructUrl(StandardFileSystems.FILE_PROTOCOL, filePath);
  }

  private static void onSyncStart(Project project) {
    final SyncListener[] syncListeners = SyncListener.EP_NAME.getExtensions();
    for (SyncListener syncListener : syncListeners) {
      syncListener.onSyncStart(project);
    }
  }

  private static void afterSync(Project project,
                                boolean successful) {
    final SyncListener[] syncListeners = SyncListener.EP_NAME.getExtensions();
    for (SyncListener syncListener : syncListeners) {
      syncListener.afterSync(project, successful);
    }
  }

  private void onSyncComplete(Project project,
                              BlazeContext context,
                              ProjectViewSet projectViewSet,
                              BlazeProjectData blazeProjectData) {
    validate(project, context, blazeProjectData);

    final SyncListener[] syncListeners = SyncListener.EP_NAME.getExtensions();
    for (SyncListener syncListener : syncListeners) {
      syncListener.onSyncComplete(
        project,
        importSettings,
        projectViewSet,
        blazeProjectData
      );
    }
  }

  private static void validate(
    Project project,
    BlazeContext context,
    BlazeProjectData blazeProjectData) {
    for (BlazeSyncPlugin syncPlugin : BlazeSyncPlugin.EP_NAME.getExtensions()) {
      syncPlugin.validate(project, context, blazeProjectData);
    }
  }
}
