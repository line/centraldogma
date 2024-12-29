/*
 * Copyright 2024 LINE Corporation
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

import java.io.File;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.linecorp.armeria.server.ClientAddressSource;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.centraldogma.server.auth.AuthConfigSpec;
import com.linecorp.centraldogma.server.plugin.PluginConfig;
import com.linecorp.centraldogma.server.plugin.PluginTarget;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * {@link CentralDogma} server configuration spec.
 */
public interface CentralDogmaConfigSpec {
    /**
     * Returns the data directory.
     */
    File dataDir();

    /**
     * Returns the {@link ServerPort}s.
     */
    List<ServerPort> ports();

    /**
     * Returns the TLS configuration.
     */
    @Nullable
    TlsConfigSpec tls();

    /**
     * Returns the IP addresses of the trusted proxy servers. If trusted, the sources specified in
     * {@link #clientAddressSources()} will be used to determine the actual IP address of clients.
     */
    @Nullable
    List<String> trustedProxyAddresses();

    /**
     * Returns the sources that determines a client address. For example:
     * <ul>
     *   <li>{@code "forwarded"}</li>
     *   <li>{@code "x-forwarded-for"}</li>
     *   <li>{@code "PROXY_PROTOCOL"}</li>
     * </ul>
     *
     */
    @Nullable
    List<String> clientAddressSources();

    /**
     * Returns the number of event loop threads.
     */
    Optional<Integer> numWorkers();

    /**
     * Returns the maximum number of established connections.
     */
    Optional<Integer> maxNumConnections();

    /**
     * Returns the request timeout in milliseconds.
     */
    Optional<Long> requestTimeoutMillis();

    /**
     * Returns the timeout of an idle connection in milliseconds.
     */
    Optional<Long> idleTimeoutMillis();

    /**
     * Returns the maximum length of request content in bytes.
     */
    Optional<Integer> maxFrameLength();

    /**
     * Returns the number of repository worker threads.
     */
    int numRepositoryWorkers();

    /**
     * Returns the maximum age of a removed repository in milliseconds. A removed repository is first marked
     * as removed, and then is purged permanently once the amount of time returned by this property passes
     * since marked.
     */
    long maxRemovedRepositoryAgeMillis();

    /**
     * Returns the cache spec of the repository cache.
     */
    String repositoryCacheSpec();

    /**
     * Returns the graceful shutdown timeout.
     */
    Optional<GracefulShutdownTimeoutSpec> gracefulShutdownTimeout();

    /**
     * Returns whether web app is enabled.
     */

    boolean isWebAppEnabled();

    /**
     * Returns the title of the web app.
     */
    @Nullable
    String webAppTitle();

    /**
     * Returns the {@link ReplicationConfig}.
     */

    ReplicationConfig replicationConfig();

    /**
     * Returns whether a CSRF token is required for Thrift clients. Note that it's not safe to enable this
     * feature. It only exists for a legacy Thrift client that does not send a CSRF token.
     */

    boolean isCsrfTokenRequiredForThrift();

    /**
     * Returns the access log format.
     */
    @Nullable
    String accessLogFormat();

    /**
     * Returns the {@link AuthConfigSpec}.
     */
    @Nullable
    AuthConfigSpec authConfig();

    /**
     * Returns the maximum allowed write quota per {@link Repository}.
     */
    @Nullable
    QuotaConfigSpec writeQuotaPerRepository();

    /**
     * Returns the {@link CorsConfigSpec}.
     */
    @Nullable
    CorsConfigSpec corsConfig();

    /**
     * Returns the list of {@link PluginConfig}s.
     */
    List<PluginConfig> pluginConfigs();

    /**
     * Returns the map of {@link PluginConfig}s.
     * @deprecated This will be removed soon.
     */
    @Deprecated
    Map<Class<? extends PluginConfig>, PluginConfig> pluginConfigMap();

    /**
     * Returns the {@link PluginConfig} with casting.
     */
    @Nullable
    <T extends PluginConfig> T pluginConfig(Class<T> pluginClass);

    /**
     * Returns the {@link ManagementConfig}.
     */
    @Nullable
    ManagementConfigSpec managementConfig();

    /**
     * Returns the zone information of the server.
     * Note that the zone must be specified to use the {@link PluginTarget#ZONE_LEADER_ONLY} target.
     */
    @Nullable
    ZoneConfigSpec zone();

    /**
     * Returns the predicate of trusted proxy address.
     */
    Predicate<InetAddress> trustedProxyAddressPredicate();

    /**
     * Returns the client address source list.
     */
    List<ClientAddressSource> clientAddressSourceList();
}
