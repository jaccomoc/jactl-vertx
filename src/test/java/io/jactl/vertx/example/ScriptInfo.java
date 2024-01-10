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

import io.jactl.Jactl;
import io.jactl.JactlContext;
import io.jactl.JactlError;
import io.jactl.JactlScript;
import io.vertx.core.Vertx;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class ScriptInfo {
  private static final String                  SCRIPT_DIR = "scripts";
  // Map of scripts keyed on URI
  private static       Map<String, ScriptInfo> scripts    = new ConcurrentHashMap<>();
  public  static       boolean                 checkForModifications = true;

  public String      name;               // The script "name" (corresponds to the uri)
  public JactlScript script;             // The compiled script
  public long        modificationTime;   // File modification time
  public long        lastCheckTime;      // Time we last checked for file modification


  // Status code to use if error finding/compiling script:
  //   404 - no such script
  //   501 - compile error
  public int       statusCode = 200;
  public Throwable error;

  ScriptInfo(String name, JactlScript script, long time) {
    this.name = name;
    this.script = script;
    this.modificationTime = time;
    this.lastCheckTime = System.currentTimeMillis();
  }

  ScriptInfo(String name, int statusCode, Throwable err) {
    this.name = name;
    this.statusCode = statusCode;
    this.error = err;
    this.lastCheckTime = System.currentTimeMillis();
  }

  public static List<String> compileScripts(Map<String,Object> globals, JactlContext context, Vertx vertx) throws IOException {
    ArrayList<CompletableFuture> futures     = new ArrayList<CompletableFuture>();
    ArrayList<String>            scriptNames = new ArrayList<String>();
    Stream.of(Objects.requireNonNull(new File(SCRIPT_DIR).listFiles()))
          .filter(file -> !file.isDirectory())
          .map(File::getName)
          .filter(name -> name.endsWith(".jactl"))
          .map(name -> name.substring(0, name.length() - ".jactl".length()))
          .forEach(name -> {
            CompletableFuture f = new CompletableFuture();
            futures.add(f);
            scriptNames.add(name);
            findScript(name, globals, context, vertx, scriptInfo -> f.complete(scriptInfo));
          });
    futures.forEach(f -> {
      try {
        f.get();
      }
      catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }
    });
    return scriptNames;
  }

  /**
   * Look for a script called scriptName.jactl in directory called scripts and compile it if it exists. Cache the result
   * so next time we don't have to recompile.
   *
   * @param scriptName the "name" of the script (corresponds to the uri of the request)
   * @param handler    the handler to invoke once we have a compiled script
   */
  public static void findScript(String scriptName, Map<String, Object> globals, JactlContext jactlContext, Vertx vertx, Consumer<ScriptInfo> handler) {
    ScriptInfo scriptInfo = scripts.get(scriptName);
    if (scriptInfo == null || fileIsModified(scriptInfo)) {
      vertx.executeBlocking(prom -> {
                              synchronized (ScriptInfo.class) {
                                ScriptInfo entry = compileScript(scriptName, globals, jactlContext);
                                scripts.put(scriptName, entry);
                                prom.complete(entry);
                              }
                            },
                            res -> {
                              ScriptInfo result = (ScriptInfo) res.result();
                              handler.accept(result);
                            });
    }
    else {
      handler.accept(scriptInfo);
    }
  }

  /**
   * Compile script with given name under the SCRIPT_DIR directory.
   *
   * @param name the name (suffix of ".jactl" will be applied to generate file name)
   * @return a ScriptInfo entry with script and modification time set
   * @throws Exception on error
   */
  private static ScriptInfo compileScript(String name, Map<String, Object> globals, JactlContext jactlContext) {
    try {
      long   modificationTime = fileModificationTime(name);
      String      source = new String(Files.readAllBytes(Paths.get(SCRIPT_DIR, name + ".jactl")));
      JactlScript script = Jactl.compileScript(source, globals, jactlContext);
      return new ScriptInfo(name, script, modificationTime);
    }
    catch (JactlError e) {
      e.printStackTrace();
      return new ScriptInfo(name, 501, e);
    }
    catch (Throwable t) {
      t.printStackTrace();
      return new ScriptInfo(name, 404, t);
    }
  }

  /**
   * Check if file has been modified since last time we checked. If we have checked very recently we return false
   * without actually validating the file system modification time to prevent us hammering the file system
   * unnecessarily. NOTE: this will update the lastCheckTime field of scriptWithTime.
   *
   * @param scriptInfo the ScriptWithTime entry
   * @return true if file has changed
   */
  private static boolean fileIsModified(ScriptInfo scriptInfo) {
    if (!checkForModifications) {
      return false;
    }
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
}
