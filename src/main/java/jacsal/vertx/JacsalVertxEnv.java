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

package jacsal.vertx;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jacsal.JacsalEnv;

/**
 * Jacsal execution environment for Vert.x based applications.
 * This delegates scheduling of events (blocking and non-blocking) to Vertx.
 */
public class JacsalVertxEnv implements JacsalEnv {
  Vertx vertx;
  static Vertx singletonVertx;

  public JacsalVertxEnv() {
    if (singletonVertx == null) {
      singletonVertx = Vertx.vertx();
    }
    vertx = singletonVertx;
  }

  public JacsalVertxEnv(Vertx vertx) {
    this.vertx = vertx;
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
}
