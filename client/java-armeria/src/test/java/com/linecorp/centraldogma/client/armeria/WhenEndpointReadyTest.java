/*
 * Copyright 2022 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants;

class WhenEndpointReadyTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(HttpApiV1Constants.HEALTH_CHECK_PATH, (ctx, req) -> {
                return HttpResponse.from(healthFuture.thenApply(unused -> HttpResponse.of(HttpStatus.OK)));
            });
            sb.decorator(LoggingService.newDecorator());
        }
    };

    private static final CompletableFuture<Void> healthFuture = new CompletableFuture<>();

    @Test
    void shouldWaitHealthCheck() throws Exception {
        final CentralDogma centralDogma = new ArmeriaCentralDogmaBuilder()
                .host(server.httpSocketAddress().getHostName(), server.httpPort())
                .healthCheckIntervalMillis(500)
                .build();
        Thread.sleep(3000);
        assertThat(centralDogma.whenEndpointReady()).isNotDone();
        healthFuture.complete(null);
        await().untilAsserted(() -> {
            assertThat(centralDogma.whenEndpointReady()).isDone();
        });
    }
}
