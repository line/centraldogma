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
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_CACHE_SPEC;
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_MAX_NUM_BYTES_PER_MIRROR;
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_MAX_NUM_FILES_PER_MIRROR;
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_NUM_MIRRORING_THREADS;
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_NUM_REPOSITORY_WORKERS;
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_WEB_APP_SESSION_TIMEOUT_MILLIS;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.storage.repository.cache.RepositoryCache;

import io.netty.util.NetUtil;

final class CentralDogmaConfig {

    private final File dataDir;

    // Armeria
    private final List<ServerPort> ports;
    private final Integer numWorkers;
    private final Integer maxNumConnections;
    private final Long requestTimeoutMillis;
    private final Long idleTimeoutMillis;
    private final Integer maxFrameLength;
    private final TlsConfig tls;

    // Repository
    private final Integer numRepositoryWorkers;

    // Cache
    private final String cacheSpec;

    // Web dashboard
    private final boolean webAppEnabled;
    private final long webAppSessionTimeoutMillis;

    // Mirroring
    private final boolean mirroringEnabled;
    private final int numMirroringThreads;
    private final int maxNumFilesPerMirror;
    private final long maxNumBytesPerMirror;

    // Graceful shutdown
    private final GracefulShutdownTimeout gracefulShutdownTimeout;

    // Replication
    private final ReplicationConfig replicationConfig;

    // Security
    private final boolean securityEnabled;
    private final boolean csrfTokenRequiredForThrift;

    // Access log
    private final String accessLogFormat;

    // Administrator
    private final Set<String> administrators;

    CentralDogmaConfig(@JsonProperty(value = "dataDir", required = true) File dataDir,
                       @JsonProperty(value = "ports", required = true)
                       @JsonDeserialize(contentUsing = ServerPortDeserializer.class)
                               List<ServerPort> ports,
                       @JsonProperty("tls") TlsConfig tls,
                       @JsonProperty("numWorkers") Integer numWorkers,
                       @JsonProperty("maxNumConnections") Integer maxNumConnections,
                       @JsonProperty("requestTimeoutMillis") Long requestTimeoutMillis,
                       @JsonProperty("idleTimeoutMillis") Long idleTimeoutMillis,
                       @JsonProperty("maxFrameLength") Integer maxFrameLength,
                       @JsonProperty("numRepositoryWorkers") Integer numRepositoryWorkers,
                       @JsonProperty("cacheSpec") String cacheSpec,
                       @JsonProperty("gracefulShutdownTimeout")
                               GracefulShutdownTimeout gracefulShutdownTimeout,
                       @JsonProperty("webAppEnabled") Boolean webAppEnabled,
                       @JsonProperty("webAppSessionTimeoutMillis") Long webAppSessionTimeoutMillis,
                       @JsonProperty("mirroringEnabled") Boolean mirroringEnabled,
                       @JsonProperty("numMirroringThreads") Integer numMirroringThreads,
                       @JsonProperty("maxNumFilesPerMirror") Integer maxNumFilesPerMirror,
                       @JsonProperty("maxNumBytesPerMirror") Long maxNumBytesPerMirror,
                       @JsonProperty("replication") ReplicationConfig replicationConfig,
                       @JsonProperty("securityEnabled") Boolean securityEnabled,
                       @JsonProperty("csrfTokenRequiredForThrift") Boolean csrfTokenRequiredForThrift,
                       @JsonProperty("accessLogFormat") String accessLogFormat,
                       @JsonProperty("administrators") Set<String> administrators) {

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
        this.cacheSpec = RepositoryCache.validateCacheSpec(firstNonNull(cacheSpec, DEFAULT_CACHE_SPEC));
        this.webAppEnabled = firstNonNull(webAppEnabled, true);
        this.webAppSessionTimeoutMillis = firstNonNull(webAppSessionTimeoutMillis,
                                                       DEFAULT_WEB_APP_SESSION_TIMEOUT_MILLIS);
        checkArgument(this.webAppSessionTimeoutMillis > 0,
                      "webAppSessionTimeoutMillis: %s (expected: > 0)", this.webAppSessionTimeoutMillis);
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
        this.securityEnabled = firstNonNull(securityEnabled, false);
        this.csrfTokenRequiredForThrift = firstNonNull(csrfTokenRequiredForThrift, true);
        this.accessLogFormat = accessLogFormat;
        this.administrators = administrators != null ? ImmutableSet.copyOf(administrators)
                                                     : ImmutableSet.of();
    }

