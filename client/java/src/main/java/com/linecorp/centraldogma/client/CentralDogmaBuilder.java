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
package com.linecorp.centraldogma.client;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.centraldogma.internal.CsrfToken;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService;

import io.netty.util.NetUtil;

/**
 * Builds a {@link CentralDogma} client.
 */
public class CentralDogmaBuilder {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaBuilder.class);

    private static final int DEFAULT_PORT = 36462;

    @VisibleForTesting
    static final AtomicLong nextAnonymousGroupId = new AtomicLong();

    private ClientFactory clientFactory = ClientFactory.DEFAULT;
    private List<Endpoint> endpoints = new ArrayList<>();
    private boolean useTls;
    private String selectedProfile;
    private ArmeriaClientConfigurator clientConfigurator = cb -> {
    };

    /**
     * Adds the {@link URI} of the Central Dogma server.
     *
     * @deprecated Use {@link #host(String)} or {@link #profile(String...)} instead.
     *
     * @param uri the URI of the Central Dogma server. e.g.
     *            {@code tbinary+http://example.com:36462/cd/thrift/v1}
     */
    @Deprecated
    public CentralDogmaBuilder uri(String uri) {
        final URI parsed = URI.create(requireNonNull(uri, "uri"));
        final String host = parsed.getHost();
        final int port = parsed.getPort();
        checkArgument(host != null, "uri: %s (must contain a host part)", uri);
        if (port < 0) {
            host(host);
        } else {
            host(host, port);
        }
        return this;
    }

    /**
     * Adds the host name or IP address of the Central Dogma Server and uses the default port number of
     * {@value #DEFAULT_PORT}.
     *
     * @param host the host name or IP address of the Central Dogma server
     */
    public CentralDogmaBuilder host(String host) {
        return host(host, DEFAULT_PORT);
    }

    /**
     * Adds the host name (or IP address) and the port number of the Central Dogma server.
     *
     * @param host the host name or IP address of the Central Dogma server
     * @param port the port number of the Central Dogma server
     */
    public CentralDogmaBuilder host(String host, int port) {
        requireNonNull(host, "host");
        checkArgument(!host.startsWith("group:"), "host: %s (must not start with 'group:')", host);
        checkArgument(port >= 1 && port < 65536, "port: %s (expected: 1 .. 65535)", port);

        // TODO(trustin): Add a utility or a shortcut method in Armeria.
        if (NetUtil.isValidIpV6Address(host)) {
            host = '[' + host + ']';
        }

        checkState(selectedProfile == null, "profile() and host() cannot be used together.");
        endpoints.add(Endpoint.parse(host + ':' + port));
        return this;
    }

    /**
     * Sets the client to use TLS.
     */
    public CentralDogmaBuilder useTls() {
        return useTls(true);
    }

    /**
     * Sets whether the client uses TLS or not.
     */
    public CentralDogmaBuilder useTls(boolean useTls) {
        this.useTls = useTls;
        return this;
    }

    /**
     * Adds the host names (or IP addresses) and the port numbers of the Central Dogma servers loaded from the
     * {@code /centraldogma-profile-<profile_name>.properties} resource file. The {@code .properties} file
     * must contain at least one property whose name starts with {@code "centraldogma.hosts."}:
     *
     * <pre>{@code
     * centraldogma.hosts.0=replica1.example.com:36462
     * centraldogma.hosts.1=replica2.example.com:36462
     * centraldogma.hosts.2=replica3.example.com:36462
     * }</pre>
     *
     * @param profiles the list of profile names, in the order of preference
     *
     * @throws IllegalArgumentException if failed to load any hosts from all the specified profiles
     */
    public CentralDogmaBuilder profile(String... profiles) {
        requireNonNull(profiles, "profiles");
        return profile(ImmutableList.copyOf(profiles));
    }

    /**
     * Adds the host names (or IP addresses) and the port numbers of the Central Dogma servers loaded from the
     * {@code /centraldogma-profile-<profile_name>.properties} resource file. The {@code .properties} file
     * must contain at least one property whose name starts with {@code "centraldogma.hosts."}:
     *
     * <pre>{@code
     * centraldogma.hosts.0=replica1.example.com:36462
     * centraldogma.hosts.1=replica2.example.com:36462
     * centraldogma.hosts.2=replica3.example.com:36462
     * }</pre>
     *
     * @param profiles the list of profile names, in the order of preference
     *
     * @throws IllegalArgumentException if failed to load any hosts from all the specified profiles
     */
    public CentralDogmaBuilder profile(ClassLoader classLoader, String... profiles) {
        requireNonNull(profiles, "profiles");
        return profile(classLoader, ImmutableList.copyOf(profiles));
    }

    /**
     * Adds the host names (or IP address) and the port numbers of the Central Dogma servers loaded from the
     * {@code /centraldogma-profile-<profile_name>.properties} resource file. The {@code .properties} file
     * must contain at least one property whose name starts with {@code "centraldogma.hosts."}:
     *
     * <pre>{@code
     * centraldogma.hosts.0=replica1.example.com:36462
     * centraldogma.hosts.1=replica2.example.com:36462
     * centraldogma.hosts.2=replica3.example.com:36462
     * }</pre>
     *
     * @param profiles the list of profile names, in the order of preference
     *
     * @throws IllegalArgumentException if failed to load any hosts from all the specified profiles
     */
    public CentralDogmaBuilder profile(Iterable<String> profiles) {
        final ClassLoader ccl = Thread.currentThread().getContextClassLoader();
        if (ccl != null) {
            profile(ccl, profiles);
        } else {
            profile(CentralDogmaBuilder.class.getClassLoader(), profiles);
        }
        return this;
    }

    /**
     * Adds the host names (or IP address) and the port numbers of the Central Dogma servers loaded from the
     * {@code /centraldogma-profile-<profile_name>.properties} resource file. The {@code .properties} file
     * must contain at least one property whose name starts with {@code "centraldogma.hosts."}:
     *
     * <pre>{@code
     * centraldogma.hosts.0=replica1.example.com:36462
     * centraldogma.hosts.1=replica2.example.com:36462
     * centraldogma.hosts.2=replica3.example.com:36462
     * }</pre>
     *
     * @param profiles the list of profile names, in the order of preference
     *
     * @throws IllegalArgumentException if failed to load any hosts from all the specified profiles
     */
    public CentralDogmaBuilder profile(ClassLoader classLoader, Iterable<String> profiles) {
        requireNonNull(classLoader, "classLoader");
        requireNonNull(profiles, "profiles");
        checkState(selectedProfile == null, "profile cannot be loaded more than once.");
        checkState(endpoints.isEmpty(), "profile() and host() cannot be used together.");

        String selectedProfile = null;
        List<Endpoint> endpoints = null;
        boolean profilesIsEmpty = true;
        loop: for (String p : profiles) {
            checkNotNull(p, "profiles contains null: %s", profiles);
            profilesIsEmpty = false;

            final String path = "centraldogma-profile-" + p + ".properties";
            final InputStream in = classLoader.getResourceAsStream(path);
            if (in == null) {
                continue;
            }

            try {
                final List<Endpoint> newEndpoints = new ArrayList<>();
                final Properties props = new Properties();
                props.load(in);
                for (Entry<Object, Object> e : props.entrySet()) {
                    final String key = (String) e.getKey();
                    final String value = (String) e.getValue();

                    if (key.startsWith("centraldogma.hosts.")) {
                        final Endpoint endpoint = Endpoint.parse(value);
                        if (endpoint.isGroup()) {
                            logger.warn("Ignoring {}: contains an endpoint group which is not allowed (%s)",
                                        path, value);
                            continue loop;
                        }
                        newEndpoints.add(endpoint.withDefaultPort(DEFAULT_PORT));
                    }
                }

                if (newEndpoints.isEmpty()) {
                    logger.warn("Ignoring {}: contains no hosts", path);
                } else {
                    selectedProfile = p;
                    endpoints = newEndpoints;
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("failed to load: " + path, e);
            }
        }

        checkArgument(!profilesIsEmpty, "profiles is empty.");
        checkArgument(selectedProfile != null, "no profile matches: %s", profiles);

        this.selectedProfile = selectedProfile;
        this.endpoints = endpoints;
        return this;
    }

    /**
     * Sets the {@link ClientFactory} that will create an underlying
     * <a href="https://line.github.io/armeria/">Armeria</a> client which performs the actual socket I/O.
     */
    public CentralDogmaBuilder clientFactory(ClientFactory clientFactory) {
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        return this;
    }

    /**
     * Sets the {@link ArmeriaClientConfigurator} that will configure an underlying
     * <a href="https://line.github.io/armeria/">Armeria</a> client which performs the actual socket I/O.
     */
    public CentralDogmaBuilder clientConfigurator(ArmeriaClientConfigurator clientConfigurator) {
        this.clientConfigurator = requireNonNull(clientConfigurator, "clientConfigurator");
        return this;
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance.
     */
    public CentralDogma build() {
        checkState(!endpoints.isEmpty(), "no endpoints were added.");

        final Endpoint endpoint;
        if (endpoints.size() == 1) {
            endpoint = endpoints.get(0);
        } else {
            final String groupName;
            if (selectedProfile != null) {
                // Generate a group name from profile name.
                groupName = "centraldogma-profile-" + selectedProfile;
            } else {
                // Generate an anonymous group name with an arbitrary integer.
                groupName = "centraldogma-anonymous-" + nextAnonymousGroupId.getAndIncrement();
            }

            EndpointGroupRegistry.register(groupName, new StaticEndpointGroup(endpoints),
                                           EndpointSelectionStrategy.ROUND_ROBIN);
            endpoint = Endpoint.ofGroup(groupName);
        }

        final String scheme = "tbinary+" + (useTls ? "https" : "http") + "://";
        final String uri = scheme + endpoint.authority() + "/cd/thrift/v1";
        final ClientBuilder builder = new ClientBuilder(uri)
                .factory(clientFactory)
                .decorator(RpcRequest.class, RpcResponse.class,
                           CentralDogmaClientTimeoutScheduler::new);
        clientConfigurator.configure(builder);

        builder.decorator(HttpRequest.class, HttpResponse.class,
                          (delegate, ctx, req) -> {
                              if (!req.headers().contains(HttpHeaderNames.AUTHORIZATION)) {
                                  // To prevent CSRF attack, we add 'Authorization' header to every request.
                                  req.headers().set(HttpHeaderNames.AUTHORIZATION,
                                                    "bearer " + CsrfToken.ANONYMOUS);
                              }
                              return delegate.execute(ctx, req);
                          });
        return new DefaultCentralDogma(clientFactory, builder.build(CentralDogmaService.AsyncIface.class));
    }
}
