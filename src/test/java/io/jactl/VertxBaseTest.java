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

package io.jactl;

import io.jactl.vertx.JactlVertxEnv;
import io.jactl.vertx.VertxFunctions;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import io.jactl.vertx.example.ExampleFunctions;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

public class VertxBaseTest {
  protected int                debugLevel  = 0;
  protected String             packageName = Utils.DEFAULT_JACTL_PKG;
  protected Map<String,Object> globals     = new HashMap<>();
  protected boolean            replMode    =  false;

  protected Vertx         vertx;
  protected JactlVertxEnv jactlEnv;

  protected int testCount;
  protected int counter = 0;

  protected void init(Vertx vertx) {
    this.vertx = vertx;
    jactlEnv = new JactlVertxEnv(vertx);
    VertxFunctions.registerFunctions(jactlEnv);
    ExampleFunctions.registerFunctions(jactlEnv);
  }

  protected void cleanUp() {
    VertxFunctions.deregisterFunctions();
    ExampleFunctions.deregisterFunctions();
    assertTrue(testCount > 0);
    assertEquals(testCount, counter);
  }

  abstract class DoTest {
    List<String> classes; String code; Object expected;
    abstract void doTest(VertxTestContext testContext, Runnable continuing);
  }
  class TestInstance extends DoTest {
    public TestInstance(List<String> classes, String code, Object expected) { this.classes = classes; this.code = code; this.expected = expected; }
    @Override public void doTest(VertxTestContext testContext, Runnable continuing) {
      VertxBaseTest.this.doTest(classes, code, expected, false, testContext, continuing);
    }
  }
  class TestError extends DoTest {
    public TestError(List<String> classes, String code, String expectedError) { this.classes = classes; this.code = code; this.expected = expectedError; }
    @Override  public void doTest(VertxTestContext testContext, Runnable continuing) {
      VertxBaseTest.this.doTest(classes, code, expected, true, testContext, continuing);
    }
  }

  public class TestRunner {
    List<DoTest> tests = new ArrayList<>();
    VertxTestContext   testContext;

    public TestRunner(VertxTestContext testContext)  { this.testContext = testContext; }
    public TestRunner test(String code, Object expected)                               { return addTest(Utils.listOf(), code, expected); }
    public TestRunner test(List<String> classCode, String scriptCode, Object expected) { return addTest(classCode, scriptCode, expected); }
    public TestRunner testError(String scriptCode, String error)                       { return addTestError(Utils.listOf(), scriptCode, error); }
    public void run() {
      testCount = tests.size();
      doRun();
    }

    private void doRun() {
      if (tests.size() == 0) {
        assertEquals(testCount, counter);
        testContext.completeNow();
        return;
      }
      counter++;
      doTest(tests.remove(0), testContext, this::doRun);
    }

    private TestRunner addTest(List<String> classes, String scriptCode, Object expected) {
      TestInstance test = new TestInstance(classes, scriptCode, expected);
      tests.add(test);
      return this;
    }
    private TestRunner addTestError(List<String> classes, String script, String error) {
      TestError test = new TestError(classes, script, error);
      tests.add(test);
      return this;
    }
  }

  protected TestRunner testRunner(VertxTestContext testContext) {
    return new TestRunner(testContext);
  }

  protected void doTest(DoTest test, VertxTestContext testContext, Runnable continuing) {
    test.doTest(testContext, continuing);
  }

  protected void doTest(List<String> classCode, String scriptCode, Object expected, boolean testError, VertxTestContext testContext, Runnable continuing) {
    Object finalExpected = expected instanceof String &&
                             ((String) expected).startsWith("#") ? new BigDecimal(((String) expected).substring(1))
                                                                 : expected;
    Function<String,String> badErrorMsg   = str -> "Test " + counter + ": unexpected error '" + str + "'\nExpected '" + finalExpected + "' for test: " + scriptCode;
    Function<Object,String> unexpectedMsg = obj -> "Test " + counter + ": unexpected value '" + obj + "'\nExpected '" + finalExpected + "' for test: " + scriptCode;
    Function<Object,String> notErrorMsg   = obj -> "Test " + counter + ": unexpected value '" + obj + "'\nExpected error: '" + finalExpected + "' for test: " + scriptCode;

    try {
      JactlContext jactlContext = JactlContext.create()
                                                 .environment(jactlEnv)
                                                 .evaluateConstExprs(true)
                                                 .replMode(replMode)
                                                 .debug(debugLevel)
                                                 .build();

      Map<String, Object> bindings = createGlobals();

      classCode.forEach(code -> compileClass(code, jactlContext, packageName));

      JactlScript compiled = compileScript(scriptCode, jactlContext, packageName, bindings);
      Thread      thread   = Thread.currentThread();
      compiled.run(bindings, result -> {
        testContext.verify(() -> {
          if (testError) {
            assertTrue(result instanceof Exception, notErrorMsg.apply(result));
            String msg = ((Exception) result).getMessage();
            assertTrue(msg.toLowerCase().contains((String) finalExpected), badErrorMsg.apply(msg));
          }
          else {
            assertEquals(thread, Thread.currentThread());        // Make sure we finished on same event-loop thread
            if (finalExpected instanceof Object[]) {
              assertTrue(result instanceof Object[]);
              assertTrue(Arrays.equals((Object[]) finalExpected, (Object[]) result), unexpectedMsg.apply(result));
            }
            else {
              if (result instanceof Throwable) {
                ((Throwable) result).printStackTrace();
              }
              assertEquals(finalExpected, result, unexpectedMsg.apply(result));
            }
          }
        });
        continuing.run();
      });
    }
    catch (Exception e) {
      if (testError) {
        assertTrue(e.getMessage().toLowerCase().contains((String) finalExpected), badErrorMsg.apply(e.getMessage()));
        continuing.run();
      }
      else {
        testContext.failNow(e);
        e.printStackTrace();
      }
    }
  }

  public void compileClass(String source, JactlContext jactlContext, String packageName) {
    Compiler.compileClass(source, jactlContext, packageName);
  }

  public JactlScript compileScript(String source, JactlContext jactlContext, String packageName, Map<String, Object> bindings) {
    return Compiler.compileScript(source, jactlContext, packageName, bindings);
  }

  protected Map<String, Object> createGlobals() {
    HashMap<String, Object> map = new HashMap<String, Object>();
    map.putAll(globals);
    return map;
  }
}
