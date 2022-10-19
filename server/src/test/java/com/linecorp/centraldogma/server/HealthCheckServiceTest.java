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

package com.linecorp.centraldogma.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants;

class HealthCheckServiceTest {

    @TempDir
    private static Path rootDir;

    @Test
    void healthCheck() {
        try (CentralDogma dogma = new CentralDogmaBuilder(rootDir.toFile()).build()) {
            dogma.start().join();
            final ServerPort serverPort = dogma.config().ports().get(0);

            final BlockingWebClient client =
                    BlockingWebClient.of("http://127.0.0.1:" + serverPort.localAddress().getPort());
            AggregatedHttpResponse response = client.get(HttpApiV1Constants.HEALTH_CHECK_PATH);
            assertThat(response.status()).isEqualTo(HttpStatus.OK);

            final CompletableFuture<Void> closeFuture = dogma.stop();
            response = client.get(HttpApiV1Constants.HEALTH_CHECK_PATH);
            assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            closeFuture.join();
        }
    }
}
