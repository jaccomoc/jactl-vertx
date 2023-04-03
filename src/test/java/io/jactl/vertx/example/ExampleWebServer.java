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

import io.jactl.vertx.JactlVertxEnv;
import io.jactl.vertx.JsonFunctions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.jactl.Compiler;
import io.jactl.JactlContext;
import io.jactl.JactlError;
import io.jactl.JactlScript;
import io.jactl.runtime.DieError;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * <pr>An example of a Vertx based web server that executes Jactl scripts and returns the result to
 * the caller. It listens on localhost on a random port (it prints out the port at startup, so you
 * can work out how to send requests to it). You can bind to a specific port by passing the port
 * on the command line.</p>
 * <p>For a URL like "http://localhost:12345/xyz" it will look for a script called "scripts/xyz.jactl"
 * in the current directory where the server is running. If such a script exists it takes the JSON body
 * of the request, binds it to a global variable called "request", and then invokes the script.
 * The return value of the script is expected to be a Map and will be used as the body of the response
 * back to the caller (encoded as JSON).</p>
 * <p>The global variables passed into each script are:</p>
 * <dl>
 *   <dt><b>request</b></dt><dd>Map corresponding to JSON request</dd>
 *   <dt><b>host</b></dt><dd>The hostname we are bound to (localhost)</dd>
 *   <dt><b>port</b></dt><dd>The port we are listening on</dd>
 *   <dt><b>startTime</b></dt><dd>The time (in milliseconds) when server was started</dd>
 * </dl>
 * <p>For example, if we wanted to be able to offer a service at "http://localhost:12345/wordCount" that
 * counted the words in some text then we would create a script called scripts/wordCount.jactl:</p>
 * <pre>
 * // request should be of form [text:'some text to be word counted']
 *
 * die 'Missing value for text field' unless request.text
 * def badFields = request.filter{ k,v -> k != 'text' }.map{ k,v -> k }
 * die "Unknown field${badFields?'s':''}: ${badFields.join(', ')}"
 *
 * int words = 0
 * while (request.text =~ /\w+/g) {
 *   words++
 * }
 * return words
 * </pre>
 */
public class ExampleWebServer {
  private static final String  SCRIPT_DIR = "scripts";
  private static final String  HOST       = "localhost";       // Address to bind to
  private static       int     PORT       = 0;                 // Port to bind to (0 - pick a random port)
  private static final long    START_TIME = System.currentTimeMillis();

  private static Map<String,Object> globals;
  private static Vertx              vertx;
  private static JactlContext      jactlContext;

  // Map of scripts keyed on URI
  private static Map<String, ScriptInfo> scripts = new ConcurrentHashMap<>();

  public static void main(String[] args) {
    if (args.length > 0) {
      PORT = Integer.parseInt(args[0]);
    }

    // Default values for global vars to be passed in to each script
    globals = Map.of("request",   new LinkedHashMap<>(),
                     "host",      HOST,
                     "port",      PORT,
                     "startTime", START_TIME);

    vertx = Vertx.vertx();
    JactlVertxEnv env = new JactlVertxEnv(vertx);
    VertxFunctions.registerFunctions(env);
    jactlContext = JactlContext.create().environment(env).build();

    var server = vertx.createHttpServer();

    // Helper to encode an Exception as a Map
    Function<Exception,String> errMsg = err -> err.getClass().getName() + ": " + err.getMessage();

    Function<Buffer,Object> fromJson = buf -> JsonFunctions.fromJson(buf.toString(), "", 0);

    // Configure HTTP server router to handle inbound requests
    Router router = Router.router(vertx);
    router.route().handler(ctx -> {
      ctx.request().pause();                              // Pause while finding/compiling script

      // Find and execute script with name of uri (if script exists)
      findScript(ctx.request().uri(), scriptInfo -> {
        ctx.request().resume();
        // Result is either a script or an Exception if something went wrong during compilation
        if (scriptInfo.statusCode != 200) {
          ctx.response()
             .setStatusCode(scriptInfo.statusCode)
             .end(Json.encode(errMsg.apply(scriptInfo.error)));
        }
        else {
          ctx.request().bodyHandler(buf -> {
            // Pass in body of request bound to global variable "request"
            try {
              Map<String,Object> bindings = new LinkedHashMap<>(globals){{ put("request", fromJson.apply(buf)); }};

              // Invoke script with given bindings for global variables
              scriptInfo.script.run(bindings, result -> {
                var response = result instanceof Exception ? errMsg.apply((Exception)result) : result;
                int status   = result instanceof DieError ? 400 : result instanceof Exception ? 500 : 200;
                ctx.response()
                   .setStatusCode(status)
                   .end(Json.encode(response));
              });
            }
            catch (Exception e) {
              ctx.response().setStatusCode(404).end(Json.encode(errMsg.apply(e)));
            }
          });
        }
      });
    });

    // Start HTTP server with given router
    server.requestHandler(router).listen(0, HOST).onSuccess(svr -> {
      System.out.println("Listening on " +  server.actualPort());
    });

  }

