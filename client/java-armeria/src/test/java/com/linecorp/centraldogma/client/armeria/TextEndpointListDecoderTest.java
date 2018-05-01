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

import static com.linecorp.centraldogma.client.armeria.JsonEndpointListDecoderTest.ENDPOINT_LIST;
import static com.linecorp.centraldogma.client.armeria.JsonEndpointListDecoderTest.HOST_AND_PORT_LIST;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;

public class TextEndpointListDecoderTest {
    @Test
    public void decode() {
        EndpointListDecoder<String> decoder = EndpointListDecoder.TEXT;
        List<Endpoint> decoded = decoder.decode(HOST_AND_PORT_LIST.stream().collect(Collectors.joining("\n")));

        assertThat(decoded).hasSize(4);
        assertThat(decoded).isEqualTo(ENDPOINT_LIST);
    }
}
