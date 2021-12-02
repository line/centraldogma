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

package com.linecorp.centraldogma.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class CentralDogmaEndpointGroupTest {

    private static final List<String> HOST_AND_PORT_LIST = ImmutableList.of(
            "1.2.3.4:5678",
            "centraldogma-sample001.com:1234");

    private static final String HOST_AND_PORT_LIST_JSON;

    static {
        try {
            HOST_AND_PORT_LIST_JSON = new ObjectMapper().writeValueAsString(HOST_AND_PORT_LIST);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static final List<Endpoint> ENDPOINT_LIST = ImmutableList.of(
            Endpoint.of("1.2.3.4", 5678),
            Endpoint.of("centraldogma-sample001.com", 1234));

    @RegisterExtension
    final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("directory").join();
            client.createRepository("directory", "my-service").join();
            client.forRepo("directory", "my-service")
                  .commit("commit", Change.ofJsonUpsert("/endpoint.json", HOST_AND_PORT_LIST_JSON))
                  .push(Revision.HEAD)
                  .join();
            client.forRepo("directory", "my-service")
                  .commit("commit", Change.ofTextUpsert("/endpoints.txt",
                                                        String.join("\n", HOST_AND_PORT_LIST)))
                  .push(Revision.HEAD)
                  .join();
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void json() throws Exception {
        try (Watcher<JsonNode> watcher = dogma.client().forRepo("directory", "my-service")
                                              .watchingFile(Query.ofJson("/endpoint.json"))
                                              .newWatcher()) {
            final CentralDogmaEndpointGroup<JsonNode> endpointGroup = CentralDogmaEndpointGroup.builder(
                    watcher, EndpointListDecoder.JSON).build();
            endpointGroup.whenReady().get();
            assertThat(endpointGroup.endpoints()).isEqualTo(ENDPOINT_LIST);
        }
    }

    @Test
    void text() throws Exception {
        final AtomicInteger counter = new AtomicInteger();
        try (Watcher<String> watcher = dogma.client()
                                            .forRepo("directory", "my-service")
                                            .watchingFile(Query.ofText("/endpoints.txt"))
                                            .map(entry -> {
                                                counter.incrementAndGet();
                                                return entry;
                                            }).newWatcher()) {
            final CentralDogmaEndpointGroup<String> endpointGroup = CentralDogmaEndpointGroup.ofWatcher(
                    watcher, EndpointListDecoder.TEXT);
            endpointGroup.whenReady().get();
            assertThat(endpointGroup.endpoints()).isEqualTo(ENDPOINT_LIST);
            assertThat(counter.get()).isOne();

            dogma.client()
                 .forRepo("directory", "my-service")
                 .commit("commit", Change.ofTextUpsert("/endpoints.txt", "foo.bar:1234"))
                 .push(Revision.HEAD)
                 .join();

            await().untilAsserted(() -> assertThat(endpointGroup.endpoints())
                    .containsExactly(Endpoint.of("foo.bar", 1234)));
            assertThat(counter.get()).isEqualTo(2);
        }
    }

    @Test
    void recoverFromNotFound() throws Exception {
        final AtomicInteger counter = new AtomicInteger();
        try (Watcher<String> watcher = dogma.client()
                                            .forRepo("directory", "new-service")
                                            .watchingFile(Query.ofText("/endpoints.txt"))
                                            .map(entry -> {
                                                counter.incrementAndGet();
                                                return entry;
                                            }).newWatcher()) {

            final CentralDogmaEndpointGroup<String> endpointGroup = CentralDogmaEndpointGroup.ofWatcher(
                    watcher, EndpointListDecoder.TEXT);
            // Timeout because the initial watcher is still trying to fetch missing repository.
            assertThatThrownBy(() -> endpointGroup.whenReady().get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(endpointGroup.endpoints()).isEmpty();
            assertThat(counter.get()).isZero();

            dogma.client().createRepository("directory", "new-service").join();
            dogma.client()
                 .forRepo("directory", "new-service")
                 .commit("commit", Change.ofTextUpsert("/endpoints.txt", "foo.bar:1234"))
                 .push(Revision.HEAD)
                 .join();
            endpointGroup.whenReady().get(20, TimeUnit.SECONDS);

            await().untilAsserted(() -> assertThat(endpointGroup.endpoints())
                    .containsExactly(Endpoint.of("foo.bar", 1234)));
            assertThat(counter.get()).isOne();
        }
    }
}