    @JsonProperty
    File dataDir() {
        return dataDir;
    }

    @JsonProperty
    @JsonSerialize(contentUsing = ServerPortSerializer.class)
    List<ServerPort> ports() {
        return ports;
    }

    @Nullable
    @JsonProperty
    TlsConfig tls() {
        return tls;
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    Optional<Integer> numWorkers() {
        return Optional.ofNullable(numWorkers);
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    Optional<Integer> maxNumConnections() {
        return Optional.ofNullable(maxNumConnections);
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    Optional<Long> requestTimeoutMillis() {
        return Optional.ofNullable(requestTimeoutMillis);
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    Optional<Long> idleTimeoutMillis() {
        return Optional.ofNullable(idleTimeoutMillis);
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    Optional<Integer> maxFrameLength() {
        return Optional.ofNullable(maxFrameLength);
    }

    @JsonProperty
    int numRepositoryWorkers() {
        return numRepositoryWorkers;
    }

    @JsonProperty
    String cacheSpec() {
        return cacheSpec;
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    Optional<GracefulShutdownTimeout> gracefulShutdownTimeout() {
        return Optional.ofNullable(gracefulShutdownTimeout);
    }

    @JsonProperty
    boolean isWebAppEnabled() {
        return webAppEnabled;
    }

    @JsonProperty
    long webAppSessionTimeoutMillis() {
        return webAppSessionTimeoutMillis;
    }

    @JsonProperty
    boolean isMirroringEnabled() {
        return mirroringEnabled;
    }

    @JsonProperty
    int numMirroringThreads() {
        return numMirroringThreads;
    }

    @JsonProperty
    int maxNumFilesPerMirror() {
        return maxNumFilesPerMirror;
    }

    @JsonProperty
    long maxNumBytesPerMirror() {
        return maxNumBytesPerMirror;
    }

    @JsonProperty("replication")
    ReplicationConfig replicationConfig() {
        return replicationConfig;
    }

    @JsonProperty
    boolean isSecurityEnabled() {
        return securityEnabled;
    }

    @JsonProperty
    boolean isCsrfTokenRequiredForThrift() {
        return csrfTokenRequiredForThrift;
    }

    @JsonProperty
    String accessLogFormat() {
        return accessLogFormat;
    }

    Set<String> administrators() {
        return administrators;
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
            final String proto = Ascii.toLowerCase(value.protocol().uriText());
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
            gen.writeStringField("protocol", proto);
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

            final JsonNode proto = root.get("protocol");
            if (proto == null || proto.getNodeType() != JsonNodeType.STRING) {
                return fail(ctx, root);
            }

            final String hostVal = host.textValue();
            final int portVal = port.intValue();

            final InetSocketAddress localAddressVal;
            if ("*".equals(hostVal)) {
                localAddressVal = new InetSocketAddress(portVal);
            } else {
                localAddressVal = new InetSocketAddress(hostVal, portVal);
            }

            final SessionProtocol protoVal = SessionProtocol.of(Ascii.toUpperCase(proto.textValue()));

            return new ServerPort(localAddressVal, protoVal);
        }

        private static ServerPort fail(DeserializationContext ctx, JsonNode root) throws JsonMappingException {
            ctx.reportInputMismatch(ServerPort.class, "invalid server port information: %s", root);
            throw new Error(); // Should never reach here.
        }
    }

    static final class OptionalConverter extends StdConverter<Optional<?>, Object> {
        @Override
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Object convert(Optional<?> value) {
            return value.orElse(null);
        }
    }
}
