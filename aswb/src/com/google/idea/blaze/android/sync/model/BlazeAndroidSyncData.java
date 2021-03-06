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
package com.google.idea.blaze.android.sync.model;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import java.io.Serializable;

/**
 * Sync data for the Android plugin.
 */
@Immutable
public class BlazeAndroidSyncData implements Serializable {
  private static final long serialVersionUID = 1L;

  public final BlazeAndroidImportResult importResult;
  @Nullable public final AndroidSdkPlatform androidSdkPlatform;

  public BlazeAndroidSyncData(BlazeAndroidImportResult importResult,
                              @Nullable AndroidSdkPlatform androidSdkPlatform) {
    this.importResult = importResult;
    this.androidSdkPlatform = androidSdkPlatform;
  }
}
