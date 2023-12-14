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

package io.jactl.vertx.example;

import io.jactl.*;
import io.jactl.runtime.*;
import io.jactl.vertx.JactlVertxEnv;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * An example Jactl function.
 *
 * It registers one global function:
 * <dl>
 *   <dt>sendReceiveJson</dt><dd>Send a JSON request and wait for a response</dd>
 * </dl>
 */
public class ExampleFunctions {

  private static WebClient webClient;

  /**
   * Initialisation: registers the functions and methods and performs some initialisation.
   * NOTE: existence of a method called registerFunctions(JactlEnv env) allows us to configure
   *       the REPL and the command line Jactl script runner with these functions by adding
   *       this class and library to the .jactlrc file.
   * @param env  the Jactl env (which will be a JactlVertxEnv)
   */
  public static void registerFunctions(JactlEnv env) {
    Vertx            vertx    = ((JactlVertxEnv) env).vertx();
    WebClientOptions options  = new WebClientOptions();
    String           poolSize = System.getProperty("CONNECTION_POOL_SIZE");
    if (poolSize != null) {
      options = options.setMaxPoolSize(Integer.parseInt(poolSize));
      System.out.println("Setting vertx web client pool size to " + poolSize);
    }
    webClient = WebClient.create(vertx, options);

    Jactl.function()
         .name("sendReceiveJson")
         .param("url")
         .param("request")
         .impl(ExampleFunctions.class, "sendReceiveJson")
         .register();

    Jactl.function()
         .name("respond")
         .param("responseId")
         .param("statusCode")
         .param("response")
         .impl(ExampleFunctions.class, "respond")
         .register();

    Jactl.function()
         .name("checkpointsEnabled")
         .param("status")
         .impl(ExampleFunctions.class, "checkpointsEnabled")
         .register();
  }

  /**
   * Deregister the functions/methods. This allows us to run multiple tests and register/deregister each time.
   * Only used by JUnit tests (see {@link VertxBaseTest}).
   */
  public static void deregisterFunctions() {
    Jactl.deregister("sendReceiveJson");
    Jactl.deregister("respond");
    Jactl.deregister("checkpointsEnabled");
  }

  /**
   * Send a JSON message and return the response as a Map.
   * @param c       the Continuation object (will always be null)
   * @param source  the source code (for error reporting)
   * @param offset  the offset into the source (for error reporting)
   * @param url     the full url where the message should be POSTed to
   * @param request the request message as a Map
   * @return the response message as a Map
   */
  public static Map sendReceiveJson(Continuation c, String source, int offset, String url, Object request) {
    Continuation.suspendNonBlocking(source, offset, url, (context, urlValue, resumer) -> {
      try {
        webClient.postAbs((String)urlValue)
                 .sendJson(request)
                 .onSuccess(response -> {
                   String json = response.bodyAsString();
                   Object body = json == null ? null : io.jactl.runtime.Json.fromJson(json, source, offset);
                   String name = response.statusCode() / 100 != 2 ? "errorMsg" : "response";
                   resumer.accept(Utils.mapOf("statusCode", response.statusCode(), name, body));
                 })
                 .onFailure(res -> {
                   // Unexpected error
                   resumer.accept(new RuntimeError("Error invoking " + urlValue, source, offset, res));
                 });
      }
      catch (Throwable e) {
        resumer.accept(new RuntimeError("Error invoking " + urlValue, source, offset, e));
      }
    });
    return null;       // never happens since function is async
  }
  public static Object sendReceiveJsonData;

  ///////////////////////////////

  private static Map<Long,HttpServerResponse> responses = new HashMap<>();
  private static long responseId = System.currentTimeMillis() * 1_000_000;

  public static boolean respond(long responseId, int status, Object responseResult) {
    HttpServerResponse response = null;
    synchronized (responses) {
      response = responses.get(responseId);
    }
    if (response == null) {
      // Probably due to recovering checkpointed state in new process so response no longer exists
      return false;
    }

    response.setStatusCode(status)
            .end(Json.encode(responseResult))
            .onFailure(err -> err.printStackTrace());
    return true;
  }
  public static Object respondData;

  public static long registerResponse(HttpServerResponse response) {
    synchronized (responses) {
      long id = responseId++;
      responses.put(id, response);
      return id;
    }
  }

  public static void deregisterResponse(long responseId) {
    synchronized (responses) {
      responses.remove(responseId);
    }
  }

  public static Object checkpointsEnabled(boolean state) {
    JactlVertxEnv.checkpointEnabled.set(state);
    return null;
  }
  public static Object checkpointsEnabledData;
}
