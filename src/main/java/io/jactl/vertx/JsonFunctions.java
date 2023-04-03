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
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.EncodeException;
import io.vertx.core.json.Json;
import io.jactl.JactlEnv;
import io.jactl.JactlType;
import io.jactl.runtime.BuiltinFunctions;
import io.jactl.runtime.JactlFunction;
import io.jactl.runtime.RuntimeError;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * This class registers two methods:
 * <dl>
 *    <dt>Object.toJson()</dt><dd>Converts any standard object to JSON string</dd>
 *    <dt>String.fromJson()</dt><dd>Converts from a JSON string back to an Object</dd>
 * </dl>
 */
public class JsonFunctions {
  /**
   * Initialisation: registers the methods.
   */
  public static void registerFunctions(JactlEnv notused) {
    // Ensure that we use BigDecimal for decoding floating point numbers and make sure when
    // encoding BigDecimal to JSON that we don't use scientific notation.
    ObjectMapper mapper = ((io.vertx.core.json.jackson.DatabindCodec)io.vertx.core.json.Json.CODEC).mapper();
    mapper.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    BuiltinFunctions.registerFunction(new JactlFunction(JactlType.ANY)
                                        .name("toJson")
                                        .impl(JsonFunctions.class, "toJson"));
    BuiltinFunctions.registerFunction(new JactlFunction(JactlType.STRING)
                                        .name("fromJson")
                                        .impl(JsonFunctions.class, "fromJson"));
  }

  /**
   * Deregister the functions/methods. This allows us to run multiple tests and register/deregister each time.
   */
  public static void deregisterFunctions() {
    BuiltinFunctions.deregisterFunction(JactlType.ANY,    "toJson");
    BuiltinFunctions.deregisterFunction(JactlType.STRING, "fromJson");

    // Must reset these fields, or we will get an error when we try to re-register the functions
    toJsonData = fromJsonData = null;
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

}
