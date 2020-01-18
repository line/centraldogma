/*
 * Copyright 2020 LINE Corporation
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

import static com.linecorp.centraldogma.client.armeria.JsonEndpointListDecoderTest.ENDPOINT_LIST;
import static com.linecorp.centraldogma.client.armeria.JsonEndpointListDecoderTest.HOST_AND_PORT_LIST;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;

class TextEndpointListDecoderTest {

    @Test
    void decode() {
        final EndpointListDecoder<String> decoder = EndpointListDecoder.TEXT;
        final List<Endpoint> decoded = decoder.decode(String.join("\n", HOST_AND_PORT_LIST));

        assertThat(decoded).hasSize(4);
        assertThat(decoded).isEqualTo(ENDPOINT_LIST);
    }
}
