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
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;

/**
 * Builds a {@link CentralDogma} client.
 */
public abstract class AbstractCentralDogmaBuilder<B extends AbstractCentralDogmaBuilder<B>> {

    private static final int DEFAULT_PORT = 36462;

    private ImmutableSet<InetSocketAddress> hosts = ImmutableSet.of();
    private boolean useTls;
    private String selectedProfile;

    /**
     * Returns {@code this}.
     */
    @SuppressWarnings("unchecked")
    protected final B self() {
        return (B) this;
    }

    /**
     * Adds the {@link URI} of the Central Dogma server.
     *
     * @deprecated Use {@link #host(String)} or {@link #profile(String...)} instead.
     *
     * @param uri the URI of the Central Dogma server. e.g.
     *            {@code tbinary+http://example.com:36462/cd/thrift/v1}
     */
    @Deprecated
    public final B uri(String uri) {
        final URI parsed = URI.create(requireNonNull(uri, "uri"));
        final String host = parsed.getHost();
        final int port = parsed.getPort();
        checkArgument(host != null, "uri: %s (must contain a host part)", uri);
        if (port < 0) {
            host(host);
        } else {
            host(host, port);
        }
        return self();
    }

    /**
     * Adds the host name or IP address of the Central Dogma Server and uses the default port number of
     * {@value #DEFAULT_PORT}.
     *
     * @param host the host name or IP address of the Central Dogma server
     */
    public final B host(String host) {
        return host(host, DEFAULT_PORT);
    }

    /**
     * Adds the host name (or IP address) and the port number of the Central Dogma server.
     *
     * @param host the host name or IP address of the Central Dogma server
     * @param port the port number of the Central Dogma server
     */
    public final B host(String host, int port) {
        requireNonNull(host, "host");
        checkArgument(!host.startsWith("group:"), "host: %s (must not start with 'group:')", host);
        checkArgument(port >= 1 && port < 65536, "port: %s (expected: 1 .. 65535)", port);

        final InetSocketAddress addr = newEndpoint(host, port);
        checkState(selectedProfile == null, "profile() and host() cannot be used together.");
        hosts = ImmutableSet.<InetSocketAddress>builder().addAll(hosts).add(addr).build();
        return self();
    }

    /**
     * Sets the client to use TLS.
     */
    public final B useTls() {
        return useTls(true);
    }

    /**
     * Sets whether the client uses TLS or not.
     */
    public final B useTls(boolean useTls) {
        this.useTls = useTls;
        return self();
    }

    /**
     * Returns whether the client uses TLS or not.
     *
     * @see #useTls(boolean)
     */
    protected final boolean isUseTls() {
        return useTls;
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
    public final B profile(String... profiles) {
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
    public final B profile(ClassLoader classLoader, String... profiles) {
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
    public final B profile(Iterable<String> profiles) {
        final ClassLoader ccl = Thread.currentThread().getContextClassLoader();
        if (ccl != null) {
            profile(ccl, profiles);
        } else {
            profile(getClass().getClassLoader(), profiles);
        }
        return self();
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
    public final B profile(ClassLoader classLoader, Iterable<String> profiles) {
        requireNonNull(classLoader, "classLoader");
        requireNonNull(profiles, "profiles");
        checkState(selectedProfile == null, "profile cannot be loaded more than once.");
        checkState(hosts.isEmpty(), "profile() and host() cannot be used together.");

        String selectedProfile = null;
        ImmutableSet<InetSocketAddress> hosts = null;
        boolean profilesIsEmpty = true;
        for (String p : profiles) {
            checkNotNull(p, "profiles contains null: %s", profiles);
            profilesIsEmpty = false;

            final String path = "centraldogma-profile-" + p + ".properties";
            final InputStream in = classLoader.getResourceAsStream(path);
            if (in == null) {
                continue;
            }

            try {
                final ImmutableSet.Builder<InetSocketAddress> newHostsBuilder = ImmutableSet.builder();
                final Properties props = new Properties();
                props.load(in);
                for (Entry<Object, Object> e : props.entrySet()) {
                    final String key = (String) e.getKey();
                    final String value = (String) e.getValue();

                    if (key.startsWith("centraldogma.hosts.")) {
                        final HostAndPort hostAndPort = HostAndPort.fromString(value);
                        final InetSocketAddress addr = newEndpoint(
                                hostAndPort.getHost(), hostAndPort.getPortOrDefault(DEFAULT_PORT));
                        newHostsBuilder.add(addr);
                    }
                }

                final ImmutableSet<InetSocketAddress> newHosts = newHostsBuilder.build();
                if (newHosts.isEmpty()) {
                    printWarning(String.format("Ignoring %s: contains no hosts", path));
                } else {
                    selectedProfile = p;
                    hosts = newHosts;
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("failed to load: " + path, e);
            }
        }

        checkArgument(!profilesIsEmpty, "profiles is empty.");
        checkArgument(selectedProfile != null, "no profile matches: %s", profiles);

        this.selectedProfile = selectedProfile;
        this.hosts = hosts;
        return self();
    }

    private static InetSocketAddress newEndpoint(String host, int port) {
        final InetSocketAddress endpoint;
        if (InetAddresses.isInetAddress(host)) {
            endpoint = new InetSocketAddress(InetAddresses.forString(host), port);
        } else {
            endpoint = InetSocketAddress.createUnresolved(host, port);
        }
        return endpoint;
    }

    /**
     * Returns the name of the selected profile.
     *
     * @return the profile name, or {@code null} if no profile was specified or matched
     */
    @Nullable
    protected final String selectedProfile() {
        return selectedProfile;
    }

    /**
     * Returns the hosts added via {@link #host(String, int)} or {@link #profile(String...)}.
     */
    protected final Set<InetSocketAddress> hosts() {
        return hosts;
    }

    /**
     * Prints the warning message.
     */
    protected void printWarning(String message) {
        System.err.println(message);
    }
}
