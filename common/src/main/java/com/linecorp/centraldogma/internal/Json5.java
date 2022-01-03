/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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
import java.time.Instant;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.InstantDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.InstantSerializer;

public final class Json5 {
    private static final ObjectMapper mapper = new ObjectMapper();

    static {
        // Sort the attributes when serialized via the mapper.
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

        // Enable JSON5 feature.
        mapper.enable(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(),
                      JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature(),
                      JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(),
                      JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(),
                      JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(),
                      JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature(),
                      JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS.mappedFeature());

        mapper.registerModules(new SimpleModule().addSerializer(Instant.class, InstantSerializer.INSTANCE)
                                                 .addDeserializer(Instant.class, InstantDeserializer.INSTANT));
    }

    public static JsonNode readTree(String data) throws JsonParseException {
        try {
            return mapper.readTree(data);
        } catch (JsonParseException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static JsonNode readTree(byte[] data) throws JsonParseException {
        try {
            return mapper.readTree(data);
        } catch (JsonParseException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    private Json5() {}
}
