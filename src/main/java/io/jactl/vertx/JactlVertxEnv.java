/*
 * Copyright © 2022,2023 James Crawford
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.jactl.vertx;

import io.jactl.JactlContext;
import io.jactl.Utils;
import io.jactl.runtime.RuntimeError;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.jactl.JactlEnv;
import io.vertx.core.shareddata.AsyncMap;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Jactl execution environment for Vert.x based applications.
 * This delegates scheduling of events (blocking and non-blocking) to Vertx.
 */
public class JactlVertxEnv implements JactlEnv {
  private static final String CHECKPOINT_MAP = Utils.JACTL_PREFIX + "checkpointMap";
  private static Vertx singletonVertx;
  protected Vertx    vertx;
  protected String   podId;
  private AsyncMap<String,byte[]> checkpoints;

  // TESTING
  public static AtomicBoolean checkpointEnabled = new AtomicBoolean(true);

  public JactlVertxEnv() {
    if (singletonVertx == null) {
      singletonVertx = Vertx.vertx();
    }
    vertx = singletonVertx;
    init();
  }

  public JactlVertxEnv(Vertx vertx) {
    this(vertx, null);
  }

  public JactlVertxEnv(Vertx vertx, String podId) {
    this(vertx, podId, true);
  }

  public JactlVertxEnv(Vertx vertx, String podId, boolean clustered) {
    this.vertx = vertx;
    this.podId = podId;
    if (clustered) {
      init();
    }
  }

  protected void init() {
    vertx.sharedData()
         .getAsyncMap(CHECKPOINT_MAP + (podId == null ? "" : ":" + podId))
         .onSuccess(aMap -> checkpoints = (AsyncMap<String,byte[]>)(Object)aMap)
         .onFailure(Throwable::printStackTrace);
  }

  public Vertx vertx() {
    return vertx;
  }

  @Override
  public void scheduleEvent(Object threadContext, Runnable runnable) {
    Context context = threadContext == null ? vertx.getOrCreateContext() : (Context)threadContext;
    context.runOnContext(event -> runnable.run());
  }

  @Override
  public void scheduleEvent(Object threadContext, Runnable runnable, long timeMs) {
    if (timeMs <= 0) {
      scheduleEvent(threadContext, runnable);
      return;
    }
    Context context = threadContext == null ? null : (Context)threadContext;
    vertx.setTimer(timeMs, event -> {
      if (context == null) {
        runnable.run();
      }
      else {
        scheduleEvent(context, runnable);
      }
    });
  }

  @Override
  public void scheduleEvent(Runnable runnable, long timeMs) {
    if (timeMs <= 0) {
      scheduleEvent(null, runnable);
      return;
    }
    vertx.setTimer(timeMs, event -> runnable.run());
  }

  @Override
  public void scheduleBlocking(Runnable runnable) {
    vertx.executeBlocking(event -> runnable.run());
  }

  @Override
  public Object getThreadContext() {
    return vertx.getOrCreateContext();
  }

  @Override
  public void saveCheckpoint(UUID id, int checkpointId, byte[] checkpoint, String source, int offset, Object result, Consumer<Object> resumer) {
    if (!checkpointEnabled.get()) {
      resumer.accept(result);
      return;
    }
    String idAsString  = id.toString();
    String key         = idAsString + ':' + checkpointId;
    String previousKey = checkpointId > 1 ? idAsString + ':' + (checkpointId - 1) : null;
    checkpoints.putIfAbsent(key, checkpoint)
               .onSuccess(res -> {
                 if (res != null) {
                   // There was already an entry for that instanceId and checkpointId. This means that we have the
                   // same instance running in two different places at the same time.
                   resumer.accept(new RuntimeError("Duplicate checkpoint detected", source, offset));
                 }
                 else {
                   if (previousKey != null) {
                     // Delete the last one but don't bother waiting for it to complete
                     checkpoints.remove(previousKey).onFailure(Throwable::printStackTrace);
                   }
                   resumer.accept(result);
                 }
               })
               .onFailure(err -> resumer.accept(new RuntimeError("Error during saveCheckpoint: " + err.getMessage(), source, offset, err)));
  }

  @Override
  public void deleteCheckpoint(UUID id, int checkpointId) {
    if (!checkpointEnabled.get()) {
      return;
    }
    String key = id.toString() + ':' + checkpointId;
    checkpoints.remove(key).onFailure(Throwable::printStackTrace);
  }

  public int recoverCheckpoints(JactlContext context) throws ExecutionException, InterruptedException {
    CompletableFuture<Object> f = new CompletableFuture<>();
    checkpoints.values()
               .onSuccess(f::complete)
               .onFailure(f::complete);
    Object result = f.get();
    if (result instanceof List) {
      List<byte[]> list = (List<byte[]>) result;
      list.forEach(chkPt -> context.recoverCheckpoint(chkPt, res -> {}));
      return list.size();
    }
    throw new RuntimeException("Error getting values of async map", (Throwable)result);
  }
}
