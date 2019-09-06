/*
 * Copyright 2018 LINE Corporation
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

import java.net.UnknownHostException;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.encoding.HttpDecodingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.AbstractArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.internal.client.ReplicationLagTolerantCentralDogma;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService.AsyncIface;

import io.netty.channel.EventLoopGroup;

/**
 * Builds a legacy {@link CentralDogma} client based on Thrift.
 *
 * @deprecated Use {@link ArmeriaCentralDogmaBuilder}.
 */
@Deprecated
public class LegacyCentralDogmaBuilder extends AbstractArmeriaCentralDogmaBuilder<LegacyCentralDogmaBuilder> {
    /**
     * Returns a newly-created {@link CentralDogma} instance.
     *
     * @throws UnknownHostException if failed to resolve the host names from the DNS servers
     */
    public CentralDogma build() throws UnknownHostException {
        final Endpoint endpoint = endpoint();
        final String scheme = "tbinary+" + (isUseTls() ? "https" : "http") + "://";
        final String uri = scheme + endpoint.authority() + "/cd/thrift/v1";
        final ClientBuilder builder =
                newClientBuilder(uri, cb -> cb.decorator(HttpDecodingClient.newDecorator())
                                              .rpcDecorator(LegacyCentralDogmaTimeoutScheduler::new));

        final String authorization = "Bearer " + accessToken();
        builder.decorator((delegate, ctx, req) -> {
            if (!req.headers().contains(HttpHeaderNames.AUTHORIZATION)) {
                // To prevent CSRF attack, we add 'Authorization' header to every request.
                final HttpRequest newReq =
                        HttpRequest.of(req, req.headers()
                                               .toBuilder()
                                               .set(HttpHeaderNames.AUTHORIZATION, authorization)
                                               .build());
                return delegate.execute(ctx, newReq);
            }
            return delegate.execute(ctx, req);
        });

        final EventLoopGroup executor = clientFactory().eventLoopGroup();
        final int maxRetriesOnReplicationLag = maxNumRetriesOnReplicationLag();
        final CentralDogma dogma = new LegacyCentralDogma(executor, builder.build(AsyncIface.class));
        if (maxRetriesOnReplicationLag <= 0) {
            return dogma;
        } else {
            return new ReplicationLagTolerantCentralDogma(
                    executor, dogma, maxRetriesOnReplicationLag, retryIntervalOnReplicationLagMillis());
        }
    }
}
