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

package com.linecorp.centraldogma.client.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.inject.Inject;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.google.common.util.concurrent.Uninterruptibles;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.spring.CentralDogmaInitializationTimeoutTest.TestConfiguration;
import com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "initTimeout" })
class CentralDogmaInitializationTimeoutTest {

    // TODO(ikhoon): Randomize the port number to avoid flakiness.
    private static final Lock lock = new ReentrantLock();
    private static final int TEST_SERVER_PORT = 56463;

    private static Server server;

    @SpringBootApplication
    static class TestConfiguration {}

    @Inject
    private CentralDogma client;

    @BeforeAll
    static void beforeAll() {
        lock.lock();
        final int maxAttempts = 8;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                final Server server =
                        Server.builder()
                              .http(TEST_SERVER_PORT)
                              .service(HttpApiV1Constants.HEALTH_CHECK_PATH,
                                       (ctx, req) -> HttpResponse.delayed(HttpResponse.of("OK"),
                                                                          Duration.ofSeconds(5)))
                              .build();
                server.start().join();
                CentralDogmaInitializationTimeoutTest.server = server;
            } catch (Exception ex) {
                if (i < maxAttempts) {
                    // Ignore the exception silently apart from the last attempts.
                    final long sleep = Backoff.ofDefault().nextDelayMillis(maxAttempts);
                    Uninterruptibles.sleepUninterruptibly(sleep, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    @AfterAll
    static void afterAll() {
        try {
            // Immediately close the server to release the port quickly.
            server.close();
        } finally {
            lock.unlock();
        }
    }

    @Test
    void initializedEndpoints() {
        assertThat(client.whenEndpointReady()).isCompleted();
    }
}
