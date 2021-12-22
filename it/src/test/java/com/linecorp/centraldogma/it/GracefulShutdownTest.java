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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.GracefulShutdownTimeout;

class GracefulShutdownTest {

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            super.configure(builder);
            builder.gracefulShutdownTimeout(new GracefulShutdownTimeout(1000, 2000));
        }
    };

    @BeforeEach
    void startServer() {
        dogma.start();
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchRepositoryGracefulShutdown(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        testGracefulShutdown(client.watchRepository(
                dogma.project(), dogma.repo1(), Revision.HEAD, PathPattern.all(), 60000, false));
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void watchFileGracefulShutdown(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        testGracefulShutdown(client.watchFile(
                dogma.project(), dogma.repo1(), Revision.HEAD, Query.ofJson("/test.json"), 60000, false));
    }

    private static void testGracefulShutdown(CompletableFuture<?> future) throws Exception {
        // Wait a little bit so that we do not start to stop the server before the watch operation is accepted.
        Thread.sleep(500);
        dogma.stopAsync();

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ShuttingDownException.class);
    }
}
