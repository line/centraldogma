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
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.encoding.DecodingClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.AbstractArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.internal.client.ReplicationLagTolerantCentralDogma;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService.AsyncIface;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Builds a legacy {@link CentralDogma} client based on Thrift.
 *
 * @deprecated Use {@link ArmeriaCentralDogmaBuilder}.
 */
@Deprecated
public class LegacyCentralDogmaBuilder extends AbstractArmeriaCentralDogmaBuilder<LegacyCentralDogmaBuilder> {
    private static final Logger logger = LoggerFactory.getLogger(LegacyCentralDogmaBuilder.class);

    /**
     * Returns a newly-created {@link CentralDogma} instance.
     *
     * @throws UnknownHostException if failed to resolve the host names from the DNS servers
     */
    public CentralDogma build() throws UnknownHostException {
        final EndpointGroup endpointGroup = endpointGroup();
        final String scheme = "tbinary+" + (isUseTls() ? "https" : "http");
        final ClientBuilder builder =
                newClientBuilder(scheme, endpointGroup, cb -> {
                    cb.decorator(DecodingClient.newDecorator())
                      .rpcDecorator(LegacyCentralDogmaTimeoutScheduler::new);
                }, "/cd/thrift/v1");

        final String authorization = "Bearer " + accessToken();
        builder.decorator((delegate, ctx, req) -> {
            if (!req.headers().contains(HttpHeaderNames.AUTHORIZATION)) {
                // To prevent CSRF attack, we add 'Authorization' header to every request.
                final HttpRequest newReq = req.withHeaders(req.headers()
                                                              .toBuilder()
                                                              .set(HttpHeaderNames.AUTHORIZATION, authorization)
                                                              .build());
                return delegate.execute(ctx, newReq);
            }
            return delegate.execute(ctx, req);
        });

        final ScheduledExecutorService blockingTaskExecutor = blockingTaskExecutor();

        final int maxRetriesOnReplicationLag = maxNumRetriesOnReplicationLag();

        final MeterRegistry meterRegistry = meterRegistry().orElse(clientFactory().meterRegistry());
        if (meterRegistry != clientFactory().meterRegistry()) {
            logger.warn("The specified meterRegistry differs from the meterRegistry from clientFactory.");
        }

        final CentralDogma dogma = new LegacyCentralDogma(blockingTaskExecutor,
                                                          builder.build(AsyncIface.class), meterRegistry);
        if (maxRetriesOnReplicationLag <= 0) {
            return dogma;
        } else {
            return new ReplicationLagTolerantCentralDogma(
                    blockingTaskExecutor, dogma, maxRetriesOnReplicationLag,
                    retryIntervalOnReplicationLagMillis(),
                    () -> {
                        // FIXME(trustin): Note that this will always return `null` due to a known limitation
                        //                 in Armeria: https://github.com/line/armeria/issues/760
                        final ClientRequestContext ctx = ClientRequestContext.currentOrNull();
                        return ctx != null ? ctx.remoteAddress() : null;
                    }, meterRegistry);
        }
    }
}
