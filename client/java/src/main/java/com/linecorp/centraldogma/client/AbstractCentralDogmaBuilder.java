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
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.net.InetAddresses;

import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.internal.CsrfToken;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * Builds a {@link CentralDogma} client.
 */
public abstract class AbstractCentralDogmaBuilder<B extends AbstractCentralDogmaBuilder<B>> {

    private static final String TEST_PROFILE_RESOURCE_PATH = "centraldogma-profiles-test.json";
    private static final String PROFILE_RESOURCE_PATH = "centraldogma-profiles.json";
    private static final List<String> DEFAULT_PROFILE_RESOURCE_PATHS =
            ImmutableList.of(TEST_PROFILE_RESOURCE_PATH, PROFILE_RESOURCE_PATH);

    static final int DEFAULT_PORT = 36462;

    private static final int DEFAULT_MAX_NUM_RETRIES_ON_REPLICATION_LAG = 5;
    private static final int DEFAULT_RETRY_INTERVAL_ON_REPLICATION_LAG_SECONDS = 2;

    private ImmutableSet<InetSocketAddress> hosts = ImmutableSet.of();
    private boolean useTls;
    private List<String> profileResourcePaths = DEFAULT_PROFILE_RESOURCE_PATHS;
    @Nullable
    private String selectedProfile;
    private String accessToken = CsrfToken.ANONYMOUS;
    private int maxNumRetriesOnReplicationLag = DEFAULT_MAX_NUM_RETRIES_ON_REPLICATION_LAG;
    private long retryIntervalOnReplicationLagMillis =
            TimeUnit.SECONDS.toMillis(DEFAULT_RETRY_INTERVAL_ON_REPLICATION_LAG_SECONDS);
    private MeterRegistry meterRegistry = Metrics.globalRegistry;

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
     * @deprecated Use {@link #host(String)} or {@link #profile(String...)}.
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
        checkState(selectedProfile == null, "useTls() cannot be called once a profile is selected.");
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
     * Sets the paths to look for to read the {@code .json} file that contains the client profiles.
     * The paths are tried in the order of iteration. The default value of this property is
     * <code>[ {@value #TEST_PROFILE_RESOURCE_PATH}, {@value #PROFILE_RESOURCE_PATH} ]</code>, which means
     * the builder will check if {@value #TEST_PROFILE_RESOURCE_PATH} exists first and will try
     * {@value #PROFILE_RESOURCE_PATH} only if {@value #TEST_PROFILE_RESOURCE_PATH} is missing.
     */
    public final B profileResources(String... paths) {
        return profileResources(ImmutableList.copyOf(requireNonNull(paths, "paths")));
    }

    /**
     * Sets the paths to look for to read the {@code .json} file that contains the client profiles.
     * The paths are tried in the order of iteration. The default value of this property is
     * <code>[ {@value #TEST_PROFILE_RESOURCE_PATH}, {@value #PROFILE_RESOURCE_PATH} ]</code>, which means
     * the builder will check if {@value #TEST_PROFILE_RESOURCE_PATH} exists first and will try
     * {@value #PROFILE_RESOURCE_PATH} only if {@value #TEST_PROFILE_RESOURCE_PATH} is missing.
     */
    public final B profileResources(Iterable<String> paths) {
        final List<String> newPaths = ImmutableList.copyOf(requireNonNull(paths, "paths"));
        checkArgument(!newPaths.isEmpty(), "paths is empty.");
        checkState(selectedProfile == null, "profileResources cannot be set after profile() is called.");
        profileResourcePaths = newPaths;
        return self();
    }

    /**
     * Adds the host names (or IP addresses) and the port numbers of the Central Dogma servers loaded from the
     * client profile resources. When more than one profile is matched, the last matching one will be used. See
     * <a href="https://line.github.io/centraldogma/client-java.html#using-client-profiles">Using client
     * profiles</a> for more information.
     *
     * @param profiles the list of profile names
     *
     * @throws IllegalArgumentException if failed to load any hosts from all the specified profiles
     */
    public final B profile(String... profiles) {
        requireNonNull(profiles, "profiles");
        return profile(ImmutableList.copyOf(profiles));
    }

    /**
     * Adds the host names (or IP addresses) and the port numbers of the Central Dogma servers loaded from the
     * client profile resources. When more than one profile is matched, the last matching one will be used. See
     * <a href="https://line.github.io/centraldogma/client-java.html#using-client-profiles">Using client
     * profiles</a> for more information.
     *
     * @param profiles the list of profile names
     *
     * @throws IllegalArgumentException if failed to load any hosts from all the specified profiles
     */
    public final B profile(ClassLoader classLoader, String... profiles) {
        requireNonNull(profiles, "profiles");
        return profile(classLoader, ImmutableList.copyOf(profiles));
    }

    /**
     * Adds the host names (or IP address) and the port numbers of the Central Dogma servers loaded from the
     * client profile resources. When more than one profile is matched, the last matching one will be used. See
     * <a href="https://line.github.io/centraldogma/client-java.html#using-client-profiles">Using client
     * profiles</a> for more information.
     *
     * @param profiles the list of profile names
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
     * client profile resources. When more than one profile is matched, the last matching one will be used. See
     * <a href="https://line.github.io/centraldogma/client-java.html#using-client-profiles">Using client
     * profiles</a> for more information.
     *
     * @param profiles the list of profile names
     *
     * @throws IllegalArgumentException if failed to load any hosts from all the specified profiles
     */
    public final B profile(ClassLoader classLoader, Iterable<String> profiles) {
        requireNonNull(classLoader, "classLoader");
        requireNonNull(profiles, "profiles");
        checkState(selectedProfile == null, "profile cannot be loaded more than once.");
        checkState(hosts.isEmpty(), "profile() and host() cannot be used together.");

        final Map<String, ClientProfile> availableProfiles = new HashMap<>();
        try {
            final List<URL> resourceUrls = findProfileResources(classLoader);
            checkState(!resourceUrls.isEmpty(), "failed to find any of: ", profileResourcePaths);

            for (URL resourceUrl : resourceUrls) {
                final List<ClientProfile> availableProfileList =
                        new ObjectMapper().readValue(resourceUrl, new TypeReference<List<ClientProfile>>() {});

                // Collect all profiles checking the profiles ignoring the duplicate profile names.
                availableProfileList.forEach(profile -> {
                    final String name = profile.name();
                    final ClientProfile existingProfile = availableProfiles.get(name);
                    if (existingProfile == null || existingProfile.priority() < profile.priority()) {
                        // Not a duplicate or higher priority
                        availableProfiles.put(name, profile);
                    }
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to load: " + PROFILE_RESOURCE_PATH, e);
        }

        final List<String> reversedProfiles = reverse(profiles);
        checkArgument(!reversedProfiles.isEmpty(), "profiles is empty.");
        for (String candidateName : reversedProfiles) {
            checkNotNull(candidateName, "profiles contains null: %s", profiles);

            final ClientProfile candidate = availableProfiles.get(candidateName);
            if (candidate == null) {
                continue;
            }

            final ImmutableSet.Builder<InetSocketAddress> newHostsBuilder = ImmutableSet.builder();
            candidate.hosts().stream()
                     .filter(e -> (useTls ? "https" : "http").equals(e.protocol()))
                     .forEach(e -> newHostsBuilder.add(newEndpoint(e.host(), e.port())));

            final ImmutableSet<InetSocketAddress> newHosts = newHostsBuilder.build();
            if (!newHosts.isEmpty()) {
                selectedProfile = candidateName;
                hosts = newHosts;
                return self();
            }
        }

        throw new IllegalArgumentException("no profile matches: " + profiles);
    }

    private List<URL> findProfileResources(ClassLoader classLoader) throws IOException {
        final ImmutableList.Builder<URL> urls = ImmutableList.builder();
        for (String p : profileResourcePaths) {
            for (final Enumeration<URL> e = classLoader.getResources(p); e.hasMoreElements();) {
                urls.add(e.nextElement());
            }
        }
        return urls.build();
    }

    private static List<String> reverse(Iterable<String> profiles) {
        final List<String> reversedProfiles = new ArrayList<>();
        Iterables.addAll(reversedProfiles, profiles);
        Collections.reverse(reversedProfiles);
        return reversedProfiles;
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
     * Sets the access token to use when authenticating a client.
     */
    public final B accessToken(String accessToken) {
        requireNonNull(accessToken, "accessToken");
        checkArgument(!accessToken.isEmpty(), "accessToken is empty.");
        this.accessToken = accessToken;
        return self();
    }

    /**
     * Returns the access token to use when authenticating a client.
     */
    protected String accessToken() {
        return accessToken;
    }

    /**
     * Sets the maximum number of retries to perform when replication lag is detected. For example,
     * without replication lag detection and retries, the {@code getFile()} in the following example
     * might fail with a {@link RevisionNotFoundException} when replication is enabled on the server side:
     * <pre>{@code
     * CentralDogma dogma = ...;
     * // getFile() may fail if:
     * // 1) the replica A serves getFile() while the replica B serves the normalizeRevision() and
     * // 2) the replica A did not catch up all the commits made in the replica B.
     * Revision headRevision = dogma.normalizeRevision("proj", "repo", Revision.HEAD).join();
     * Entry<String> entry = dogma.getFile("proj", "repo", headRevision, Query.ofText("/a.txt")).join();
     * }</pre>
     *
     * <p>Setting a value greater than {@code 0} to this property will make the client detect such situations
     * and retry automatically. By default, the client will retry up to
     * {@value #DEFAULT_MAX_NUM_RETRIES_ON_REPLICATION_LAG} times.</p>
     */
    public final B maxNumRetriesOnReplicationLag(int maxRetriesOnReplicationLag) {
        checkArgument(maxRetriesOnReplicationLag >= 0,
                      "maxRetriesOnReplicationLag: %s (expected: >= 0)", maxRetriesOnReplicationLag);
        this.maxNumRetriesOnReplicationLag = maxRetriesOnReplicationLag;
        return self();
    }

    /**
     * Returns the maximum number of retries to perform when replication lag is detected.
     */
    protected int maxNumRetriesOnReplicationLag() {
        return maxNumRetriesOnReplicationLag;
    }

    /**
     * Sets the interval between retries which occurred due to replication lag. By default, the interval
     * between retries is {@value #DEFAULT_RETRY_INTERVAL_ON_REPLICATION_LAG_SECONDS} seconds.
     */
    public final B retryIntervalOnReplicationLag(Duration retryIntervalOnReplicationLag) {
        requireNonNull(retryIntervalOnReplicationLag, "retryIntervalOnReplicationLag");
        checkArgument(!retryIntervalOnReplicationLag.isNegative(),
                      "retryIntervalOnReplicationLag: %s (expected: >= 0)", retryIntervalOnReplicationLag);
        return retryIntervalOnReplicationLagMillis(retryIntervalOnReplicationLag.toMillis());
    }

    /**
     * Sets the interval between retries which occurred due to replication lag in milliseconds. By default,
     * the interval between retries is {@value #DEFAULT_RETRY_INTERVAL_ON_REPLICATION_LAG_SECONDS} seconds.
     */
    public final B retryIntervalOnReplicationLagMillis(long retryIntervalOnReplicationLagMillis) {
        checkArgument(retryIntervalOnReplicationLagMillis >= 0,
                      "retryIntervalOnReplicationLagMillis: %s (expected: >= 0)",
                      retryIntervalOnReplicationLagMillis);
        this.retryIntervalOnReplicationLagMillis = retryIntervalOnReplicationLagMillis;
        return self();
    }

    /**
     * Returns the interval between retries which occurred due to replication lag in milliseconds.
     */
    protected long retryIntervalOnReplicationLagMillis() {
        return retryIntervalOnReplicationLagMillis;
    }

    /**
     * Sets the {@link MeterRegistry} used to collect metrics.
     */
    public final B meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        return self();
    }

    protected MeterRegistry meterRegistry() {
        return meterRegistry;
    }
}