  /**
   * Look for a script called scriptName.jactl in directory called scripts and compile it if it
   * exists. Cache the result so next time we don't have to recompile.
   * @param scriptName  the "name" of the script (corresponds to the uri of the request)
   * @param handler     the handler to invoke once we have a compiled script
   */
  private static void findScript(String scriptName, Consumer<ScriptInfo> handler) {
    ScriptInfo scriptInfo = scripts.get(scriptName);
    if (scriptInfo == null || fileIsModified(scriptInfo)) {
      vertx.executeBlocking(prom -> {
                                var entry = compileScript(scriptName);
                                scripts.put(scriptName, entry);
                                prom.complete(entry);
                            },
                            res -> handler.accept((ScriptInfo)res.result()));
    }
    else {
      handler.accept(scripts.get(scriptName));
    }
  }

  /**
   * Compile script with given name under the SCRIPT_DIR directory.
   * @param name        the name (suffix of ".jactl" will be applied to generate file name)
   * @return a ScriptInfo entry with script and modification time set
   * @throws Exception  on error
   */
  private static ScriptInfo compileScript(String name) {
    try {
      long modificationTime = fileModificationTime(name);
      String source = Files.readString(Path.of(SCRIPT_DIR, name + ".jactl"));
      var script = Compiler.compileScript(source, jactlContext, globals);
      return new ScriptInfo(name, script, modificationTime);
    }
    catch (JactlError e) {
      return new ScriptInfo(name, 501, e);
    }
    catch (Exception e) {
      return new ScriptInfo(name, 404, e);
    }
  }

  /**
   * Check if file has been modified since last time we checked. If we have checked very recently
   * we return false without actually validating the file system modification time to prevent us
   * hammering the file system unnecessarily.
   * NOTE: this will update the lastCheckTime field of scriptWithTime.
   * @param scriptInfo   the ScriptWithTime entry
   * @return true if file has changed
   */
  private static boolean fileIsModified(ScriptInfo scriptInfo) {
    // Only check file system every 5 seconds
    boolean isModified = false;
    long    now        = System.currentTimeMillis();
    if (now - scriptInfo.lastCheckTime > 5000) {
      isModified = fileModificationTime(scriptInfo.name) > scriptInfo.modificationTime;
    }
    scriptInfo.lastCheckTime = now;
    return isModified;
  }

  private static long fileModificationTime(String name) {
    return new File(SCRIPT_DIR, name + ".jactl").lastModified();
  }

  static class ScriptInfo {
    String       name;               // The script "name" (corresponds to the uri)
    JactlScript script;             // The compiled script
    long         modificationTime;   // File modification time
    long         lastCheckTime;      // Time we last checked for file modification

    // Status code to use if error finding/compiling script:
    //   404 - no such script
    //   501 - compile error
    int          statusCode = 200;
    Exception    error;

    ScriptInfo(String name, JactlScript script, long time) {
      this.name             = name;
      this.script           = script;
      this.modificationTime = time;
      this.lastCheckTime    = System.currentTimeMillis();
    }

    ScriptInfo(String name, int statusCode, Exception err) {
      this.name          = name;
      this.statusCode    = statusCode;
      this.error         = err;
      this.lastCheckTime = System.currentTimeMillis();
    }
  }
}
