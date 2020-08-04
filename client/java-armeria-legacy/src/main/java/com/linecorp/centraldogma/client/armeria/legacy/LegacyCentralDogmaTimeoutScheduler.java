/*
 * Copyright 2017 LINE Corporation
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

import java.util.List;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.SimpleDecoratingRpcClient;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.centraldogma.internal.api.v1.WatchTimeout;

/**
 * Decorates a {@link Client} to enlarge responseTimeout when requesting watchFile or watchRepository.
 */
class LegacyCentralDogmaTimeoutScheduler extends SimpleDecoratingRpcClient {

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    LegacyCentralDogmaTimeoutScheduler(RpcClient delegate) {
        super(delegate);
    }

    @Override
    public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
        final long responseTimeoutMillis = ctx.responseTimeoutMillis();
        if (responseTimeoutMillis > 0) {
            final String method = req.method();
            if ("watchFile".equals(method) || "watchRepository".equals(method)) {
                final List<Object> params = req.params();
                final long timeout = (Long) params.get(params.size() - 1);
                if (timeout > 0) {
                    ctx.setResponseTimeoutMillis(TimeoutMode.EXTEND,
                                                 WatchTimeout.availableTimeout(timeout, responseTimeoutMillis));
                }
            }
        }
        return unwrap().execute(ctx, req);
    }
}
