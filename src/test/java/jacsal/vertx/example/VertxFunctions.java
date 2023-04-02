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

package jacsal.vertx.example;

import io.vertx.ext.web.client.WebClient;
import jacsal.JacsalEnv;
import jacsal.runtime.BuiltinFunctions;
import jacsal.runtime.Continuation;
import jacsal.runtime.JacsalFunction;
import jacsal.runtime.RuntimeError;
import jacsal.vertx.JacsalVertxEnv;
import jacsal.vertx.JsonFunctions;

import java.util.Map;

/**
 * An example Jacsal function.
 *
 * It registers one global function:
 * <dl>
 *   <dt>sendReceiveJson</dt><dd>Send a JSON request and wait for a response</dd>
 * </dl>
 */
public class VertxFunctions {

  private static WebClient webClient;

  /**
   * Initialisation: registers the functions and methods and performs some initialisation.
   * NOTE: existence of a method called registerFunctions(JacsalEnv env) allows us to configure
   *       the REPL and the command line Jacsal script runner with these functions by adding
   *       this class and library to the .jacsalrc file.
   * @param env  the Jacsal env (which will be a JacsalVertxEnv)
   */
  public static void registerFunctions(JacsalEnv env) {
    webClient = WebClient.create(((JacsalVertxEnv)env).vertx());

    BuiltinFunctions.registerFunction(new JacsalFunction()
                                        .name("sendReceiveJson")
                                        .param("url")
                                        .param("request")
                                        .impl(VertxFunctions.class, "sendReceiveJson"));
  }

  /**
   * Deregister the functions/methods. This allows us to run multiple tests and register/deregister each time.
   * Only used by JUnit tests (see {@link jacsal.vertx.BaseTest}).
   */
  public static void deregisterFunctions() {
    BuiltinFunctions.deregisterFunction("sendReceive");

    // Must reset this field, or we will get an error when we try to re-register the function
    sendReceiveJsonData = null;
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
    Continuation.suspendNonBlocking((context, resumer) -> {
      try {
        webClient.postAbs(url)
                 .sendJson(request)
                 .onSuccess(res -> {
                   var body = JsonFunctions.fromJson(res.bodyAsString(), source, offset);
                   var name = res.statusCode() / 100 != 2 ? "errorMsg" : "response";
                   resumer.accept(Map.of("statusCode", res.statusCode(), name, body));
                 })
                 .onFailure(res -> {
                   // Unexpected error
                   resumer.accept(new RuntimeError("Error invoking " + url, source, offset, res));
                 });
      }
      catch (Exception e) {
        resumer.accept(new RuntimeError("Error invoking " + url, source, offset, e));
      }
    });
    return null;       // never happens since function is async
  }
  public static Object sendReceiveJsonData;
}
