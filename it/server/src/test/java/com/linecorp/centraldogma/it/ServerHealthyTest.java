/*
 * Copyright 2025 LINE Corporation
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

import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.plugin.AllReplicasPlugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

public final class ServerHealthyTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @Test
    void serverIsUnhealthyWhenPluginStarts() {
        assertThat(dogma.httpClient().get(HttpApiV1Constants.HEALTH_CHECK_PATH).aggregate().join().status())
                .isSameAs(HttpStatus.OK);
    }

    public static final class HealthCheckingPlugin extends AllReplicasPlugin {

        @Override
        public CompletionStage<Void> start(PluginContext context) {
            final CentralDogma centralDogma = dogma.dogma();
            // Can't call dogma.httpClient() here because the CentralDogmaExtension is not yet initialized.
            final WebClient webClient =
                    WebClient.of("http://127.0.0.1:" + centralDogma.activePort().localAddress().getPort());
            assertThat(webClient.get(HttpApiV1Constants.HEALTH_CHECK_PATH)
                                .aggregate().join().status()).isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
            return UnmodifiableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> stop(PluginContext context) {
            return UnmodifiableFuture.completedFuture(null);
        }

        @Override
        public Class<?> configType() {
            return getClass();
        }
    }
}
