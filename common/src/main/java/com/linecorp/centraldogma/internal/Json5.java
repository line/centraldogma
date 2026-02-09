/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.internal;

import java.io.IOError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.InstantDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.InstantSerializer;
import com.google.common.base.Ascii;

/**
 * JSON5 parser that enables <a href="https://json5.org/">JSON5</a> features that Jackson supports.
 * Example of supported JSON5 features:
 * <pre>{@code
 * {
 *   // comments
 *   unquoted: 'and you can quote me on that',
 *   singleQuotes: 'I can use "double quotes" here',
 *   lineBreaks: "Look, Mom! \
 * No \\n's!",
 *   leadingDecimalPoint: .8675309,
 *   positiveSign: +1,
 *   trailingComma: 'in objects', andIn: ['arrays',],
 *   "backwardsCompatible": "with JSON",
 * }
 * }</pre>
 *
 * <p>The unsupported features are listed below:
 * <ul>
 *   <li>Hexadecimal integers are not supported. (e.g., 0xdecaf)</li>
 *   <li>Trailing decimal points are not supported. (e.g., 8675309.)</li>
 * </ul>
 */
public final class Json5 {
    private static final ObjectMapper mapper = new ObjectMapper();

    static {
        // Sort the attributes when serialized via the mapper.
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

        mapper.enable(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(),
                      JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature(),
                      JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(),
                      JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(),
                      JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(),
                      JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature(),
                      JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS.mappedFeature(),
                      JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS.mappedFeature());

        mapper.registerModules(new SimpleModule().addSerializer(Instant.class, InstantSerializer.INSTANCE)
                                                 .addDeserializer(Instant.class, InstantDeserializer.INSTANT));
    }

    public static boolean isJsonCompatible(String path) {
        return isJson(path) || isJson5(path);
    }

    public static boolean isJson5(String path) {
        return Ascii.toLowerCase(path).endsWith(".json5");
    }

    public static boolean isJson(String path) {
        return Ascii.toLowerCase(path).endsWith(".json");
    }

    public static JsonNode readTree(String data) throws JsonParseException, JsonMappingException {
        try {
            return mapper.readTree(data);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static JsonNode readTree(byte[] data) throws JsonParseException, JsonMappingException {
        return readTree(new String(data, StandardCharsets.UTF_8));
    }

    public static <T> T readValue(String data, Class<T> valueType) throws JsonProcessingException {
        return mapper.readValue(data, valueType);
    }

    private Json5() {}
}
