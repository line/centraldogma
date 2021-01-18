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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.common.collect.Iterables;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.centraldogma.client.AbstractCentralDogmaBuilder;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants;

/**
 * Builds a {@link CentralDogma} client.
 */
public class AbstractArmeriaCentralDogmaBuilder<B extends AbstractArmeriaCentralDogmaBuilder<B>>
        extends AbstractCentralDogmaBuilder<B> {

    private static final int DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS = 15000;

    private ClientFactory clientFactory = ClientFactory.ofDefault();
    private ArmeriaClientConfigurator clientConfigurator = cb -> {};
    private Duration healthCheckInterval = Duration.ofMillis(DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS);
    private DnsAddressEndpointGroupConfigurator dnsAddressEndpointGroupConfigurator = b -> {};

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
     * Sets the {@link ArmeriaClientConfigurator} that will configure an underlying
     * <a href="https://line.github.io/armeria/">Armeria</a> client which performs the actual socket I/O.
     */
    public final B clientConfigurator(ArmeriaClientConfigurator clientConfigurator) {
        this.clientConfigurator = requireNonNull(clientConfigurator, "clientConfigurator");
        return self();
    }

    /**
     * Sets the {@link DnsAddressEndpointGroupConfigurator} that will configure the DNS lookup
     * done by the <a href="https://line.github.io/armeria/">Armeria</a> client.
     */
    public final B dnsAddressEndpointGroupConfigurator(
            DnsAddressEndpointGroupConfigurator dnsAddressEndpointGroupConfigurator) {
        this.dnsAddressEndpointGroupConfigurator = requireNonNull(
                dnsAddressEndpointGroupConfigurator, "dnsAddressEndpointGroupConfigurator");
        return self();
    }

    /**
     * Sets the interval between health check requests. The default value is
     * {@value #DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS} seconds.
     *
     * @param healthCheckInterval the interval between health check requests. {@link Duration#ZERO} disables
     *                            health check requests.
     */
    public final B healthCheckInterval(Duration healthCheckInterval) {
        requireNonNull(healthCheckInterval, "healthCheckInterval");
        checkArgument(!healthCheckInterval.isNegative(),
                      "healthCheckInterval: %s (expected: >= 0)", healthCheckInterval);
        this.healthCheckInterval = healthCheckInterval;
        return self();
    }

    /**
     * Sets the interval between health check requests in milliseconds. The default value is
     * {@value #DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS} milliseconds.
     *
     * @param healthCheckIntervalMillis the interval between health check requests in milliseconds.
     *                                  {@code 0} disables health check requests.
     */
    public final B healthCheckIntervalMillis(long healthCheckIntervalMillis) {
        checkArgument(healthCheckIntervalMillis >= 0,
                      "healthCheckIntervalMillis: %s (expected: >= 0)", healthCheckIntervalMillis);
        healthCheckInterval = Duration.ofMillis(healthCheckIntervalMillis);
        return self();
    }

    /**
     * Returns the {@link EndpointGroup} this client will connect to, derived from {@link #hosts()}.
     *
     * @throws UnknownHostException if failed to resolve the host names from the DNS servers
     */
    protected final EndpointGroup endpointGroup() throws UnknownHostException {
        final Set<InetSocketAddress> hosts = hosts();
        checkState(!hosts.isEmpty(), "no hosts were added.");

        final InetSocketAddress firstHost = Iterables.getFirst(hosts, null);
        if (hosts.size() == 1 && !firstHost.isUnresolved()) {
            return toResolvedHostEndpoint(firstHost);
        }

        final List<Endpoint> staticEndpoints = new ArrayList<>();
        final List<EndpointGroup> groups = new ArrayList<>();
        for (final InetSocketAddress addr : hosts) {
            if (addr.isUnresolved()) {
                final DnsAddressEndpointGroupBuilder dnsAddressEndpointGroup = DnsAddressEndpointGroup
                        .builder(addr.getHostString())
                        .eventLoop(clientFactory.eventLoopGroup().next());
                dnsAddressEndpointGroupConfigurator.configure(dnsAddressEndpointGroup);
                groups.add(dnsAddressEndpointGroup.port(addr.getPort()).build());
            } else {
                staticEndpoints.add(toResolvedHostEndpoint(addr));
            }
        }

        if (!staticEndpoints.isEmpty()) {
            groups.add(EndpointGroup.of(staticEndpoints));
        }

        final EndpointGroup group;
        if (groups.size() == 1) {
            group = groups.get(0);
        } else {
            group = new CompositeEndpointGroup(groups, EndpointSelectionStrategy.roundRobin());
        }

        if (group instanceof DynamicEndpointGroup) {
            // Wait until the initial endpointGroup list is ready.
            try {
                group.whenReady().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                final UnknownHostException cause = new UnknownHostException(
                        "failed to resolve any of: " + hosts);
                cause.initCause(e);
                throw cause;
            }
        }

        if (!healthCheckInterval.isZero()) {
            return HealthCheckedEndpointGroup.builder(group, HttpApiV1Constants.HEALTH_CHECK_PATH)
                                             .clientFactory(clientFactory)
                                             .protocol(isUseTls() ? SessionProtocol.HTTPS
                                                                  : SessionProtocol.HTTP)
                                             .retryInterval(healthCheckInterval)
                                             .build();
        } else {
            return group;
        }
    }

    private static Endpoint toResolvedHostEndpoint(InetSocketAddress addr) {
        return Endpoint.of(addr.getHostString(), addr.getPort())
                       .withIpAddr(addr.getAddress().getHostAddress());
    }

    /**
     * Returns a newly created {@link ClientBuilder} configured with the specified {@code customizer}
     * and then with the {@link ArmeriaClientConfigurator} specified with
     * {@link #clientConfigurator(ArmeriaClientConfigurator)}.
     */
    protected final ClientBuilder newClientBuilder(String scheme, EndpointGroup endpointGroup,
                                                   Consumer<ClientBuilder> customizer, String path) {
        final ClientBuilder builder = Clients.builder(scheme, endpointGroup, path);
        customizer.accept(builder);
        clientConfigurator.configure(builder);
        builder.factory(clientFactory());
        return builder;
    }
}
