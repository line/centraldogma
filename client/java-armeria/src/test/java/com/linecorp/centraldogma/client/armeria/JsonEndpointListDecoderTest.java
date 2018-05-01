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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

public class JsonEndpointListDecoderTest {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    static List<String> HOST_AND_PORT_LIST = ImmutableList.of(
            "centraldogma-sample001.com",
            "centraldogma-sample001.com:1234",
            "1.2.3.4",
            "1.2.3.4:5678"
    );

    static List<Endpoint> ENDPOINT_LIST = ImmutableList.of(
            Endpoint.of("centraldogma-sample001.com"),
            Endpoint.of("centraldogma-sample001.com", 1234),
            Endpoint.of("1.2.3.4"),
            Endpoint.of("1.2.3.4", 5678)
    );

    @Test
    public void decode() throws Exception {
        EndpointListDecoder<JsonNode> decoder = EndpointListDecoder.JSON;
        List<Endpoint> decoded = decoder.decode(
                objectMapper.readTree(objectMapper.writeValueAsString(HOST_AND_PORT_LIST)));

        assertThat(decoded).hasSize(4);
        assertThat(decoded).isEqualTo(ENDPOINT_LIST);
    }
}