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

import com.hazelcast.config.Config;
import io.jactl.runtime.JsonDecoder;
import io.jactl.vertx.JactlVertxEnv;
import io.jactl.vertx.VertxFunctions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.spi.cluster.NodeListener;
import io.vertx.ext.web.Router;
import io.jactl.JactlContext;
import io.jactl.runtime.DieError;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
  private static final long    START_TIME = System.currentTimeMillis();
  private static       String  HOST       = "localhost";       // Address to bind to
  private static       int     PORT       = 0;                 // Port to bind to (0 - pick a random port)

  private static Map<String,Object> globals;
  private static Vertx              vertx;
  private static JactlContext       jactlContext;
  private static String             pathPrefix = "";

  public static void main(String[] args) throws ExecutionException, InterruptedException, IOException {
    String hostPort = null;
    int threads = -1;
    int workerPoolSize = VertxOptions.DEFAULT_WORKER_POOL_SIZE;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "-t":
          threads = Integer.parseInt(args[++i]);
          break;
        case "-P":
          pathPrefix = args[++i];
          if (!pathPrefix.startsWith("/")) {
            System.err.println("Path prefix must start with '/'");
            System.exit(1);
          }
          break;
        case "-w":
          workerPoolSize = Integer.parseInt(args[++i]);
          break;
        default:
          hostPort = args[i];
          break;
      }
    }
    if (hostPort != null) {
      int    colonPos = hostPort.indexOf(':');
      HOST = colonPos == -1 ? "localhost" : hostPort.substring(0, colonPos);
      HOST = HOST.isEmpty() ? "*" : HOST;
      String portStr = hostPort.substring(colonPos + 1);
      PORT = portStr.isEmpty() ? 0 : Integer.parseInt(portStr);
    }

    // Default values for global vars to be passed in to each script
    globals = Map.of("request",    new LinkedHashMap<>(),
                     "responseId", 0L,
                     "host",      HOST,
                     "port",      PORT,
                     "baseUrl",   "http://" + HOST + ":" + PORT + pathPrefix,
                     "startTime", START_TIME);

    if (Boolean.getBoolean("CLUSTERED")) {
      log("Clustered: namespace=" + System.getProperty("KUBERNETES_NAMESPACE") + ", service-name=" + System.getProperty("SERVICE_NAME"));
      Config hazelCastConf = new Config();
      hazelCastConf.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
      hazelCastConf.getNetworkConfig().getJoin().getKubernetesConfig()
                   .setEnabled(true)
                   .setProperty("namespace", System.getProperty("KUBERNETES_NAMESPACE"))
                   .setProperty("service-name", System.getProperty("SERVICE_NAME"));
      HazelcastClusterManager mgr = new HazelcastClusterManager(hazelCastConf);
      mgr.nodeListener(new NodeListener() {
        @Override public void nodeAdded(String nodeID) { log("Node added: " + nodeID); }
        @Override public void nodeLeft(String nodeID)  { log("Node left:  " + nodeID); }
      });
      CompletableFuture<Vertx> vertxFuture = new CompletableFuture<>();
      var options = new VertxOptions().setClusterManager(mgr)
                                      .setWorkerPoolSize(workerPoolSize);
      Vertx.clusteredVertx(options)
           .onSuccess(result -> vertxFuture.complete(result))
           .onFailure(err -> { err.printStackTrace(); System.exit(1); });
      Vertx vtx = vertxFuture.get();
      log("Cluster node id: " + mgr.getNodeId());
      String podId = System.getProperty("POD_NAME");
      log("Pod name: " + podId);
      init(vtx, threads, podId);
    }
    else {
      init(Vertx.vertx(), threads, null);
    }
  }

  private static void init(Vertx vtx, int threads, String podId) throws IOException, ExecutionException, InterruptedException {
    vertx = vtx;
    JactlVertxEnv env = new JactlVertxEnv(vertx, podId);
    ExampleFunctions.registerFunctions(env);
    VertxFunctions.registerFunctions(env);
    jactlContext = JactlContext.create().environment(env).build();

    log("Compiling scripts");
    ScriptInfo.compileScripts(globals, jactlContext, vertx);
    log("Scripts compiled");

    DeploymentOptions options = new DeploymentOptions().setInstances(threads == -1 ? Runtime.getRuntime().availableProcessors() : threads);
    vertx.deployVerticle(WebServer.class, options);
    log("Web Server Verticles deployed");
    int count = env.recoverCheckpoints(jactlContext);
    log("Recovered " + count + " script instances");
  }

  private static SimpleDateFormat timeFmt = new SimpleDateFormat("hh:mm:ss.SSS");
  private static synchronized void log(String msg) {
    System.out.println(timeFmt.format(new Date()) + ": " + msg);
  }

  public static class WebServer extends AbstractVerticle {

    @Override
    public void start() throws Exception {
      var server = vertx.createHttpServer();

      // Helper to encode an Exception as a Map
      Function<Throwable, String> errMsg = err -> err.getClass().getName() + ": " + err.getMessage();

      Function<Buffer, Object> fromJson = buf -> JsonDecoder.get(buf.toString(), "", 0);

      // Configure HTTP server router to handle inbound requests
      Router router = Router.router(vertx);
      router.route(pathPrefix + "/:script").handler(ctx -> {
        var request  = ctx.request();
        var response = ctx.response();
        request.pause();                              // Pause while finding/compiling script

        // Find and execute script with name of uri (if script exists)
        ScriptInfo.findScript(ctx.pathParam("script"), globals, jactlContext, vertx, scriptInfo -> {
          request.resume();
          // Result is either a script or an Exception if something went wrong during compilation
          if (scriptInfo.statusCode != 200) {
            response.setStatusCode(scriptInfo.statusCode)
                    .end(Json.encode(errMsg.apply(scriptInfo.error)));
          }
          else {
            request.bodyHandler(buf -> {
              long responseId = ExampleFunctions.registerResponse(response);
              // Pass in body of request bound to global variable "request"
              try {
                Map<String, Object> bindings = new LinkedHashMap<>(globals) {{
                  put("request", fromJson.apply(buf));
                  put("responseId", responseId);
                }};

                // Invoke script with given bindings for global variables
                scriptInfo.script.run(bindings, result -> {
                  if (!response.ended()) {
                    var responseResult = result instanceof Exception ? errMsg.apply((Exception) result) : result;
                    int status         = result instanceof DieError ? 400 : result instanceof Exception ? 500 : 200;
                    try {
                      response.setStatusCode(status)
                              .end(Json.encode(responseResult))
                              .onFailure(err -> err.printStackTrace());
                    }
                    catch (Throwable t) {
                      t.printStackTrace();
                    }
                  }
                });
              }
              catch (Exception e) {
                e.printStackTrace();
                response.setStatusCode(404).end(Json.encode(errMsg.apply(e)));
              }
              catch (Throwable t) {
                t.printStackTrace();
                response.setStatusCode(501).end(Json.encode(errMsg.apply(t)));
              }
            });
          }
        });
      });

      // Start HTTP server with given router
      if (HOST.equals("*")) {
        HOST = "0.0.0.0";
      }
      else {
        try {
          var addr = InetAddress.getByName(HOST);
          if (!addr.isSiteLocalAddress() && !addr.isLoopbackAddress()) {
            System.err.println(HOST + " is not local to this server");
            System.exit(1);
          }
        }
        catch (UnknownHostException e) {
          System.err.println(HOST + ": unknown host");
          System.exit(1);
        }
      }

      server.requestHandler(router)
            .listen(PORT, HOST)
            .onSuccess(svr -> {
              PORT = server.actualPort();
              log("Listening on " + HOST + ":" + PORT);
            })
            .onFailure(err -> {
              System.err.println("Error listening on " + HOST + ":" + PORT + ": " + err.getMessage());
              err.printStackTrace();
              System.exit(1);
            });
    }
  }

}
