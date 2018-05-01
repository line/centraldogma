/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.centraldogma.client.armeria;

import static com.linecorp.centraldogma.client.armeria.EndpointListCodecUtils.convertToEndpointList;
import static com.linecorp.centraldogma.client.armeria.EndpointListCodecUtils.objectMapper;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.Endpoint;

final class JsonEndpointListDecoder implements EndpointListDecoder<JsonNode> {
    @Override
    public List<Endpoint> decode(JsonNode node) {
        final List<String> endpoints;
        try {
            endpoints = objectMapper.readValue(node.traverse(),
                                               new TypeReference<List<String>>() {});
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid format: " + node);
        }
        return convertToEndpointList(endpoints);
    }
}
