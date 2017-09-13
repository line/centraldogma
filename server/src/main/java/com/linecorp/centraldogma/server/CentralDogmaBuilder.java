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

import com.github.benmanes.caffeine.cache.CaffeineSpec;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.centraldogma.server.internal.storage.repository.cache.RepositoryCache;

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
    private Ini securityConfig;

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
     * Sets the cache specification of the server. See {@link CaffeineSpec} for the syntax of the spec.
     * If unspecified, the default cache spec of {@value #DEFAULT_CACHE_SPEC} is used.
     */
    public CentralDogmaBuilder cacheSpec(String cacheSpec) {
        this.cacheSpec = RepositoryCache.validateCacheSpec(cacheSpec);
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
     * Sets whether {@link MirroringService} is enabled or not.
     * If unspecified, {@link MirroringService} is enabled.
     */
    public CentralDogmaBuilder mirroringEnabled(boolean mirroringEnabled) {
        this.mirroringEnabled = mirroringEnabled;
        return this;
    }

    /**
     * Sets the number of worker threads dedicated to mirroring between repositories.
     * If unspecified, {@value #DEFAULT_NUM_MIRRORING_THREADS} threads are created at maximum.
     */
    public CentralDogmaBuilder numMirroringThreads(int numMirroringThreads) {
        this.numMirroringThreads = numMirroringThreads;
        return this;
    }

    /**
     * Sets the maximum allowed number of files in a mirrored tree.
     * If unspecified, {@value #DEFAULT_MAX_NUM_FILES_PER_MIRROR} files are allowed at maximum.
     */
    public CentralDogmaBuilder maxNumFilesPerMirror(int maxNumFilesPerMirror) {
        this.maxNumFilesPerMirror = maxNumFilesPerMirror;
        return this;
    }

    /**
     * Sets the maximum allowed number of bytes in a mirrored tree.
     * If unspecified, {@value #DEFAULT_MAX_NUM_BYTES_PER_MIRROR} bytes are allowed at maximum.
     */
    public CentralDogmaBuilder maxNumBytesPerMirror(long maxNumBytesPerMirror) {
        this.maxNumBytesPerMirror = maxNumBytesPerMirror;
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
     * Configures the security using an {@link Ini} configuration for
     * <a href="https://shiro.apache.org">Apache Shiro</a>. An {@link Ini} object would be created by
     * {@link Ini#fromResourcePath(String)} with the INI file path.
     */
    public CentralDogmaBuilder securityConfig(Ini securityConfig) {
        requireNonNull(securityConfig, "securityConfig");
        final Ini iniCopy = new Ini();
        iniCopy.putAll(securityConfig);
        this.securityConfig = iniCopy;
        return this;
    }

    /**
     * Returns a newly-created {@link CentralDogma} server.
     */
    public CentralDogma build() {
        return new CentralDogma(buildConfig(), securityConfig);
    }

    private CentralDogmaConfig buildConfig() {
        final List<ServerPort> ports = !this.ports.isEmpty() ? this.ports
                                                             : Collections.singletonList(DEFAULT_PORT);

        return new CentralDogmaConfig(dataDir, ports, numWorkers, maxNumConnections,
                                      requestTimeoutMillis, idleTimeoutMillis, maxFrameLength,
                                      numRepositoryWorkers, cacheSpec, gracefulShutdownTimeout,
                                      webAppEnabled, mirroringEnabled, numMirroringThreads,
                                      maxNumFilesPerMirror, maxNumBytesPerMirror, replicationConfig,
                                      securityConfig != null);
    }
}
