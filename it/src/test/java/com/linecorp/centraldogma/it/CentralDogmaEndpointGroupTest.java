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

package com.linecorp.centraldogma.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import org.junit.Rule;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.client.armeria.CentralDogmaEndpointGroup;
import com.linecorp.centraldogma.client.armeria.EndpointListDecoder;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class CentralDogmaEndpointGroupTest {
    private static List<String> HOST_AND_PORT_LIST = ImmutableList.of(
            "centraldogma-sample001.com:1234",
            "1.2.3.4:5678"
    );
    private static String HOST_AND_PORT_LIST_JSON;

    static {
        try {
            HOST_AND_PORT_LIST_JSON = new ObjectMapper().writeValueAsString(HOST_AND_PORT_LIST);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Endpoint> ENDPOINT_LIST = ImmutableList.of(
            Endpoint.of("centraldogma-sample001.com", 1234),
            Endpoint.of("1.2.3.4", 5678)
    );

    @Rule
    public final CentralDogmaRule dogma = new CentralDogmaRule() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("directory").join();
            client.createRepository("directory", "my-service").join();
            client.push("directory", "my-service",
                        Revision.HEAD, "commit",
                        Change.ofJsonUpsert("/endpoint.json", HOST_AND_PORT_LIST_JSON))
                  .join();
            client.push("directory", "my-service",
                        Revision.HEAD, "commit",
                        Change.ofTextUpsert("/endpoint.txt",
                                            HOST_AND_PORT_LIST.stream().collect(Collectors.joining("\n"))))
                  .join();
        }
    };

    @Test
    public void json() {
        Watcher<JsonNode> watcher = dogma.client().fileWatcher("directory", "my-service",
                                                               Query.ofJson("/endpoint.json"));
        CentralDogmaEndpointGroup<JsonNode> endpointGroup = CentralDogmaEndpointGroup.ofWatcher(
                watcher, EndpointListDecoder.JSON);
        assertThat(endpointGroup.endpoints()).isEqualTo(ENDPOINT_LIST);

        watcher.close();
    }

    @Test(timeout = 10000)
    public void text() throws Exception {
        Watcher<String> watcher = dogma.client().fileWatcher("directory", "my-service",
                                                             Query.ofText("/endpoint.txt"));
        CountDownLatch latch = new CountDownLatch(2);
        watcher.watch(unused -> latch.countDown());
        CentralDogmaEndpointGroup<String> endpointGroup = CentralDogmaEndpointGroup.ofWatcher(
                watcher, EndpointListDecoder.TEXT);
        assertThat(endpointGroup.endpoints()).isEqualTo(ENDPOINT_LIST);
        assertThat(latch.getCount()).isOne();

        dogma.client().push("directory", "my-service",
                            Revision.HEAD, "commit",
                            Change.ofTextUpsert("/endpoint.txt",
                                                "foo.bar:1234"))
             .join();

        latch.await();
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("foo.bar", 1234)));
        watcher.close();
    }
}
