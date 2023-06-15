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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.jactl.Jactl;
import io.jactl.Utils;
import io.jactl.runtime.Continuation;
import io.vertx.core.Vertx;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.EncodeException;
import io.vertx.core.json.Json;
import io.jactl.JactlEnv;
import io.jactl.JactlType;
import io.jactl.runtime.RuntimeError;
import io.vertx.core.shareddata.AsyncMap;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class registers two methods:
 * <dl>
 *    <dt>Object.toJson()</dt><dd>Converts any standard object to JSON string</dd>
 *    <dt>String.fromJson()</dt><dd>Converts from a JSON string back to an Object</dd>
 * </dl>
 */
public class JsonFunctions {
  private static       Vertx                                              vertx;
  private static final ConcurrentHashMap<String, AsyncMap<Object,Object>> asyncMaps = new ConcurrentHashMap<>();

  /**
   * Initialisation: registers the methods.
   * @param env the env
   */
  public static void registerFunctions(JactlEnv env) {
    vertx = ((JactlVertxEnv) env).vertx();

    // Ensure that we use BigDecimal for decoding floating point numbers and make sure when
    // encoding BigDecimal to JSON that we don't use scientific notation.
    ObjectMapper mapper = ((io.vertx.core.json.jackson.DatabindCodec)io.vertx.core.json.Json.CODEC).mapper();
    mapper.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    Jactl.method(JactlType.ANY)
         .name("toJsonVertx")
         .impl(JsonFunctions.class, "toJson")
         .register();

    Jactl.method(JactlType.STRING)
         .name("fromJsonVertx")
         .impl(JsonFunctions.class, "fromJson")
         .register();

    Jactl.function()
         .name("distributedPut")
         .param("map")
         .param("key")
         .param("value")
         .param("ttl", 0)
         .impl(JsonFunctions.class,"distributedPut")
         .register();

    Jactl.function()
         .name("distributedGet")
         .param("map")
         .param("key")
         .impl(JsonFunctions.class,"distributedGet")
         .register();

    Jactl.function()
         .name("distributedRemove")
         .param("map")
         .param("key")
         .impl(JsonFunctions.class,"distributedRemove")
         .register();

  }

  /**
   * Deregister the functions/methods. This allows us to run multiple tests and register/deregister each time.
   */
  public static void deregisterFunctions() {
    Jactl.deregister(JactlType.ANY,    "toJsonVertx");
    Jactl.deregister(JactlType.STRING, "fromJsonVertx");
    Jactl.deregister("distributedPut");
    Jactl.deregister("distributedGet");
    Jactl.deregister("distributedRemove");
  }

  /**
   * Convert an object to a JSON string.
   * @param obj     the object to be converted
   * @param source  the source code (for error reporting)
   * @param offset  offset into the source code (for error reporting)
   * @return the JSON string
   */
  public static String toJson(Object obj, String source, int offset) {
    try {
      return Json.encode(obj);
    }
    catch (EncodeException e) {
      throw new RuntimeError("Error encoding to Json", source, offset, e);
    }
  }
  public static Object toJsonData;

  /**
   * Convert a JSON string to a Map
   * @param json    the JSON string
   * @param source  the source code (for error reporting)
   * @param offset  offset into the source code (for error reporting)
   * @return a Map formed by decoding the JSON string
   * @throws RuntimeError if the string is not well-formed JSON
   */
  public static Object fromJson(String json, String source, int offset) {
    json = json.trim();
    try {
      if (json.isEmpty()) {
        return Json.decodeValue(json, Map.class);  // will generate a decode error
      }
      char c = json.charAt(0);
      switch (c) {
        case '[': {
          Object result = Json.decodeValue(json, List.class);
          return result;
        }
        case '{': {
          Object result = Json.decodeValue(json, Map.class);
          return result;
        }
        default : {
          if (Character.isDigit(c) && json.indexOf('.') != -1) {
            return new BigDecimal(json);
          }
          return Json.decodeValue(json, Object.class);
        }
      }
    }
    catch (NumberFormatException | DecodeException e) {
      throw new RuntimeError("Error decoding Json", source, offset, e);
    }
  }
  public static Object fromJsonData;

