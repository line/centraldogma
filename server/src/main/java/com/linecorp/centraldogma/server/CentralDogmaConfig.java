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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_MAX_NUM_BYTES_PER_MIRROR;
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_MAX_NUM_FILES_PER_MIRROR;
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_NUM_MIRRORING_THREADS;
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_NUM_REPOSITORY_WORKERS;
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_REPOSITORY_CACHE_SPEC;
import static com.linecorp.centraldogma.server.internal.storage.repository.cache.RepositoryCache.validateCacheSpec;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.util.StdConverter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.auth.AuthConfig;

import io.netty.util.NetUtil;

public final class CentralDogmaConfig {

    private final File dataDir;

    // Armeria
    private final List<ServerPort> ports;
    @Nullable
    private final Integer numWorkers;
    @Nullable
    private final Integer maxNumConnections;
    @Nullable
    private final Long requestTimeoutMillis;
    @Nullable
    private final Long idleTimeoutMillis;
    @Nullable
    private final Integer maxFrameLength;
    @Nullable
    private final TlsConfig tls;

    // Repository
    private final Integer numRepositoryWorkers;

    // Cache
    private final String repositoryCacheSpec;

    // Web dashboard
    private final boolean webAppEnabled;

    @Nullable
    private final String webAppTitle;

    // Mirroring
    private final boolean mirroringEnabled;
    private final int numMirroringThreads;
    private final int maxNumFilesPerMirror;
    private final long maxNumBytesPerMirror;

    // Graceful shutdown
    @Nullable
    private final GracefulShutdownTimeout gracefulShutdownTimeout;

    // Replication
    private final ReplicationConfig replicationConfig;

    // Security
    private final boolean csrfTokenRequiredForThrift;

    // Access log
    @Nullable
    private final String accessLogFormat;

    @Nullable
    private final AuthConfig authConfig;

    CentralDogmaConfig(
            @JsonProperty(value = "dataDir", required = true) File dataDir,
            @JsonProperty(value = "ports", required = true)
            @JsonDeserialize(contentUsing = ServerPortDeserializer.class)
                    List<ServerPort> ports,
            @JsonProperty("tls") @Nullable TlsConfig tls,
            @JsonProperty("numWorkers") @Nullable Integer numWorkers,
            @JsonProperty("maxNumConnections") @Nullable Integer maxNumConnections,
            @JsonProperty("requestTimeoutMillis") @Nullable Long requestTimeoutMillis,
            @JsonProperty("idleTimeoutMillis") @Nullable Long idleTimeoutMillis,
            @JsonProperty("maxFrameLength") @Nullable Integer maxFrameLength,
            @JsonProperty("numRepositoryWorkers") @Nullable Integer numRepositoryWorkers,
            @JsonProperty("repositoryCacheSpec") @Nullable String repositoryCacheSpec,
            @JsonProperty("gracefulShutdownTimeout") @Nullable
                    GracefulShutdownTimeout gracefulShutdownTimeout,
            @JsonProperty("webAppEnabled") @Nullable Boolean webAppEnabled,
            @JsonProperty("webAppTitle") @Nullable String webAppTitle,
            @JsonProperty("mirroringEnabled") @Nullable Boolean mirroringEnabled,
            @JsonProperty("numMirroringThreads") @Nullable Integer numMirroringThreads,
            @JsonProperty("maxNumFilesPerMirror") @Nullable Integer maxNumFilesPerMirror,
            @JsonProperty("maxNumBytesPerMirror") @Nullable Long maxNumBytesPerMirror,
            @JsonProperty("replication") @Nullable ReplicationConfig replicationConfig,
            @JsonProperty("csrfTokenRequiredForThrift") @Nullable Boolean csrfTokenRequiredForThrift,
            @JsonProperty("accessLogFormat") @Nullable String accessLogFormat,
            @JsonProperty("authentication") @Nullable AuthConfig authConfig) {

        this.dataDir = requireNonNull(dataDir, "dataDir");
        this.ports = ImmutableList.copyOf(requireNonNull(ports, "ports"));
        checkArgument(!ports.isEmpty(), "ports must have at least one port.");
        this.tls = tls;
        this.numWorkers = numWorkers;

        this.maxNumConnections = maxNumConnections;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.maxFrameLength = maxFrameLength;
        this.numRepositoryWorkers = firstNonNull(numRepositoryWorkers, DEFAULT_NUM_REPOSITORY_WORKERS);
        checkArgument(this.numRepositoryWorkers > 0,
                      "numRepositoryWorkers: %s (expected: > 0)", this.numRepositoryWorkers);
        this.repositoryCacheSpec = validateCacheSpec(
                firstNonNull(repositoryCacheSpec, DEFAULT_REPOSITORY_CACHE_SPEC));
        this.webAppEnabled = firstNonNull(webAppEnabled, true);
        this.webAppTitle = webAppTitle;
        this.mirroringEnabled = firstNonNull(mirroringEnabled, true);
        this.numMirroringThreads = firstNonNull(numMirroringThreads, DEFAULT_NUM_MIRRORING_THREADS);
        checkArgument(this.numMirroringThreads > 0,
                      "numMirroringThreads: %s (expected: > 0)", this.numMirroringThreads);
        this.maxNumFilesPerMirror = firstNonNull(maxNumFilesPerMirror, DEFAULT_MAX_NUM_FILES_PER_MIRROR);
        checkArgument(this.maxNumFilesPerMirror > 0,
                      "maxNumFilesPerMirror: %s (expected: > 0)", this.maxNumFilesPerMirror);
        this.maxNumBytesPerMirror = firstNonNull(maxNumBytesPerMirror, DEFAULT_MAX_NUM_BYTES_PER_MIRROR);
        checkArgument(this.maxNumBytesPerMirror > 0,
                      "maxNumBytesPerMirror: %s (expected: > 0)", this.maxNumBytesPerMirror);
        this.gracefulShutdownTimeout = gracefulShutdownTimeout;
        this.replicationConfig = firstNonNull(replicationConfig, ReplicationConfig.NONE);
        this.csrfTokenRequiredForThrift = firstNonNull(csrfTokenRequiredForThrift, true);
        this.accessLogFormat = accessLogFormat;

        this.authConfig = authConfig;
    }

