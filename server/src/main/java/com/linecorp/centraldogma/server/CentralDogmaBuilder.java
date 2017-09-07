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

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.shiro.config.Ini;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.centraldogma.common.Jackson;
import com.linecorp.centraldogma.server.replication.ReplicationConfig;
import com.linecorp.centraldogma.server.repository.cache.RepositoryCache;

public final class CentralDogmaBuilder {

    // You get 36462 if you map 'dogma' to T9 phone dialer layout.
    private static final ServerPort DEFAULT_PORT = new ServerPort(36462, SessionProtocol.HTTP);

    static final int DEFAULT_NUM_REPOSITORY_WORKERS = 16;
    static final int DEFAULT_NUM_MIRRORING_THREADS = 16;
    static final int DEFAULT_MAX_NUM_FILES_PER_MIRROR = 8192;
    static final long DEFAULT_MAX_NUM_BYTES_PER_MIRROR = 32 * 1048576; // 32 MiB
    static final String DEFAULT_CACHE_SPEC = "maximumWeight=134217728," + // Cache up to apx. 128-megachars
                                             "expireAfterAccess=5m";      // Expire on 5 minutes of inactivity

    // Armeria properties
    // Note that we use nullable types here for optional properties.
    // When a property is null, the default value will be used implicitly.
    private final List<ServerPort> ports = new ArrayList<>(2);
    private Integer numWorkers;
    private Integer maxNumConnections;
    private Long requestTimeoutMillis;
    private Long idleTimeoutMillis;
    private Integer maxFrameLength;

    // Central Dogma properties
    private final File dataDir;
    private int numRepositoryWorkers = DEFAULT_NUM_REPOSITORY_WORKERS;
    private String cacheSpec = DEFAULT_CACHE_SPEC;
    private boolean webAppEnabled = true;
    private boolean mirroringEnabled = true;
    private int numMirroringThreads = DEFAULT_NUM_MIRRORING_THREADS;
    private int maxNumFilesPerMirror = DEFAULT_MAX_NUM_FILES_PER_MIRROR;
    private long maxNumBytesPerMirror = DEFAULT_MAX_NUM_BYTES_PER_MIRROR;
    private GracefulShutdownTimeout gracefulShutdownTimeout;
    private ReplicationConfig replicationConfig = ReplicationConfig.NONE;
    private boolean securityEnabled = false;

    private Ini securityConfig;

    public CentralDogmaBuilder(File dataDir) {
        this.dataDir = requireNonNull(dataDir, "dataDir");
        if (dataDir.exists() && !dataDir.isDirectory()) {
            throw new IllegalArgumentException("dataDir: " + dataDir + " (not a directory)");
        }
    }

    public CentralDogmaBuilder port(int port, SessionProtocol protocol) {
        return port(new ServerPort(port, protocol));
    }

    public CentralDogmaBuilder port(InetSocketAddress localAddress, SessionProtocol protocol) {
        return port(new ServerPort(localAddress, protocol));
    }

    public CentralDogmaBuilder port(ServerPort port) {
        ports.add(requireNonNull(port, "port"));
        return this;
    }

    public CentralDogmaBuilder numWorkers(int numWorkers) {
        this.numWorkers = numWorkers;
        return this;
    }

    public CentralDogmaBuilder maxNumConnections(int maxNumConnections) {
        this.maxNumConnections = maxNumConnections;
        return this;
    }

    public CentralDogmaBuilder requestTimeout(Duration requestTimeout) {
        return requestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    public CentralDogmaBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
        return this;
    }

    public CentralDogmaBuilder idleTimeout(Duration idleTimeout) {
        return idleTimeoutMillis(requireNonNull(idleTimeout, "idleTimeout").toMillis());
    }

    public CentralDogmaBuilder idleTimeoutMillis(long idleTimeoutMillis) {
        this.idleTimeoutMillis = idleTimeoutMillis;
        return this;
    }

    public CentralDogmaBuilder maxFrameLength(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
        return this;
    }

    public CentralDogmaBuilder numRepositoryWorkers(int numRepositoryWorkers) {
        this.numRepositoryWorkers = numRepositoryWorkers;
        return this;
    }

    public CentralDogmaBuilder cacheSpec(String cacheSpec) {
        this.cacheSpec = RepositoryCache.validateCacheSpec(cacheSpec);
        return this;
    }

    public CentralDogmaBuilder webAppEnabled(boolean webAppEnabled) {
        this.webAppEnabled = webAppEnabled;
        return this;
    }

    public CentralDogmaBuilder mirroringEnabled(boolean mirroringEnabled) {
        this.mirroringEnabled = mirroringEnabled;
        return this;
    }

    public CentralDogmaBuilder numMirroringThreads(int numMirroringThreads) {
        this.numMirroringThreads = numMirroringThreads;
        return this;
    }

    public CentralDogmaBuilder maxNumFilesPerMirror(int maxNumFilesPerMirror) {
        this.maxNumFilesPerMirror = maxNumFilesPerMirror;
        return this;
    }

    public CentralDogmaBuilder maxNumBytesPerMirror(long maxNumBytesPerMirror) {
        this.maxNumBytesPerMirror = maxNumBytesPerMirror;
        return this;
    }

    public CentralDogmaBuilder gracefulShutdownTimeout(GracefulShutdownTimeout gracefulShutdownTimeout) {
        this.gracefulShutdownTimeout = gracefulShutdownTimeout;
        return this;
    }

    public CentralDogmaBuilder replication(ReplicationConfig replicationConfig) {
        this.replicationConfig = requireNonNull(replicationConfig, "replicationConfig");
        return this;
    }

    public CentralDogmaBuilder securityEnabled(boolean securityEnabled) {
        this.securityEnabled = securityEnabled;
        return this;
    }

    public CentralDogmaBuilder securityConfig(Ini securityConfig) {
        this.securityConfig = requireNonNull(securityConfig, "securityConfig");
        return this;
    }

    public CentralDogma build() {
        return new CentralDogma(buildConfig(), securityConfig);
    }

    public String toJson() {
        try {
            return Jackson.writeValueAsPrettyString(buildConfig());
        } catch (JsonProcessingException e) {
            // Should never reach here.
            throw new Error(e);
        }
    }

    private CentralDogmaConfig buildConfig() {
        final List<ServerPort> ports = !this.ports.isEmpty() ? this.ports
                                                             : Collections.singletonList(DEFAULT_PORT);

        return new CentralDogmaConfig(dataDir, ports, numWorkers, maxNumConnections,
                                      requestTimeoutMillis, idleTimeoutMillis, maxFrameLength,
                                      numRepositoryWorkers, cacheSpec, gracefulShutdownTimeout,
                                      webAppEnabled, mirroringEnabled, numMirroringThreads,
                                      maxNumFilesPerMirror, maxNumBytesPerMirror, replicationConfig,
                                      securityEnabled
        );
    }
}
