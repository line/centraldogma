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
package com.linecorp.centraldogma.client.armeria;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.healthcheck.HttpHealthCheckedEndpointGroupBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.centraldogma.client.AbstractCentralDogmaBuilder;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants;

/**
 * Builds a {@link CentralDogma} client.
 */
public class AbstractArmeriaCentralDogmaBuilder<B extends AbstractArmeriaCentralDogmaBuilder<B>>
        extends AbstractCentralDogmaBuilder<B> {

    @VisibleForTesting
    static final AtomicLong nextAnonymousGroupId = new AtomicLong();

    private ClientFactory clientFactory = ClientFactory.DEFAULT;
    private ArmeriaClientConfigurator clientConfigurator = cb -> {};
    private boolean healthCheckEnabled = true;

    /**
     * Returns the {@link ClientFactory} that will create an underlying
     * <a href="https://line.github.io/armeria/">Armeria</a> client which performs the actual socket I/O.
     */
    protected final ClientFactory clientFactory() {
        return clientFactory;
    }

    /**
     * Sets the {@link ClientFactory} that will create an underlying
     * <a href="https://line.github.io/armeria/">Armeria</a> client which performs the actual socket I/O.
     */
    public final B clientFactory(ClientFactory clientFactory) {
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        return self();
    }

    /**
     * Returns the {@link ArmeriaClientConfigurator} that will configure an underlying
     * <a href="https://line.github.io/armeria/">Armeria</a> client which performs the actual socket I/O.
     */
    protected final ArmeriaClientConfigurator clientConfigurator() {
        return clientConfigurator;
    }

    /**
     * Sets the {@link ArmeriaClientConfigurator} that will configure an underlying
     * <a href="https://line.github.io/armeria/">Armeria</a> client which performs the actual socket I/O.
     */
    public final B clientConfigurator(ArmeriaClientConfigurator clientConfigurator) {
        this.clientConfigurator = requireNonNull(clientConfigurator, "clientConfigurator");
        return self();
    }

    @VisibleForTesting
    void disableHealthCheck() {
        healthCheckEnabled = false;
    }

    /**
     * Returns the {@link Endpoint} this client will connect to, derived from {@link #hosts()}.
     *
     * @throws UnknownHostException if failed to resolve the host names from the DNS servers
     */
    protected final Endpoint endpoint() throws UnknownHostException {
        final Set<InetSocketAddress> hosts = hosts();
        checkState(!hosts.isEmpty(), "no hosts were added.");

        final InetSocketAddress firstHost = Iterables.getFirst(hosts, null);
        final Endpoint endpoint;
        if (hosts.size() == 1 && !firstHost.isUnresolved()) {
            endpoint = toResolvedHostEndpoint(firstHost);
        } else {
            final String groupName;
            if (selectedProfile() != null) {
                // Generate a group name from profile name.
                groupName = "centraldogma-profile-" + selectedProfile();
            } else {
                // Generate an anonymous group name with an arbitrary integer.
                groupName = "centraldogma-anonymous-" + nextAnonymousGroupId.getAndIncrement();
            }

            final List<Endpoint> staticEndpoints = new ArrayList<>();
            final List<EndpointGroup> groups = new ArrayList<>();
            for (final InetSocketAddress addr : hosts) {
                if (addr.isUnresolved()) {
                    groups.add(new DnsAddressEndpointGroupBuilder(addr.getHostString())
                            .eventLoop(clientFactory.eventLoopGroup().next())
                            .port(addr.getPort())
                            .build());
                } else {
                    staticEndpoints.add(toResolvedHostEndpoint(addr));
                }
            }

            if (!staticEndpoints.isEmpty()) {
                groups.add(new StaticEndpointGroup(staticEndpoints));
            }

            EndpointGroup group;
            if (groups.size() == 1) {
                group = groups.get(0);
            } else {
                group = new CompositeEndpointGroup(groups);
            }

            if (group instanceof DynamicEndpointGroup) {
                // Wait until the initial endpoint list is ready.
                try {
                    ((DynamicEndpointGroup) group).awaitInitialEndpoints(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    final UnknownHostException cause = new UnknownHostException(
                            "failed to resolve any of: " + hosts);
                    cause.initCause(e);
                    throw cause;
                }
            }

            if (healthCheckEnabled) {
                group = new HttpHealthCheckedEndpointGroupBuilder(group, HttpApiV1Constants.HEALTH_CHECK_PATH)
                        .clientFactory(clientFactory)
                        .protocol(isUseTls() ? SessionProtocol.HTTPS : SessionProtocol.HTTP)
                        .build();
            }

            EndpointGroupRegistry.register(groupName, group, EndpointSelectionStrategy.ROUND_ROBIN);
            endpoint = Endpoint.ofGroup(groupName);
        }
        return endpoint;
    }

    private static Endpoint toResolvedHostEndpoint(InetSocketAddress addr) {
        return Endpoint.of(addr.getHostString(), addr.getPort())
                       .withIpAddr(addr.getAddress().getHostAddress());
    }

    @Override
    protected final void printWarning(String message) {
        LoggerFactory.getLogger(getClass()).warn(message);
    }
}
