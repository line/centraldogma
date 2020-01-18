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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.DefaultRpcRequest;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.centraldogma.internal.api.v1.WatchTimeout;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService;

@ExtendWith(MockitoExtension.class)
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

    private void check(String method, long timeoutMills, long defaultTimeoutMills, long expectedTimeoutMills)
            throws Exception {
        final RpcRequest req = newRequest(method, ImmutableList.of("a", "b", "c", timeoutMills));
        final ClientRequestContext ctx = newClientContext(req);
        ctx.setResponseTimeoutMillis(defaultTimeoutMills);
        decorator.execute(ctx, req);
        assertThat(ctx.responseTimeoutMillis()).isEqualTo(expectedTimeoutMills);
        verify(client).execute(ctx, req);
    }

    private static RpcRequest newRequest(String method, List<Object> args) {
        return new DefaultRpcRequest(CentralDogmaService.AsyncIface.class, method, args);
    }

    private static ClientRequestContext newClientContext(RpcRequest req) {
        final ClientRequestContext ctx = ClientRequestContext.of(
                HttpRequest.of(HttpMethod.POST, "/cd/thrift/v1"));
        return spy(ctx);
    }
}
