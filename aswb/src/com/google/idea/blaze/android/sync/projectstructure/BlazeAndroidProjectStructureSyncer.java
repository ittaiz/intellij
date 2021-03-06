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
package com.google.idea.blaze.android.sync.projectstructure;

import com.android.builder.model.SourceProvider;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.idea.blaze.android.resources.LightResourceClassService;
import com.google.idea.blaze.android.run.BlazeAndroidRunConfiguration;
import com.google.idea.blaze.android.sync.AndroidSdkPlatformSyncer;
import com.google.idea.blaze.android.sync.model.AndroidResourceModule;
import com.google.idea.blaze.android.sync.model.AndroidSdkPlatform;
import com.google.idea.blaze.android.sync.model.BlazeAndroidSyncData;
import com.google.idea.blaze.android.sync.model.idea.BlazeAndroidModel;
import com.google.idea.blaze.android.sync.model.idea.SourceProviderImpl;
import com.google.idea.blaze.base.ideinfo.AndroidRuleIdeInfo;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.ideinfo.RuleIdeInfo;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.primitives.Kind;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.TargetExpression;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.projectview.section.sections.TargetSection;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.output.PrintOutput;
import com.google.idea.blaze.base.sync.BlazeSyncPlugin;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.StdModuleTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModifiableRootModel;
import org.jetbrains.android.facet.AndroidFacet;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Updates the IDE's project structure.
 */
public class BlazeAndroidProjectStructureSyncer {

