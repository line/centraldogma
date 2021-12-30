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

import static com.linecorp.centraldogma.common.Revision.HEAD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

/**
 * Makes sure the clients stop watching when their {@link ClientFactory} is closing.
 */
class PrematureClientFactoryCloseTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
            client.forRepo("foo", "bar")
                  .commit("Add baz.txt", Change.ofTextUpsert("/baz.txt", ""))
                  .push(HEAD).join();
        }
    };

    @Test
    void watchRepository() throws Exception {
        test(client -> client.forRepo("foo", "bar")
                             .watch(PathPattern.all())
                             .timeoutMillis(Long.MAX_VALUE)
                             .errorOnEntryNotFound(false)
                             .start());
    }

    @Test
    void watchFile() throws Exception {
        test(client -> client.forRepo("foo", "bar")
                             .watch(Query.ofText("/baz.txt"))
                             .timeoutMillis(Long.MAX_VALUE)
                             .errorOnEntryNotFound(false)
                             .start());
    }

    private static void test(Function<CentralDogma, CompletableFuture<?>> watchAction) throws Exception {
        final ClientFactory clientFactory = ClientFactory.builder().build();
        final CentralDogma client = new ArmeriaCentralDogmaBuilder()
                .clientFactory(clientFactory)
                .host("127.0.0.1", dogma.serverAddress().getPort())
                .build();

        final CompletableFuture<?> future = watchAction.apply(client);

        // Wait until the server receives the watch request.
        await().untilAsserted(() -> {
            assertThat(MoreMeters.measureAll(dogma.dogma().meterRegistry().get()))
                    .containsEntry("watches.active#value", 1.0);
        });

        // Close the `ClientFactory` to trigger disconnection.
        clientFactory.close();

        // The watch request should finish without an exception.
        assertThat(future.join()).isNull();

        // Wait until the server detects the watch cancellation.
        await().untilAsserted(() -> {
            assertThat(MoreMeters.measureAll(dogma.dogma().meterRegistry().get()))
                    .containsEntry("watches.active#value", 0.0);
        });
    }
}
