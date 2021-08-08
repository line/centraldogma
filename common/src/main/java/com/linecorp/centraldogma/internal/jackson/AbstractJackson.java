/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.internal.jackson;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.datatype.jsr310.deser.InstantDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.InstantSerializer;

public abstract class AbstractJackson implements Jackson {

    private final ObjectMapper compactMapper;
    private final ObjectMapper prettyMapper;
    private final JsonFactory compactFactory;
    private final JsonFactory prettyFactory;

    protected AbstractJackson(ObjectMapper compactMapper, ObjectMapper prettyMapper,
                              JsonFactory compactFactory, JsonFactory prettyFactory) {
        this.compactMapper = compactMapper;
        this.prettyMapper = prettyMapper;
        this.compactFactory = compactFactory;
        this.prettyFactory = prettyFactory;

        registerModules1(new SimpleModule().addSerializer(Instant.class, InstantSerializer.INSTANCE)
                                           .addDeserializer(Instant.class, InstantDeserializer.INSTANT));
    }

    @Override
    public <T> T readValue(String data, Class<T> type) throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, type);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    @Override
    public <T> T readValue(byte[] data, Class<T> type) throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, type);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    @Override
    public <T> T readValue(File file, Class<T> type) throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(file, type);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    @Override
    public <T> T readValue(String data, TypeReference<T> typeReference)
            throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, typeReference);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    @Override
    public <T> T readValue(byte[] data, TypeReference<T> typeReference)
            throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, typeReference);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    @Override
    public <T> T readValue(File file, TypeReference<T> typeReference) throws IOException {
        return compactMapper.readValue(file, typeReference);
    }

    @Override
    public JsonNode readTree(@Nullable String data) throws JsonParseException {
        if (data == null) {
            return nullNode;
        }
        try {
            return compactMapper.readTree(data);
        } catch (JsonParseException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    @Override
    public JsonNode readTree(@Nullable byte[] data) throws JsonParseException {
        if (data == null) {
            return nullNode;
        }
        try {
            return compactMapper.readTree(data);
        } catch (JsonParseException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    @Override
    public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
        return compactMapper.writeValueAsBytes(value);
    }

    @Override
    public String writeValueAsString(Object value) throws JsonProcessingException {
        return compactMapper.writeValueAsString(value);
    }

    @Override
    public <T extends JsonNode> T valueToTree(Object value) {
        return compactMapper.valueToTree(value);
    }

    @Override
    public <T> T treeToValue(TreeNode node, Class<T> valueType)
            throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.treeToValue(node, valueType);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (JsonProcessingException e) {
            // Should never reach here.
            throw new IllegalStateException(e);
        }
    }

    @Override
    public <T> T convertValue(Object fromValue, Class<T> toValueType) {
        return compactMapper.convertValue(fromValue, toValueType);
    }

    @Override
    public <T> T convertValue(Object fromValue, TypeReference<T> toValueTypeRef) {
        return compactMapper.convertValue(fromValue, toValueTypeRef);
    }

    @Override
    public JsonGenerator createGenerator(Writer writer) throws IOException {
        return compactFactory.createGenerator(writer);
    }

    @Override
    public JsonGenerator createPrettyGenerator(Writer writer) throws IOException {
        final JsonGenerator generator = prettyFactory.createGenerator(writer);
        generator.useDefaultPrettyPrinter();
        return generator;
    }

    @Override
    public String textValue(@Nullable JsonNode node, String defaultValue) {
        return node != null && node.getNodeType() == JsonNodeType.STRING ? node.textValue() : defaultValue;
    }

    @Override
    public JsonNode extractTree(JsonNode jsonNode, Iterable<String> jsonPaths) {
        for (String jsonPath : jsonPaths) {
            jsonNode = extractTree(jsonNode, jsonPath);
        }
        return jsonNode;
    }
}
