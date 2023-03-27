package jacsal.vertx;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import jacsal.Compiler;
import jacsal.JacsalContext;
import jacsal.Utils;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public class BaseTest {
  protected int                debugLevel  = 0;
  protected String             packageName = Utils.DEFAULT_JACSAL_PKG;
  protected Map<String,Object> globals     = new HashMap<>();
  protected boolean            replMode    =  false;

  protected Vertx vertx;

  protected int testCount;
  protected int counter = 0;

  static class TestInstance { List<String> classes; String code; Object expected; }
  class TestRunner {
    List<TestInstance> tests = new ArrayList<>();
    VertxTestContext   testContext;

    public TestRunner(VertxTestContext testContext)  { this.testContext = testContext; }
    public TestRunner test(String code, Object expected)                               { return addTest(List.of(), code, expected); }
    public TestRunner test(List<String> classCode, String scriptCode, Object expected) { return addTest(classCode, scriptCode, expected); }
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
      TestInstance test = new TestInstance();
      test.classes  = classes;
      test.code     = scriptCode;
      test.expected = expected;
      tests.add(test);
      return this;
    }
  }

  protected TestRunner testRunner(VertxTestContext testContext) {
    return new TestRunner(testContext);
  }

  protected void doTest(TestInstance test, VertxTestContext testContext, Runnable continuing) {
    doTest(test.classes, test.code, test.expected, testContext, continuing);
  }

  protected void doTest(List<String> classCode, String scriptCode, Object expected, VertxTestContext testContext, Runnable continuing) {
    Object finalExpected = expected instanceof String &&
                             ((String) expected).startsWith("#") ? new BigDecimal(((String) expected).substring(1))
                                                                 : expected;
      try {
        JacsalContext jacsalContext = JacsalContext.create()
                                                   .environment(new JacsalVertxEnv(vertx))
                                                   .evaluateConstExprs(true)
                                                   .replMode(replMode)
                                                   .debug(debugLevel)
                                                   .build();

        var bindings = createGlobals();

        classCode.forEach(code -> compileClass(code, jacsalContext, packageName));

        var compiled = compileScript(scriptCode, jacsalContext, packageName, bindings);
        Thread thread = Thread.currentThread();
        compiled.accept(bindings, result -> {
          testContext.verify(() -> {
            assertEquals(thread, Thread.currentThread());        // Make sure we finished on same event-loop thread
            if (finalExpected instanceof Object[]) {
              assertTrue(result instanceof Object[]);
              assertTrue(Arrays.equals((Object[]) finalExpected, (Object[]) result));
            }
            else {
              if (result instanceof Throwable) {
                ((Throwable) result).printStackTrace();
              }
              assertEquals(finalExpected, result);
            }
          });
          continuing.run();
        });
      }
      catch (Exception e) {
        testContext.failNow(e);
        e.printStackTrace();
      }
  }

  public void compileClass(String source, JacsalContext jacsalContext, String packageName) {
    Compiler.compileClass(source, jacsalContext, packageName);
  }

  public BiConsumer<Map<String, Object>,Consumer<Object>> compileScript(String source, JacsalContext jacsalContext, String packageName, Map<String, Object> bindings) {
    return Compiler.compileScript(source, jacsalContext, packageName, bindings);
  }

  protected Map<String, Object> createGlobals() {
    var map = new HashMap<String, Object>();
    map.putAll(globals);
    return map;
  }
}
