package jacsal.vertx;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import jacsal.Compiler;
import jacsal.JacsalContext;
import jacsal.JacsalScript;
import jacsal.Utils;
import jacsal.vertx.example.VertxFunctions;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

public class BaseTest {
  protected int                debugLevel  = 0;
  protected String             packageName = Utils.DEFAULT_JACSAL_PKG;
  protected Map<String,Object> globals     = new HashMap<>();
  protected boolean            replMode    =  false;

  protected Vertx vertx;
  protected JacsalVertxEnv jacsalEnv;

  protected int testCount;
  protected int counter = 0;

  protected void init(Vertx vertx) {
    this.vertx = vertx;
    jacsalEnv = new JacsalVertxEnv(vertx);
    JsonFunctions.registerFunctions(jacsalEnv);
    VertxFunctions.registerFunctions(jacsalEnv);
  }

  protected void cleanUp() {
    JsonFunctions.deregisterFunctions();
    VertxFunctions.deregisterFunctions();
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
      BaseTest.this.doTest(classes, code, expected, false, testContext, continuing);
    }
  }
  class TestError extends DoTest {
    public TestError(List<String> classes, String code, String expectedError) { this.classes = classes; this.code = code; this.expected = expectedError; }
    @Override  public void doTest(VertxTestContext testContext, Runnable continuing) {
      BaseTest.this.doTest(classes, code, expected, true, testContext, continuing);
    }
  }

  class TestRunner {
    List<DoTest> tests = new ArrayList<>();
    VertxTestContext   testContext;

    public TestRunner(VertxTestContext testContext)  { this.testContext = testContext; }
    public TestRunner test(String code, Object expected)                               { return addTest(List.of(), code, expected); }
    public TestRunner test(List<String> classCode, String scriptCode, Object expected) { return addTest(classCode, scriptCode, expected); }
    public TestRunner testError(String scriptCode, String error)                       { return addTestError(List.of(), scriptCode, error); }
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
      JacsalContext jacsalContext = JacsalContext.create()
                                                 .environment(jacsalEnv)
                                                 .evaluateConstExprs(true)
                                                 .replMode(replMode)
                                                 .debug(debugLevel)
                                                 .build();

      var bindings = createGlobals();

      classCode.forEach(code -> compileClass(code, jacsalContext, packageName));

      var    compiled = compileScript(scriptCode, jacsalContext, packageName, bindings);
      Thread thread   = Thread.currentThread();
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

  public void compileClass(String source, JacsalContext jacsalContext, String packageName) {
    Compiler.compileClass(source, jacsalContext, packageName);
  }

  public JacsalScript compileScript(String source, JacsalContext jacsalContext, String packageName, Map<String, Object> bindings) {
    return Compiler.compileScript(source, jacsalContext, packageName, bindings);
  }

  protected Map<String, Object> createGlobals() {
    var map = new HashMap<String, Object>();
    map.putAll(globals);
    return map;
  }
}
