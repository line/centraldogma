/*
 * Copyright 2019 LINE Corporation
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

import java.net.UnknownHostException;
import java.util.concurrent.ScheduledExecutorService;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.encoding.DecodingClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.internal.client.ReplicationLagTolerantCentralDogma;
import com.linecorp.centraldogma.internal.client.armeria.ArmeriaCentralDogma;

/**
 * Builds a {@link CentralDogma} client based on an <a href="https://line.github.io/armeria/">Armeria</a>
 * HTTP client.
 */
public final class ArmeriaCentralDogmaBuilder
        extends AbstractArmeriaCentralDogmaBuilder<ArmeriaCentralDogmaBuilder> {
    /**
     * Returns a newly-created {@link CentralDogma} instance.
     *
     * @throws UnknownHostException if failed to resolve the host names from the DNS servers
     */
    public CentralDogma build() throws UnknownHostException {
        final EndpointGroup endpointGroup = endpointGroup();
        final String scheme = "none+" + (isUseTls() ? "https" : "http");
        final ClientBuilder builder =
                newClientBuilder(scheme, endpointGroup, cb -> cb.decorator(DecodingClient.newDecorator()), "/");
        final int maxRetriesOnReplicationLag = maxNumRetriesOnReplicationLag();

        // TODO(ikhoon): Apply ExecutorServiceMetrics for the 'blockingTaskExecutor' once
        //               https://github.com/line/centraldogma/pull/542 is merged.
        final ScheduledExecutorService blockingTaskExecutor = blockingTaskExecutor();

        final CentralDogma dogma = new ArmeriaCentralDogma(blockingTaskExecutor,
                                                           builder.build(WebClient.class),
                                                           accessToken(),
                                                           endpointGroup::close);
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
                    });
        }
    }
}
