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

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

public final class Yaml {

    private static final YAMLMapper mapper = new YAMLMapper();

    static {
        mapper.disable(Feature.WRITE_DOC_START_MARKER);
        mapper.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
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
        try {
            return mapper.readTree(data);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static String writeValueAsString(Object node) throws JsonProcessingException {
        return mapper.writeValueAsString(node);
    }

    public static boolean isYaml(String filename) {
        return filename.endsWith(".yaml") || filename.endsWith(".yml");
    }

    private Yaml() {}
}