  private static void validateMapName(String mapName, String source, int offset) {
    if (mapName.startsWith(Utils.JACTL_PREFIX)) {
      throw new RuntimeError("Map names must not begin with " + Utils.JACTL_PREFIX, source, offset);
    }
  }

  public static Object distributedPut(Continuation c, String source, int offset, String map, String key, Object value, int ttlMs) {
    validateMapName(map, source, offset);
    Continuation.suspendNonBlocking(source, offset, null, (context, data, resumer) -> {
      var asyncMap = asyncMaps.get(map);
      if (asyncMap != null) {
        asyncMap.put(key, value)
                .onSuccess(res -> resumer.accept(value))
                .onFailure(err -> resumer.accept(new RuntimeError("distributedPut error: " + err.getMessage(), source, offset, err)));
      }
      else {
        vertx.sharedData()
             .getAsyncMap(map)
             .onSuccess(aMap -> {
               asyncMaps.put(map, aMap);
               if (ttlMs == 0) {
                 aMap.put(key, value)
                     .onSuccess(res -> resumer.accept(value))
                     .onFailure(err -> resumer.accept(new RuntimeError("distributedPut error: " + err.getMessage(), source, offset, err)));
               }
               else {
                 aMap.put(key, value, ttlMs)
                     .onSuccess(res -> resumer.accept(value))
                     .onFailure(err -> resumer.accept(new RuntimeError("distributedPut error: " + err.getMessage(), source, offset, err)));
               }
             })
             .onFailure(err -> resumer.accept(new RuntimeError("distributedPut error: " + err.getMessage(), source, offset, err)));
      }
    });
    return null;
  }
  public static Object distributedPutData;

  public static Object distributedGet(Continuation c, String source, int offset, String map, String key) {
    validateMapName(map, source, offset);
    Continuation.suspendNonBlocking(source, offset, null, (context, data, resumer) -> {
      var asyncMap = asyncMaps.get(map);
      if (asyncMap != null) {
        asyncMap.get(key)
                .onSuccess(resumer::accept)
                .onFailure(err -> resumer.accept(new RuntimeError("distributedGet error: " + err.getMessage(), source, offset, err)));
      }
      else {
        vertx.sharedData()
             .getAsyncMap(map)
             .onSuccess(aMap -> {
               asyncMaps.put(map, aMap);
               aMap.get(key)
                   .onSuccess(resumer::accept)
                   .onFailure(err -> resumer.accept(new RuntimeError("distributedGet error: " + err.getMessage(), source, offset, err)));

             })
             .onFailure(err -> resumer.accept(new RuntimeError("distributedGet error: " + err.getMessage(), source, offset, err)));
      }
    });
    return null;
  }
  public static Object distributedGetData;

  public static Object distributedRemove(Continuation c, String source, int offset, String map, String key) {
    validateMapName(map, source, offset);
    Continuation.suspendNonBlocking(source, offset, null, (context, data, resumer) -> {
      var asyncMap = asyncMaps.get(map);
      if (asyncMap != null) {
        asyncMap.remove(key)
                .onSuccess(resumer::accept)
                .onFailure(err -> resumer.accept(new RuntimeError("distributedRemove error: " + err.getMessage(), source, offset, err)));
      }
      else {
        vertx.sharedData()
             .getAsyncMap(map)
             .onSuccess(aMap -> {
               asyncMaps.put(map, aMap);
               aMap.remove(key)
                   .onSuccess(resumer::accept)
                   .onFailure(err -> resumer.accept(new RuntimeError("distributedRemove error: " + err.getMessage(), source, offset, err)));

             })
             .onFailure(err -> resumer.accept(new RuntimeError("distributedRemove error: " + err.getMessage(), source, offset, err)));
      }
    });
    return null;
  }
  public static Object distributedRemoveData;
}