  public static void updateProjectStructure(Project project,
                                            BlazeContext context,
                                            WorkspaceRoot workspaceRoot,
                                            ProjectViewSet projectViewSet,
                                            BlazeProjectData blazeProjectData,
                                            BlazeSyncPlugin.ModuleEditor moduleEditor,
                                            Module workspaceModule,
                                            ModifiableRootModel workspaceModifiableModel,
                                            boolean isAndroidWorkspace) {
    LightResourceClassService.Builder rClassBuilder = new LightResourceClassService.Builder(project);

    if (isAndroidWorkspace) {
      BlazeAndroidSyncData syncData = blazeProjectData.syncState.get(BlazeAndroidSyncData.class);
      if (syncData == null) {
        return;
      }

      AndroidSdkPlatform androidSdkPlatform = syncData.androidSdkPlatform;
      if (androidSdkPlatform != null) {
        int totalOrderEntries = 0;

        // Create the workspace module
        updateWorkspaceModule(
          project,
          workspaceRoot,
          workspaceModule,
          androidSdkPlatform
        );

        // Create android resource modules
        // Because we're setting up dependencies, the modules have to exist before we configure them
        Map<Label, AndroidResourceModule> labelToAndroidResourceModule = Maps.newHashMap();
        for (AndroidResourceModule androidResourceModule : syncData.importResult.androidResourceModules) {
          labelToAndroidResourceModule.put(androidResourceModule.label, androidResourceModule);
          String moduleName = moduleNameForAndroidModule(androidResourceModule.label);
          moduleEditor.createModule(moduleName, StdModuleTypes.JAVA);
        }

        // Configure android resource modules
        for (AndroidResourceModule androidResourceModule : labelToAndroidResourceModule.values()) {
          RuleIdeInfo rule = blazeProjectData.ruleMap.get(androidResourceModule.label);
          AndroidRuleIdeInfo androidRuleIdeInfo = rule.androidRuleIdeInfo;
          assert androidRuleIdeInfo != null;

          String moduleName = moduleNameForAndroidModule(rule.label);
          Module module = moduleEditor.findModule(moduleName);
          assert module != null;
          ModifiableRootModel modifiableRootModel = moduleEditor.editModule(module);

          updateAndroidRuleModule(
            project,
            workspaceRoot,
            androidSdkPlatform,
            rule,
            module,
            modifiableRootModel,
            androidResourceModule
          );

          for (Label resourceDependency : androidResourceModule.transitiveResourceDependencies) {
            if (!labelToAndroidResourceModule.containsKey(resourceDependency)) {
              continue;
            }
            String dependencyModuleName = moduleNameForAndroidModule(resourceDependency);
            Module dependency = moduleEditor.findModule(dependencyModuleName);
            if (dependency == null) {
              continue;
            }
            modifiableRootModel.addModuleOrderEntry(dependency);
            ++totalOrderEntries;
          }
          rClassBuilder.addRClass(androidRuleIdeInfo.resourceJavaPackage, module);
          // Add a dependency from the workspace to the resource module
          workspaceModifiableModel.addModuleOrderEntry(module);
        }

        // Collect potential android run configuration targets
        Set<Label> runConfigurationModuleTargets = Sets.newHashSet();

        // Get all explicitly mentioned targets
        // Doing this now will cut down on root changes later
        for (TargetExpression targetExpression : projectViewSet.listItems(TargetSection.KEY)) {
          if (!(targetExpression instanceof Label)) {
            continue;
          }
          Label label = (Label)targetExpression;
          runConfigurationModuleTargets.add(label);
        }
        // Get any pre-existing targets
        for (RunConfiguration runConfiguration : RunManager.getInstance(project).getAllConfigurationsList()) {
          if (!(runConfiguration instanceof BlazeAndroidRunConfiguration)) {
            continue;
          }
          BlazeAndroidRunConfiguration blazeAndroidRunConfiguration = (BlazeAndroidRunConfiguration)runConfiguration;
          runConfigurationModuleTargets.add(blazeAndroidRunConfiguration.getTarget());
        }

        int totalRunConfigurationModules = 0;
        for (Label label : runConfigurationModuleTargets) {
          // If it's a resource module, it will already have been created
          if (labelToAndroidResourceModule.containsKey(label)) {
            continue;
          }
          // Ensure the label is a supported android rule that exists
          RuleIdeInfo rule = blazeProjectData.ruleMap.get(label);
          if (rule == null) {
            continue;
          }
          if (!rule.kindIsOneOf(Kind.ANDROID_BINARY, Kind.ANDROID_TEST)) {
            continue;
          }

          String moduleName = moduleNameForAndroidModule(rule.label);
          Module module = moduleEditor.createModule(moduleName, StdModuleTypes.JAVA);
          ModifiableRootModel modifiableRootModel = moduleEditor.editModule(module);
          updateAndroidRuleModule(
            project,
            workspaceRoot,
            androidSdkPlatform,
            rule,
            module,
            modifiableRootModel,
            null
          );
          ++totalRunConfigurationModules;
        }

        context.output(new PrintOutput(String.format(
          "Android resource module count: %d, run config modules: %d, order entries: %d",
          syncData.importResult.androidResourceModules.size(),
          totalRunConfigurationModules,
          totalOrderEntries
        )));
      }
    } else {
      AndroidFacetModuleCustomizer.removeAndroidFacet(workspaceModule);
    }

    LightResourceClassService.getInstance(project).installRClasses(rClassBuilder);
  }

  /**
   * Ensures a suitable module exists for the given android target.
   */
  @Nullable
  public static Module ensureRunConfigurationModule(Project project, Label target) {
    String moduleName = moduleNameForAndroidModule(target);
    Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
    if (module != null) {
      return module;
    }

    WorkspaceRoot workspaceRoot = WorkspaceRoot.fromProject(project);
    BlazeProjectData blazeProjectData = BlazeProjectDataManager.getInstance(project).getBlazeProjectData();
    if (blazeProjectData == null) {
      return null;
    }
    AndroidSdkPlatform androidSdkPlatform = AndroidSdkPlatformSyncer.getAndroidSdkPlatform(blazeProjectData);
    if (androidSdkPlatform == null) {
      return null;
    }
    RuleIdeInfo rule = blazeProjectData.ruleMap.get(target);
    if (rule == null) {
      return null;
    }
    if (rule.androidRuleIdeInfo == null) {
      return null;
    }

    BlazeSyncPlugin.ModuleEditor moduleEditor = BlazeProjectDataManager.getInstance(project).editModules();
    Module newModule = moduleEditor.createModule(moduleName, StdModuleTypes.JAVA);
    ModifiableRootModel modifiableRootModel = moduleEditor.editModule(newModule);

    ApplicationManager.getApplication().runWriteAction(() -> {
      updateAndroidRuleModule(
        project,
        workspaceRoot,
        androidSdkPlatform,
        rule,
        newModule,
        modifiableRootModel,
        null
      );
      moduleEditor.commit();
    });
    return newModule;
  }