    @JsonProperty
    public File dataDir() {
        return dataDir;
    }

    @JsonProperty
    @JsonSerialize(contentUsing = ServerPortSerializer.class)
    public List<ServerPort> ports() {
        return ports;
    }

    @Nullable
    @JsonProperty
    public TlsConfig tls() {
        return tls;
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    public Optional<Integer> numWorkers() {
        return Optional.ofNullable(numWorkers);
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    public Optional<Integer> maxNumConnections() {
        return Optional.ofNullable(maxNumConnections);
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    public Optional<Long> requestTimeoutMillis() {
        return Optional.ofNullable(requestTimeoutMillis);
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    public Optional<Long> idleTimeoutMillis() {
        return Optional.ofNullable(idleTimeoutMillis);
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    public Optional<Integer> maxFrameLength() {
        return Optional.ofNullable(maxFrameLength);
    }

    @JsonProperty
    int numRepositoryWorkers() {
        return numRepositoryWorkers;
    }

    /**
     * Returns the {@code repositoryCacheSpec}.
     *
     * @deprecated Use {@link #repositoryCacheSpec()}.
     */
    @JsonProperty
    @Deprecated
    public String cacheSpec() {
        return repositoryCacheSpec;
    }

    @JsonProperty
    public String repositoryCacheSpec() {
        return repositoryCacheSpec;
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    public Optional<GracefulShutdownTimeout> gracefulShutdownTimeout() {
        return Optional.ofNullable(gracefulShutdownTimeout);
    }

    @JsonProperty
    public boolean isWebAppEnabled() {
        return webAppEnabled;
    }

    @Nullable
    @JsonProperty("webAppTitle")
    public String webAppTitle() {
        return webAppTitle;
    }

    @JsonProperty
    public boolean isMirroringEnabled() {
        return mirroringEnabled;
    }

    @JsonProperty
    public int numMirroringThreads() {
        return numMirroringThreads;
    }

    @JsonProperty
    public int maxNumFilesPerMirror() {
        return maxNumFilesPerMirror;
    }

    @JsonProperty
    public long maxNumBytesPerMirror() {
        return maxNumBytesPerMirror;
    }

    @JsonProperty("replication")
    public ReplicationConfig replicationConfig() {
        return replicationConfig;
    }

    @JsonProperty
    public boolean isCsrfTokenRequiredForThrift() {
        return csrfTokenRequiredForThrift;
    }

    @JsonProperty
    @Nullable
    public String accessLogFormat() {
        return accessLogFormat;
    }

    @Nullable
    @JsonProperty("authentication")
    public AuthConfig authConfig() {
        return authConfig;
    }

    @Override
    public String toString() {
        try {
            return Jackson.writeValueAsPrettyString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class ServerPortSerializer extends JsonSerializer<ServerPort> {
        @Override
        public void serialize(ServerPort value,
                              JsonGenerator gen, SerializerProvider serializers) throws IOException {

            final InetSocketAddress localAddr = value.localAddress();
            final int port = localAddr.getPort();
            final String host;

            if (localAddr.getAddress().isAnyLocalAddress()) {
                host = "*";
            } else {
                final String hs = localAddr.getHostString();
                if (NetUtil.isValidIpV6Address(hs)) {
                    // Try to get the platform-independent consistent IPv6 address string.
                    host = NetUtil.toAddressString(localAddr.getAddress());
                } else {
                    host = hs;
                }
            }

            gen.writeStartObject();
            gen.writeObjectFieldStart("localAddress");
            gen.writeStringField("host", host);
            gen.writeNumberField("port", port);
            gen.writeEndObject();
            gen.writeArrayFieldStart("protocols");
            for (final SessionProtocol protocol : value.protocols()) {
                gen.writeString(protocol.uriText());
            }
            gen.writeEndArray();
            gen.writeEndObject();
        }
    }

    static final class ServerPortDeserializer extends JsonDeserializer<ServerPort> {
        @Override
        public ServerPort deserialize(JsonParser p, DeserializationContext ctx) throws IOException {

            final JsonNode root = p.getCodec().readTree(p);
            final JsonNode localAddress = root.get("localAddress");
            if (localAddress == null || localAddress.getNodeType() != JsonNodeType.OBJECT) {
                return fail(ctx, root);
            }

            final JsonNode host = localAddress.get("host");
            if (host == null || host.getNodeType() != JsonNodeType.STRING) {
                return fail(ctx, root);
            }

            final JsonNode port = localAddress.get("port");
            if (port == null || port.getNodeType() != JsonNodeType.NUMBER) {
                return fail(ctx, root);
            }

            final ImmutableSet.Builder<SessionProtocol> protocolsBuilder = ImmutableSet.builder();
            final JsonNode protocols = root.get("protocols");
            if (protocols != null) {
                if (protocols.getNodeType() != JsonNodeType.ARRAY) {
                    return fail(ctx, root);
                }
                protocols.elements().forEachRemaining(
                        protocol -> protocolsBuilder.add(SessionProtocol.of(protocol.textValue())));
            } else {
                final JsonNode protocol = root.get("protocol");
                if (protocol == null || protocol.getNodeType() != JsonNodeType.STRING) {
                    return fail(ctx, root);
                }
                protocolsBuilder.add(SessionProtocol.of(protocol.textValue()));
            }

            final String hostVal = host.textValue();
            final int portVal = port.intValue();

            final InetSocketAddress localAddressVal;
            if ("*".equals(hostVal)) {
                localAddressVal = new InetSocketAddress(portVal);
            } else {
                localAddressVal = new InetSocketAddress(hostVal, portVal);
            }

            return new ServerPort(localAddressVal, protocolsBuilder.build());
        }

        private static ServerPort fail(DeserializationContext ctx, JsonNode root) throws JsonMappingException {
            ctx.reportInputMismatch(ServerPort.class, "invalid server port information: %s", root);
            throw new Error(); // Should never reach here.
        }
    }

    static final class OptionalConverter extends StdConverter<Optional<?>, Object> {
        @Override
        @Nullable
        public Object convert(Optional<?> value) {
            return value.orElse(null);
        }
    }
}
