/*
 * Copyright © 2022-2026 James Crawford
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

package io.jactl.vertx.benchmark;

import io.jactl.*;
import io.jactl.runtime.BuiltinFunctions;
import io.jactl.vertx.JactlVertxEnv;
import io.vertx.core.Context;
import io.vertx.core.VertxOptions;
import io.vertx.core.impl.VertxInternal;
import org.openjdk.jmh.annotations.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 3, timeUnit = TimeUnit.SECONDS)
@OperationsPerInvocation(100_000)
@Fork(value = 1, jvmArgs={"-Xms1g", "-ea"})
public class VirtualThreadBenchmark {

  private static String code =
    "var totals    = [:]\n" +
    "var itemCount = 0\n" +
    "var grandTotal = 0.0\n" +
    "var topCategory = ''\n" +
    "var topAmount = -1.0\n" +
    "var slept = 0\n" +
    "\n" +
    "def checkInventory(widget, count) {\n" +
    "  sleep(10) if slept++ < sleepCount\n" +   // <-- suspend for 10ms
    "  return true\n" +
    "}\n" +
    "\n" +
    "def processOrder(order) {\n" +
    "    var price    = order.price\n" +
    "    var qty      = order.quantity\n" +
    "    var category = order.category\n" +
    "\n" +
    "    return unless checkInventory(order.category, order.quantity)\n" +
    "\n" +
    "    var discount = 0.0\n" +
    "    if      (qty >= 100) { discount = 0.20 }\n" +
    "    else if (qty >=  50) { discount = 0.10 }\n" +
    "    else if (qty >=  20) { discount = 0.05 }\n" +
    "\n" +
    "    var lineTotal = price * qty * (1.0 - discount)\n" +
    "\n" +
    "    if (totals[category] == null) {\n" +
    "      totals[category] = 0.0\n" +
    "    }\n" +
    "    totals[category] = totals[category] + lineTotal\n" +
    "    grandTotal       = grandTotal + lineTotal\n" +
    "    itemCount        = itemCount + 1\n" +
    "\n" +
    "    if (totals[category] > topAmount) {\n" +
    "        topAmount   = totals[category]\n" +
    "        topCategory = category\n" +
    "    }\n" +
    "}\n" +
    "\n" +
    "for (order in orders) {\n" +
    "  processOrder(order)\n" +
    "}\n" +
    "\n" +
    "'Processed ' + itemCount + ' orders. Grand total: ' + grandTotal + '. Top category: ' + topCategory";

  private List<Map<String, Object>> ordersList;
  private Map<String, Object>       globals           = new HashMap<>();
  private String expected = "Processed 200 orders. Grand total: 927137.20. Top category: Books";
  //private String expected = "Processed 20 orders. Grand total: 93725.40. Top category: Electronics";

  private JactlVertxEnv   jactlEnv;
  private JactlContext    jactlContext;
  private JactlContext    jactlSyncContext;
  private JactlScript     script;
  private JactlScript     syncScript;
  private ExecutorService executor;
  private List<Context>   eventLoopContexts;

  @Setup(Level.Trial)
  public void setup() {
    String[] categories = {"Electronics", "Clothing", "Food", "Books", "Sports"};
    final int ORDER_COUNT = 200;
    ordersList = new ArrayList<>(ORDER_COUNT);
    Random rnd = new Random(0);
    for (int i = 0; i < ORDER_COUNT; i++) {
      Map<String, Object> order = new HashMap<>();
      order.put("category", categories[i % categories.length]);
      order.put("price",    rnd.nextInt(200));
      order.put("quantity", rnd.nextInt(100));
      ordersList.add(order);
    }

    globals.put("orders", ordersList);
    globals.put("sleepCount", 0);
    globals.put("sleepMs", 0);

    jactlEnv = new JactlVertxEnv();
    jactlContext = JactlContext.create().environment(jactlEnv).build();
    script = Jactl.compileScript(code, globals, jactlContext);

    int poolSize = VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE;
    VertxInternal vertxInternal = (VertxInternal) jactlEnv.vertx();
    eventLoopContexts = new ArrayList<>(poolSize);
    for (int i = 0; i < poolSize; i++) {
      eventLoopContexts.add(vertxInternal.createEventLoopContext());
    }

    jactlSyncContext = JactlContext.create().async(false).build();
    syncScript = Jactl.compileScript(code, globals, jactlSyncContext);
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    jactlEnv.vertx().close();
    executor.shutdown();
  }

  final public static int COUNT = 100_000;

  private String _runTest(int sleepCount) {
    globals.put("sleepCount", sleepCount);
    Semaphore               semaphore   = new Semaphore(0);
    AtomicReference<String> finalResult = new AtomicReference<>();
    for (int i = 0; i < COUNT; i++) {
      Context context = eventLoopContexts.get(i % eventLoopContexts.size());
      jactlEnv.scheduleEvent(context, () -> script.run(globals, result -> {
        finalResult.set(result.toString());
        semaphore.release();
      }));
    }
    semaphore.acquireUninterruptibly(COUNT);
    assert finalResult.get().equals(expected) : "Unexpected result: " + finalResult.get();
    return null;
  }

  private String _runTestVT(int sleepCount) throws ExecutionException, InterruptedException {
    globals.put("sleepCount", sleepCount);
    Semaphore semaphore = new Semaphore(0);
    AtomicReference<String> finalResult = new AtomicReference<>();
    for (int i = 0; i < COUNT; i++) {
      executor.submit(() -> syncScript.run(globals, result -> {
        finalResult.set(result.toString());
        semaphore.release();
      }));
    }
    semaphore.acquireUninterruptibly(COUNT);
    assert finalResult.get().equals(expected) : "Unexpected result: " + finalResult.get();
    return null;
  }

  @Benchmark
  public Object sleep0() {
    return _runTest(0);
  }

  @Benchmark
  public Object sleepVT0() throws ExecutionException, InterruptedException {
    return _runTestVT(0);
  }

  @Benchmark
  public Object sleep1() {
    return _runTest(1);
  }

  @Benchmark
  public Object sleepVT1() throws ExecutionException, InterruptedException {
    return _runTestVT(1);
  }

  @Benchmark
  public Object sleep2() {
    return _runTest(2);
  }

  @Benchmark
  public Object sleepVT2() throws ExecutionException, InterruptedException {
    return _runTestVT(2);
  }

  @Benchmark
  public Object sleep5() {
    return _runTest(5);
  }

  @Benchmark
  public Object sleepVT5() throws ExecutionException, InterruptedException {
    return _runTestVT(5);
  }

  @Benchmark
  public Object sleep10() {
    return _runTest(10);
  }

  @Benchmark
  public Object sleepVT10() throws ExecutionException, InterruptedException {
    return _runTestVT(10);
  }
}