  public static String moduleNameForAndroidModule(Label label) {
    return label.toString()
      .substring(2) // Skip initial "//"
      .replace('/', '.')
      .replace(':', '.');
  }

  /**
   * Updates the shared workspace module with android info.
   */
  private static void updateWorkspaceModule(Project project,
                                           WorkspaceRoot workspaceRoot,
                                           Module workspaceModule,
                                           AndroidSdkPlatform androidSdkPlatform) {
    File moduleDirectory = workspaceRoot.directory();
    File manifest = new File(workspaceRoot.directory(), "AndroidManifest.xml");
    String resourceJavaPackage = ":workspace";
    ImmutableList<File> transitiveResources = ImmutableList.of();

    createAndroidModel(
      project,
      androidSdkPlatform,
      workspaceModule,
      moduleDirectory,
      manifest,
      resourceJavaPackage,
      transitiveResources
    );
  }

  /**
   * Updates a module from an android rule.
   */
  private static void updateAndroidRuleModule(Project project,
                                              WorkspaceRoot workspaceRoot,
                                              AndroidSdkPlatform androidSdkPlatform,
                                              RuleIdeInfo rule,
                                              Module module,
                                              ModifiableRootModel modifiableRootModel,
                                              @Nullable AndroidResourceModule androidResourceModule) {

    ImmutableCollection<File> resources = androidResourceModule != null
                                          ? androidResourceModule.resources
                                          : ImmutableList.of();
    ImmutableCollection<File> transitiveResources = androidResourceModule != null
                                                    ? androidResourceModule.transitiveResources
                                                    : ImmutableList.of();

    AndroidRuleIdeInfo androidRuleIdeInfo = rule.androidRuleIdeInfo;
    assert androidRuleIdeInfo != null;

    File moduleDirectory = workspaceRoot.fileForPath(rule.label.blazePackage());
    ArtifactLocation manifestArtifactLocation = androidRuleIdeInfo.manifest;
    File manifest = manifestArtifactLocation != null
                    ? manifestArtifactLocation.getFile()
                    : new File(moduleDirectory, "AndroidManifest.xml");
    String resourceJavaPackage = androidRuleIdeInfo.resourceJavaPackage;
    ResourceModuleContentRootCustomizer.setupContentRoots(modifiableRootModel, resources);

    createAndroidModel(
      project,
      androidSdkPlatform,
      module,
      moduleDirectory,
      manifest,
      resourceJavaPackage,
      transitiveResources
    );
  }

  private static void createAndroidModel(Project project,
                                         AndroidSdkPlatform androidSdkPlatform,
                                         Module module,
                                         File moduleDirectory,
                                         File manifest,
                                         String resourceJavaPackage,
                                         ImmutableCollection<File> transitiveResources) {
    AndroidFacetModuleCustomizer.createAndroidFacet(module);
    SourceProvider sourceProvider = new SourceProviderImpl(
      module.getName(),
      manifest,
      transitiveResources
    );
    BlazeAndroidModel androidModel = new BlazeAndroidModel(
      project,
      module,
      moduleDirectory,
      sourceProvider,
      manifest,
      resourceJavaPackage,
      androidSdkPlatform.androidSdkLevel
    );
    AndroidFacet facet = AndroidFacet.getInstance(module);
    if (facet != null) {
      facet.setAndroidModel(androidModel);
    }
  }
}
