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
package com.google.idea.blaze.base.async;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.Scope;
import com.google.idea.blaze.base.scope.output.IssueOutput;
import com.google.idea.blaze.base.scope.output.PrintOutput;
import com.google.idea.blaze.base.scope.scopes.TimingScope;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.ExecutionException;

/**
 * Utilities operating on futures.
 */
public class FutureUtil {
  public static class FutureResult<T> {
    private final T result;
    private final boolean success;

    FutureResult(T result) {
      this.result = result;
      this.success = true;
    }

    FutureResult() {
      this.result = null;
      this.success = false;
    }

    public T result() {
      return result;
    }

    public boolean success() {
      return success;
    }
  }

  public static class Builder<T> {
    private static final Logger LOG = Logger.getInstance(FutureUtil.class);
    private final BlazeContext context;
    private final ListenableFuture<T> future;
    private String timingCategory;
    private String errorMessage;
    private String progressMessage;

    Builder(BlazeContext context, ListenableFuture<T> future) {
      this.context = context;
      this.future = future;
    }

    public Builder<T> timed(String timingCategory) {
      this.timingCategory = timingCategory;
      return this;
    }
    public Builder<T> withProgressMessage(String message) {
      this.progressMessage = message;
      return this;
    }
    public Builder<T> onError(String errorMessage) {
      this.errorMessage = errorMessage;
      return this;
    }
    public FutureResult<T> run() {
      return Scope.push(context, (childContext) -> {
        if (timingCategory != null) {
          childContext.push(new TimingScope(timingCategory));
        }
        if (progressMessage != null) {
          childContext.output(new PrintOutput(progressMessage));
        }
        try {
          return new FutureResult<>(future.get());
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          context.setCancelled();
        }
        catch (ExecutionException e) {
          LOG.error(e);
          if (errorMessage != null) {
            IssueOutput.error(errorMessage).submit(childContext);
          }
          context.setHasError();
        }
        return new FutureResult<>();
      });
    }
  }

  public static <T> Builder<T> waitForFuture(BlazeContext context, ListenableFuture<T> future) {
    return new Builder<>(context, future);
  }
}
