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

import io.jactl.VertxBaseTest;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.junit5.RunTestOnContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
public class FunctionTests extends VertxBaseTest {
  HttpServer server;
  int serverPort;

  @RegisterExtension
  RunTestOnContext rtoc = new RunTestOnContext();

  @BeforeEach
  void prepare(VertxTestContext testContext) {
    init(rtoc.vertx());
    server = vertx.createHttpServer();

    final var currencies = Map.of("AUD", 0.6675, "USD", 1.0, "EUR", 1.0843);
    Router router = Router.router(vertx);
    router.route("/currencyConversion").handler(ctx -> {
      ctx.request().bodyHandler(buf -> {
        JsonObject req          = buf.toJsonObject();
        String     currencyCode = req.getString("currencyCode");
        Double     rate         = currencyCode == null ? null : currencies.get(currencyCode);
        var result = Map.of("status", rate == null ? "fail" : "ok",
                            "amount", rate == null ? 0 : rate * req.getDouble("amount"));
        ctx.response().end(Json.encode(result));
      });
    });
    server.requestHandler(router).listen(0).onSuccess(svr -> {
      serverPort = server.actualPort();
      testContext.completeNow();
    });
  }

  @Test
  void jsonFunctions(VertxTestContext testContext) {
    testRunner(testContext)
      .test("[:].toJsonVertx()", "{}")
      .test("[].toJsonVertx()", "[]")
      .test("[a:1].toJsonVertx()", "{\"a\":1}")
      .test("1.toJsonVertx()", "1")
      .test("1.toJsonVertx().fromJsonVertx()", 1)
      .test("1L.toJsonVertx()", "1")
      .test("1L.toJsonVertx().fromJsonVertx()", 1)
      .test("1234567890123456L.toJsonVertx().fromJsonVertx()", 1234567890123456L)
      .test("'abc'.toJsonVertx()", "\"abc\"")
      .test("'abc'.toJsonVertx().fromJsonVertx()", "abc")
      .test("''.toJsonVertx()", "\"\"")
      .test("''.toJsonVertx().fromJsonVertx()", "")
      .test("1.234.toJsonVertx()", "1.234")
      .testError("'.234'.fromJsonVertx()", "unexpected character")
      .test("1.234.toJsonVertx().fromJsonVertx()", "#1.234")
      .test("0.0000000000000000001234.toJsonVertx().fromJsonVertx()", "#0.0000000000000000001234")
      .testError("'1.2.3'.fromJsonVertx()", "error decoding json")
      .test("'\"\"'.toJsonVertx()", "\"\\\"\\\"\"")
      .test("[a:1,b:[1,2,[c:'abc']]].toJsonVertx()", "{\"a\":1,\"b\":[1,2,{\"c\":\"abc\"}]}")
      .test("def f = [a:1,b:[1,2,[c:'abc']]].toJsonVertx; f()", "{\"a\":1,\"b\":[1,2,{\"c\":\"abc\"}]}")
      .test("def x = [a:1,b:[1,2,[c:'abc']]]; x.toJsonVertx()", "{\"a\":1,\"b\":[1,2,{\"c\":\"abc\"}]}")
      .test("def x = [a:1,b:[1,2,[c:'abc']]]; def f = x.toJsonVertx; f()", "{\"a\":1,\"b\":[1,2,{\"c\":\"abc\"}]}")
      .test("[a:1].toJsonVertx().fromJsonVertx()", Map.of("a", 1))
      .testError("''.fromJsonVertx()", "no content")
      .test("'{}'.fromJsonVertx()", Map.of())
      .test("'[]'.fromJsonVertx()", List.of())
      .testError("'{a}'.fromJsonVertx()", "unexpected character")
      .test("'{\"a b c\":123}'.fromJsonVertx()", Map.of("a b c", 123))
      .test("'{\"a\":1,\"b\":[1,2,{\"c\":\"abc\"}]}'.fromJsonVertx().toString()", "[a:1, b:[1, 2, [c:'abc']]]")
      .run();
  }

  @Test
  void sendReceive(VertxTestContext testContext) {
    globals.put("url", "http://localhost:" + serverPort + "/currencyConversion");

    testRunner(testContext)
      .test("def result = sendReceiveJson(url, [currencyCode:'AUD',amount:100]).response; result.status == 'ok' && sprintf('%.2f',result.amount) == '66.75'", true)
      .test("def result = sendReceiveJson(url, [currencyCode:'XXX',amount:100]).response; result.status", "fail")
      .test("def result = sendReceiveJson(url, [currencyCode:'XXX',amount:100]).statusCode", 200)
      .test("def f(x) { sendReceiveJson(url, sleep(0,sleep(0,x))).statusCode + sendReceiveJson(url, sleep(0,sleep(0,x))).statusCode }; f([currencyCode:'EUR',amount:200])", 400)
      .run();
  }

  @Test void distributedMaps(VertxTestContext testContext) {
    testRunner(testContext)
      .test("distributedPut('testMap','testKey','testValue'); distributedGet('testMap','testKey')", "testValue")
      .run();
  }

  @AfterEach
  void cleanUp(VertxTestContext testContext) {
    server.close();
    super.cleanUp();
    testContext.completeNow();
  }

}
