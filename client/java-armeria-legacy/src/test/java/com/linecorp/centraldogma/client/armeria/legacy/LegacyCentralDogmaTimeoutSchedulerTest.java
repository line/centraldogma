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

package com.linecorp.centraldogma.client.armeria.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.internal.api.v1.WatchTimeout;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService;

class LegacyCentralDogmaTimeoutSchedulerTest {

    @Mock
    private RpcClient client;

    private LegacyCentralDogmaTimeoutScheduler decorator;

    @BeforeEach
    void setUp() {
        decorator = new LegacyCentralDogmaTimeoutScheduler(client);
    }

    @Test
    void execute() throws Exception {
        check("listProjects", 1000L, 1L, 1L);
    }

    @Test
    void execute_watchFile() throws Exception {
        check("watchFile", 1000L, 1L, 1001L);
    }

    @Test
    void execute_watchRepository() throws Exception {
        check("watchRepository", 1000L, 1L, 1001L);
    }

    @Test
    void execute_watch_timeoutOverflow() throws Exception {
        check("watchRepository", Long.MAX_VALUE - 10, 100L, WatchTimeout.MAX_MILLIS);
    }

    @Test
    void execute_noTimeout() throws Exception {
        check("watchFile", 1000L, 0, 0);
    }

    private void check(String method, long timeoutMillis, long defaultTimeoutMills, long expectedTimeoutMills)
            throws Exception {
        final RpcRequest req = newRequest(method, ImmutableList.of("a", "b", "c", timeoutMillis));
        final ClientRequestContext ctx = newClientContext(req);
        final AtomicBoolean completed = new AtomicBoolean();
        ctx.eventLoop().execute(() -> {
            try {
                ctx.clearResponseTimeout();
                if (defaultTimeoutMills > 0) {
                    ctx.setResponseTimeoutMillis(defaultTimeoutMills);
                }
                // A response timeout is calculated from the start of the request.
                final long responseTimeoutMillis = ctx.responseTimeoutMillis();
                final long adjustment = expectedTimeoutMills - defaultTimeoutMills;

                decorator.execute(ctx, req);
                assertThat(ctx.responseTimeoutMillis())
                        .isEqualTo(Math.min(responseTimeoutMillis + adjustment, WatchTimeout.MAX_MILLIS));

                verify(client).execute(ctx, req);
                completed.set(true);
            } catch (Exception e) {
                Exceptions.throwUnsafely(e);
            }
        });
        await().untilTrue(completed);
    }

    private static RpcRequest newRequest(String method, List<Object> args) {
        return RpcRequest.of(CentralDogmaService.AsyncIface.class, method, args);
    }

    private static ClientRequestContext newClientContext(RpcRequest req) {
        final ClientRequestContext ctx = ClientRequestContext.of(
                HttpRequest.of(HttpMethod.POST, "/cd/thrift/v1"));
        return ctx;
    }
}
