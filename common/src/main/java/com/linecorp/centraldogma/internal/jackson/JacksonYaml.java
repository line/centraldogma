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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

public final class JacksonYaml extends AbstractJackson {

    private static final YAMLMapper yamlMapper = new YAMLMapper();

    static {
        yamlMapper.disable(Feature.WRITE_DOC_START_MARKER);
        yamlMapper.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    private static final YAMLFactory yamlFactory = yamlMapper.getFactory();

    public static final Jackson INSTANCE = new JacksonYaml();

    private JacksonYaml() {
        super(yamlMapper, yamlMapper, yamlFactory, yamlFactory);
    }

    @Override
    public void registerModules1(Module... modules) {
        yamlMapper.registerModules(modules);
    }

    @Override
    public void registerSubtypes1(NamedType... subtypes) {
        yamlMapper.registerSubtypes(subtypes);
    }

    @Override
    public void registerSubtypes1(Class<?>... subtypes) {
        yamlMapper.registerSubtypes(subtypes);
    }

    @Override
    public String writeValueAsPrettyString(Object value) throws JsonProcessingException {
        return writeValueAsString(value);
    }

    @Override
    public JsonNode extractTree(JsonNode jsonNode, String jsonPath) {
        // Implemented in JacksonJson
        return Jackson.ofJson().extractTree(jsonNode, jsonPath);
    }
}
