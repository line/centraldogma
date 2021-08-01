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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.SegmentedStringWriter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JacksonJson {

    private final ObjectMapper compactMapper;
    private final ObjectMapper prettyMapper;

    public JacksonJson(ObjectMapper compactMapper, ObjectMapper prettyMapper) {
        this.compactMapper = compactMapper;
        this.prettyMapper = prettyMapper;
    }

    public <T> T readValue(String data, Class<T> type) throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, type);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public <T> T readValue(byte[] data, Class<T> type) throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, type);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public <T> T readValue(File file, Class<T> type) throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(file, type);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

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

    public <T> T readValue(File file, TypeReference<T> typeReference) throws IOException {
        return compactMapper.readValue(file, typeReference);
    }

    public JsonNode readTree(String data) throws JsonParseException {
        try {
            return compactMapper.readTree(data);
        } catch (JsonParseException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public JsonNode readTree(byte[] data) throws JsonParseException {
        try {
            return compactMapper.readTree(data);
        } catch (JsonParseException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
        return compactMapper.writeValueAsBytes(value);
    }

    public String writeValueAsString(Object value) throws JsonProcessingException {
        return compactMapper.writeValueAsString(value);
    }

    public String writeValueAsPrettyString(Object value) throws JsonProcessingException {
        // XXX(trustin): prettyMapper.writeValueAsString() does not respect the custom pretty printer
        //               set via ObjectMapper.setDefaultPrettyPrinter() for an unknown reason, so we
        //               create a generator manually and set the pretty printer explicitly.
        final JsonFactory factory = prettyMapper.getFactory();
        final SegmentedStringWriter sw = new SegmentedStringWriter(factory._getBufferRecycler());
        try {
            final JsonGenerator g = prettyMapper.getFactory().createGenerator(sw);
            g.setPrettyPrinter(new PrettyPrinterImpl());
            prettyMapper.writeValue(g, value);
        } catch (JsonProcessingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
        return sw.getAndClear();
    }

    private static class PrettyPrinterImpl extends DefaultPrettyPrinter {
        private static final long serialVersionUID = 8408886209309852098L;

        // The default object indenter uses platform-dependent line separator, so we define one
        // with a fixed separator (\n).
        private static final Indenter objectIndenter = new DefaultIndenter("  ", "\n");

        @SuppressWarnings("AssignmentToSuperclassField")
        PrettyPrinterImpl() {
            _objectFieldValueSeparatorWithSpaces = ": ";
            _objectIndenter = objectIndenter;
        }
    }
}
