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

package com.linecorp.centraldogma.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.server.auth.AuthConfig.DEFAULT_SESSION_CACHE_SPEC;
import static com.linecorp.centraldogma.server.auth.AuthConfig.DEFAULT_SESSION_TIMEOUT_MILLIS;
import static com.linecorp.centraldogma.server.auth.AuthConfig.DEFAULT_SESSION_VALIDATION_SCHEDULE;
import static com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache.validateCacheSpec;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.auth.AuthConfig;
import com.linecorp.centraldogma.server.auth.AuthProvider;
import com.linecorp.centraldogma.server.auth.AuthProviderFactory;
import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginConfig;
import com.linecorp.centraldogma.server.storage.repository.Repository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Builds a {@link CentralDogma} server.
 *
 * <pre>{@code
 * CentralDogmaBuilder builder = new CentralDogmaBuilder(new File("/tmp/dogma"));
 * builder.numRepositoryWorkers(32);
 * builder...;
 * CentralDogma dogma = builder.build();
 * dogma.start();
 * }</pre>
 */
public final class CentralDogmaBuilder {
    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaBuilder.class);

    // You get 36462 if you map 'dogma' to T9 phone dialer layout.
    private static final ServerPort DEFAULT_PORT = new ServerPort(36462, SessionProtocol.HTTP);

    static final int DEFAULT_NUM_REPOSITORY_WORKERS = 16;
    static final long DEFAULT_MAX_REMOVED_REPOSITORY_AGE_MILLIS = 604_800_000;  // 7 days

    static final String DEFAULT_REPOSITORY_CACHE_SPEC =
            "maximumWeight=134217728," + // Cache up to apx. 128-megachars.
            "expireAfterAccess=5m";      // Expire on 5 minutes of inactivity.

    // Armeria properties
    // Note that we use nullable types here for optional properties.
    // When a property is null, the default value will be used implicitly.
    private final List<ServerPort> ports = new ArrayList<>(2);
    @Nullable
    private TlsConfig tls;
    private final List<String> trustedProxyAddresses = new ArrayList<>();
    private final List<String> clientAddressSources = new ArrayList<>();
    @Nullable
    private Integer numWorkers;
    @Nullable
    private Integer maxNumConnections;
    @Nullable
    private Long requestTimeoutMillis;
    @Nullable
    private Long idleTimeoutMillis;
    @Nullable
    private Integer maxFrameLength;

    // Central Dogma properties
    private final File dataDir;
    private int numRepositoryWorkers = DEFAULT_NUM_REPOSITORY_WORKERS;
    private long maxRemovedRepositoryAgeMillis = DEFAULT_MAX_REMOVED_REPOSITORY_AGE_MILLIS;

    @Nullable
    private String repositoryCacheSpec = DEFAULT_REPOSITORY_CACHE_SPEC;
    private boolean webAppEnabled = true;
    @Nullable
    private String webAppTitle;

    @Nullable
    private GracefulShutdownTimeout gracefulShutdownTimeout;
    private ReplicationConfig replicationConfig = ReplicationConfig.NONE;
    @Nullable
    private String accessLogFormat;

    // AuthConfig properties
    @Nullable
    private AuthProviderFactory authProviderFactory;
    private final ImmutableSet.Builder<String> administrators = new Builder<>();
    private boolean caseSensitiveLoginNames;
    private String sessionCacheSpec = DEFAULT_SESSION_CACHE_SPEC;
    private long sessionTimeoutMillis = DEFAULT_SESSION_TIMEOUT_MILLIS;
    private String sessionValidationSchedule = DEFAULT_SESSION_VALIDATION_SCHEDULE;
    @Nullable
    private Object authProviderProperties;
    private int writeQuota;
    private int timeWindowSeconds;
    private MeterRegistry meterRegistry = Flags.meterRegistry();

    @Nullable
    private CorsConfig corsConfig;

    private final List<PluginConfig> pluginConfigs = new ArrayList<>();
    private final List<Plugin> plugins = new ArrayList<>();
    @Nullable
    private ManagementConfig managementConfig;
    @Nullable
    private ZoneConfig zoneConfig;

    /**
     * Creates a new builder with the specified data directory.
     */
    public CentralDogmaBuilder(File dataDir) {
        this.dataDir = requireNonNull(dataDir, "dataDir");
        if (dataDir.exists() && !dataDir.isDirectory()) {
            throw new IllegalArgumentException("dataDir: " + dataDir + " (not a directory)");
        }
    }

    /**
     * Adds a port that serves the HTTP requests. If unspecified, cleartext HTTP on port 36462 is used.
     *
     * @param port the TCP/IP port number
     * @param protocol {@link SessionProtocol#HTTP} or {@link SessionProtocol#HTTPS}
     */
    public CentralDogmaBuilder port(int port, SessionProtocol protocol) {
        return port(new ServerPort(port, protocol));
    }

    /**
     * Adds a port that serves the HTTP requests. If unspecified, cleartext HTTP on port 36462 is used.
     *
     * @param localAddress the TCP/IP load address to bind
     * @param protocol {@link SessionProtocol#HTTP} or {@link SessionProtocol#HTTPS}
     */
    public CentralDogmaBuilder port(InetSocketAddress localAddress, SessionProtocol protocol) {
        return port(new ServerPort(localAddress, protocol));
    }

    /**
     * Adds a port that serves the HTTP requests. If unspecified, cleartext HTTP on port 36462 is used.
     */
    public CentralDogmaBuilder port(ServerPort port) {
        ports.add(requireNonNull(port, "port"));
        return this;
    }

    /**
     * Sets a {@link TlsConfig} for supporting TLS on the server.
     */
    public CentralDogmaBuilder tls(TlsConfig tls) {
        this.tls = requireNonNull(tls, "tls");
        return this;
    }

    /**
     * Adds addresses or ranges of <a href="https://tools.ietf.org/html/rfc4632">
     * Classless Inter-domain Routing (CIDR)</a> blocks of trusted proxy servers.
     *
     * @param exactOrCidrAddresses a list of addresses and CIDR blocks, e.g. {@code 10.0.0.1} for a single
     *                             address or {@code 10.0.0.0/8} for a CIDR block
     */
    public CentralDogmaBuilder trustedProxyAddresses(String... exactOrCidrAddresses) {
        requireNonNull(exactOrCidrAddresses, "exactOrCidrAddresses");
        trustedProxyAddresses.addAll(ImmutableList.copyOf(exactOrCidrAddresses));
        return this;
    }

    /**
     * Adds addresses or ranges of <a href="https://tools.ietf.org/html/rfc4632">
     * Classless Inter-domain Routing (CIDR)</a> blocks of trusted proxy servers.
     *
     * @param exactOrCidrAddresses a list of addresses and CIDR blocks, e.g. {@code 10.0.0.1} for a single
     *                             address or {@code 10.0.0.0/8} for a CIDR block
     */
    public CentralDogmaBuilder trustedProxyAddresses(Iterable<String> exactOrCidrAddresses) {
        requireNonNull(exactOrCidrAddresses, "exactOrCidrAddresses");
        trustedProxyAddresses.addAll(ImmutableList.copyOf(exactOrCidrAddresses));
        return this;
    }

    /**
     * Adds the HTTP header names to be used for retrieving a client address.
     *
     * <p>Note that {@code "PROXY_PROTOCOL"} indicates the source address specified in a
     * <a href="https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt">PROXY protocol</a> message.
     *
     * <p>Also note that if you configured trusted proxy addresses, {@code "forwarded"},
     * {@code "x-forwarded-for"} and {@code "PROXY_PROTOCOL"} will be used as client address sources by default.
     *
     * @param clientAddressSources the HTTP header names or {@code "PROXY_PROTOCOL"} to be used for
     *                             retrieving a client address
     */
    public CentralDogmaBuilder clientAddressSources(String... clientAddressSources) {
        requireNonNull(clientAddressSources, "clientAddressSources");
        this.clientAddressSources.addAll(ImmutableList.copyOf(clientAddressSources));
        return this;
    }

    /**
     * Adds the HTTP header names to be used for retrieving a client address.
     *
     * <p>Note that {@code "PROXY_PROTOCOL"} indicates the source address specified in a
     * <a href="https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt">PROXY protocol</a> message.
     *
     * <p>Also note that if you configured trusted proxy addresses, {@code "forwarded"},
     * {@code "x-forwarded-for"} and {@code "PROXY_PROTOCOL"} will be used as client address sources by default.
     *
     * @param clientAddressSources the HTTP header names or {@code "PROXY_PROTOCOL"} to be used for
     *                             retrieving a client address
     */
    public CentralDogmaBuilder clientAddressSources(Iterable<String> clientAddressSources) {
        requireNonNull(clientAddressSources, "clientAddressSources");
        this.clientAddressSources.addAll(ImmutableList.copyOf(clientAddressSources));
        return this;
    }

    /**
     * Sets the number of I/O worker threads. <a href="https://line.github.io/armeria/">Armeria</a> default is
     * used if unspecified.
     */
    public CentralDogmaBuilder numWorkers(int numWorkers) {
        this.numWorkers = numWorkers;
        return this;
    }

    /**
     * Sets the maximum allowed number of TCP/IP connections. If unspecified, no limit is enforced.
     */
    public CentralDogmaBuilder maxNumConnections(int maxNumConnections) {
        this.maxNumConnections = maxNumConnections;
        return this;
    }

    /**
     * Sets the timeout for handling an incoming request. If it takes more than the specified timeout to
     * handle a request, the server may respond with '503 Service Unavailable' or fail to respond.
     * <a href="https://line.github.io/armeria/">Armeria</a> default is used if unspecified.
     */
    public CentralDogmaBuilder requestTimeout(Duration requestTimeout) {
        return requestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    /**
     * Sets the timeout for handling an incoming request, in milliseconds. If it takes more than
     * the specified timeout to handle a request, the server may respond with '503 Service Unavailable' or
     * fail to respond. <a href="https://line.github.io/armeria/">Armeria</a> default is used if unspecified.
     */
    public CentralDogmaBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
        return this;
    }

    /**
     * Sets the timeout for keeping an idle connection. A connection is automatically closed when it stays idle
     * without any requests in progress for more than the specified timeout.
     * <a href="https://line.github.io/armeria/">Armeria</a> default is used if unspecified.
     */
    public CentralDogmaBuilder idleTimeout(Duration idleTimeout) {
        return idleTimeoutMillis(requireNonNull(idleTimeout, "idleTimeout").toMillis());
    }

    /**
     * Sets the timeout for keeping an idle connection, in milliseconds. A connection is automatically closed
     * when it stays idle without any requests in progress for more than the specified timeout.
     */
    public CentralDogmaBuilder idleTimeoutMillis(long idleTimeoutMillis) {
        this.idleTimeoutMillis = idleTimeoutMillis;
        return this;
    }

    /**
     * Sets the maximum allowed content length of an incoming request.
     */
    public CentralDogmaBuilder maxFrameLength(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
        return this;
    }

    /**
     * Sets the number of worker threads dedicated to repository access.
     * If unspecified, {@value #DEFAULT_NUM_REPOSITORY_WORKERS} threads are created at maximum.
     */
    public CentralDogmaBuilder numRepositoryWorkers(int numRepositoryWorkers) {
        this.numRepositoryWorkers = numRepositoryWorkers;
        return this;
    }

    /**
     * Sets the maximum allowed age of removed projects and repositories before they are purged.
     * Set {@code 0} to disable automatic purge.
     * If unspecified, the default of {@value #DEFAULT_MAX_REMOVED_REPOSITORY_AGE_MILLIS} milliseconds is used.
     */
    public CentralDogmaBuilder maxRemovedRepositoryAge(Duration maxRemovedRepositoryAge) {
        maxRemovedRepositoryAgeMillis(
                requireNonNull(maxRemovedRepositoryAge, "maxRemovedRepositoryAge").toMillis());
        return this;
    }

    /**
     * Sets the maximum allowed age, in milliseconds of removed projects and repositories
     * before they are purged.
     * Set {@code 0} to disable automatic purge.
     * If unspecified, the default of {@value #DEFAULT_MAX_REMOVED_REPOSITORY_AGE_MILLIS} milliseconds is used.
     */
    public CentralDogmaBuilder maxRemovedRepositoryAgeMillis(long maxRemovedRepositoryAgeMillis) {
        this.maxRemovedRepositoryAgeMillis = maxRemovedRepositoryAgeMillis;
        return this;
    }

    /**
     * Sets the cache specification which determines the capacity and behavior of the cache for the return
     * values of methods in {@link Repository} of the server. See {@link CaffeineSpec} for the syntax
     * of the spec. If unspecified, the default cache spec of {@value #DEFAULT_REPOSITORY_CACHE_SPEC} is used.
     *
     * @deprecated Use {@link #repositoryCacheSpec(String)}.
     */
    @Deprecated
    public CentralDogmaBuilder cacheSpec(String cacheSpec) {
        repositoryCacheSpec = validateCacheSpec(cacheSpec);
        return this;
    }

    /**
     * Sets the cache specification which determines the capacity and behavior of the cache for the return
     * values of methods in {@link Repository} of the server. See {@link CaffeineSpec} for the syntax
     * of the spec. If unspecified, the default cache spec of {@value #DEFAULT_REPOSITORY_CACHE_SPEC} is used.
     */
    public CentralDogmaBuilder repositoryCacheSpec(String repositoryCacheSpec) {
        this.repositoryCacheSpec = validateCacheSpec(repositoryCacheSpec);
        return this;
    }

    /**
     * Sets whether administrative web application is enabled or not.
     * If unspecified, the administrative web application is enabled.
     */
    public CentralDogmaBuilder webAppEnabled(boolean webAppEnabled) {
        this.webAppEnabled = webAppEnabled;
        return this;
    }

    /**
     * Sets the title text which is displayed on the navigation bar of the administrative web application.
     */
    public CentralDogmaBuilder webAppTitle(String webAppTitle) {
        this.webAppTitle = requireNonNull(webAppTitle, "webAppTitle");
        return this;
    }

    /**
     * Sets the graceful shutdown timeout. If unspecified, graceful shutdown is disabled.
     */
    public CentralDogmaBuilder gracefulShutdownTimeout(GracefulShutdownTimeout gracefulShutdownTimeout) {
        this.gracefulShutdownTimeout = gracefulShutdownTimeout;
        return this;
    }

    /**
     * Configures the replication.
     * If unspecified or {@link ReplicationConfig#NONE} is specified, replication is disabled.
     */
    public CentralDogmaBuilder replication(ReplicationConfig replicationConfig) {
        this.replicationConfig = requireNonNull(replicationConfig, "replicationConfig");
        return this;
    }

    /**
     * Configures a format of an access log. It will work only if any logging framework is configured.
     * Read the <a href="https://line.github.io/armeria/docs/server-access-log">Writing an access log</a>
     * document for more information.
     */
    public CentralDogmaBuilder accessLogFormat(String accessLogFormat) {
        this.accessLogFormat = requireNonNull(accessLogFormat, "accessLogFormat");
        return this;
    }

    /**
     * Sets an {@link AuthProviderFactory} instance which is used to create a new {@link AuthProvider}.
     */
    public CentralDogmaBuilder authProviderFactory(AuthProviderFactory authProviderFactory) {
        this.authProviderFactory = requireNonNull(authProviderFactory, "authProviderFactory");
        return this;
    }

    /**
     * Adds administrators to the set.
     */
    public CentralDogmaBuilder administrators(String... administrators) {
        requireNonNull(administrators, "administrators");
        for (final String administrator : administrators) {
            this.administrators.add(administrator);
        }
        return this;
    }

    /**
     * Adds administrators to the set.
     */
    public CentralDogmaBuilder administrators(Iterable<String> administrators) {
        requireNonNull(administrators, "administrators");
        this.administrators.addAll(administrators);
        return this;
    }

    /**
     * Sets whether case-sensitive matching is performed when login names are compared.
     */
    public CentralDogmaBuilder caseSensitiveLoginNames(boolean caseSensitiveLoginNames) {
        this.caseSensitiveLoginNames = caseSensitiveLoginNames;
        return this;
    }

    /**
     * Sets the cache specification which determines the capacity and behavior of the cache for
     * {@link Session} of the server. See {@link CaffeineSpec} for the syntax of the spec.
     * If unspecified, the default cache spec of {@value AuthConfig#DEFAULT_SESSION_CACHE_SPEC}
     * is used.
     */
    public CentralDogmaBuilder sessionCacheSpec(String sessionCacheSpec) {
        this.sessionCacheSpec = validateCacheSpec(sessionCacheSpec);
        return this;
    }

    /**
     * Sets the session timeout for administrative web application, in milliseconds.
     * If unspecified, {@value AuthConfig#DEFAULT_SESSION_TIMEOUT_MILLIS} is used.
     */
    public CentralDogmaBuilder sessionTimeoutMillis(long sessionTimeoutMillis) {
        this.sessionTimeoutMillis = sessionTimeoutMillis;
        return this;
    }

    /**
     * Sets the session timeout for administrative web application.
     * If unspecified, {@value AuthConfig#DEFAULT_SESSION_TIMEOUT_MILLIS} is used.
     */
    public CentralDogmaBuilder sessionTimeout(Duration sessionTimeout) {
        return sessionTimeoutMillis(
                requireNonNull(sessionTimeout, "sessionTimeout").toMillis());
    }

    /**
     * Sets a schedule for validating sessions.
     * If unspecified, {@value AuthConfig#DEFAULT_SESSION_VALIDATION_SCHEDULE} is used.
     */
    public CentralDogmaBuilder sessionValidationSchedule(String sessionValidationSchedule) {
        this.sessionValidationSchedule =
                requireNonNull(sessionValidationSchedule, "sessionValidationSchedule");
        return this;
    }

    /**
     * Sets an additional properties for an {@link AuthProviderFactory}.
     */
    public CentralDogmaBuilder authProviderProperties(Object authProviderProperties) {
        this.authProviderProperties = requireNonNull(authProviderProperties, "authProviderProperties");
        return this;
    }

    /**
     * Sets maximum allowed write requests per {@code timeWindowSeconds} for each {@link Repository}.
     */
    public CentralDogmaBuilder writeQuotaPerRepository(int writeQuota, int timeWindowSeconds) {
        checkArgument(writeQuota > 0, "writeQuota: %s (expected: > 0)", writeQuota);
        checkArgument(timeWindowSeconds > 0, "timeWindowSeconds: %s (expected: > 0)", timeWindowSeconds);
        this.writeQuota = writeQuota;
        this.timeWindowSeconds = timeWindowSeconds;
        return this;
    }

    /**
     * Sets the {@link MeterRegistry} used to collect metrics.
     */
    public CentralDogmaBuilder meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        return this;
    }

    /**
     * Enables CORS with the specified allowed origins.
     */
    public CentralDogmaBuilder cors(String... allowedOrigins) {
        requireNonNull(allowedOrigins, "allowedOrigins");
        corsConfig = new CorsConfig(ImmutableList.copyOf(allowedOrigins), null);
        return this;
    }

    /**
     * Enables CORS with the specified {@link CorsConfig}.
     */
    public CentralDogmaBuilder cors(CorsConfig corsConfig) {
        this.corsConfig = requireNonNull(corsConfig, "corsConfig");
        return this;
    }

    /**
     * Adds the {@link PluginConfig}s.
     */
    public CentralDogmaBuilder pluginConfigs(PluginConfig... pluginConfigs) {
        requireNonNull(pluginConfigs, "pluginConfigs");
        this.pluginConfigs.addAll(ImmutableList.copyOf(pluginConfigs));
        return this;
    }

    /**
     * Returns the {@link PluginConfig}s that have been added.
     */
    public List<PluginConfig> pluginConfigs() {
        return pluginConfigs;
    }

    /**
     * Adds the {@link Plugin}s.
     */
    public CentralDogmaBuilder plugins(Plugin... plugins) {
        requireNonNull(plugins, "plugins");
        return plugins(ImmutableList.copyOf(plugins));
    }

    /**
     * Adds the {@link Plugin}s.
     */
    public CentralDogmaBuilder plugins(Iterable<? extends Plugin> plugins) {
        requireNonNull(plugins, "plugins");
        this.plugins.addAll(ImmutableList.copyOf(plugins));
        return this;
    }

    /**
     * Enables a management service with the specified {@link ManagementConfig}.
     */
    public CentralDogmaBuilder management(ManagementConfig managementConfig) {
        requireNonNull(managementConfig, "managementConfig");
        this.managementConfig = managementConfig;
        return this;
    }

    /**
     * Specifies the {@link ZoneConfig} of the server.
     */
    public CentralDogmaBuilder zone(ZoneConfig zoneConfig) {
        requireNonNull(zoneConfig, "zoneConfig");
        this.zoneConfig = zoneConfig;
        return this;
    }

    /**
     * Returns a newly-created {@link CentralDogma} server.
     */
    public CentralDogma build() {
        return new CentralDogma(buildConfig(), meterRegistry, ImmutableList.copyOf(plugins));
    }

    private CentralDogmaConfig buildConfig() {
        final List<ServerPort> ports = !this.ports.isEmpty() ? this.ports
                                                             : Collections.singletonList(DEFAULT_PORT);
        final Set<String> adminSet = administrators.build();
        final AuthConfig authCfg;
        if (authProviderFactory != null) {
            authCfg = new AuthConfig(
                    authProviderFactory, adminSet, caseSensitiveLoginNames,
                    sessionCacheSpec, sessionTimeoutMillis, sessionValidationSchedule,
                    authProviderProperties != null ? Jackson.valueToTree(authProviderProperties) : null);
        } else {
            authCfg = null;
            logger.info("{} is not specified, so {} will not be configured.",
                        AuthProviderFactory.class.getSimpleName(),
                        AuthConfig.class.getSimpleName());
        }

        final QuotaConfig quotaConfig = writeQuota > 0 ? new QuotaConfig(writeQuota, timeWindowSeconds) : null;

        return new CentralDogmaConfig(dataDir, ports, tls, trustedProxyAddresses, clientAddressSources,
                                      numWorkers, maxNumConnections,
                                      requestTimeoutMillis, idleTimeoutMillis, maxFrameLength,
                                      numRepositoryWorkers, repositoryCacheSpec,
                                      maxRemovedRepositoryAgeMillis, gracefulShutdownTimeout,
                                      webAppEnabled, webAppTitle, replicationConfig,
                                      null, accessLogFormat, authCfg, quotaConfig,
                                      corsConfig, pluginConfigs, managementConfig, zoneConfig);
    }
}
