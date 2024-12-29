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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_MAX_REMOVED_REPOSITORY_AGE_MILLIS;
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_NUM_REPOSITORY_WORKERS;
import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_REPOSITORY_CACHE_SPEC;
import static com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache.validateCacheSpec;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.utils.VisibleForTesting;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.util.StdConverter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ClientAddressSource;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.auth.AuthConfig;
import com.linecorp.centraldogma.server.auth.AuthConfigSpec;
import com.linecorp.centraldogma.server.plugin.PluginConfig;
import com.linecorp.centraldogma.server.plugin.PluginConfigDeserializer;

import io.netty.util.NetUtil;

/**
 * {@link CentralDogma} server configuration.
 */
public final class CentralDogmaConfig extends AbstractCentralDogmaConfig {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaConfig.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[a-z0-9_-]+$");

    private static final Map<String, ConfigValueConverter> CONFIG_VALUE_CONVERTERS;

    static {
        final SimpleModule module = new SimpleModule();
        module.addDeserializer(PluginConfig.class, new PluginConfigDeserializer());
        // Use different ObjectMapper to avoid infinite recursion.
        objectMapper.registerModule(module);

        final ArrayList<ConfigValueConverter> configValueConverters = new ArrayList<>();
        Streams.stream(ServiceLoader.load(ConfigValueConverter.class)).forEach(configValueConverters::add);
        configValueConverters.add(DefaultConfigValueConverter.INSTANCE);
        final ImmutableMap.Builder<String, ConfigValueConverter> builder = ImmutableMap.builder();
        for (ConfigValueConverter configValueConverter : configValueConverters) {
            if (configValueConverter.supportedPrefixes().isEmpty()) {
                continue;
            }
            boolean addConverter = true;
            for (String supportedPrefix : configValueConverter.supportedPrefixes()) {
                if (!PREFIX_PATTERN.matcher(supportedPrefix).matches()) {
                    logger.warn("{} isn't used because it has an invalid prefix: {}. (expected: {})",
                                configValueConverter, supportedPrefix, PREFIX_PATTERN.pattern());
                    addConverter = false;
                    break;
                }
            }
            if (addConverter) {
                configValueConverter.supportedPrefixes()
                                    .forEach(prefix -> builder.put(prefix, configValueConverter));
            }
        }
        CONFIG_VALUE_CONVERTERS = ImmutableMap.copyOf(builder.buildOrThrow());

        final StringBuilder sb = new StringBuilder();
        sb.append('{');
        CONFIG_VALUE_CONVERTERS.entrySet().stream().sorted(Entry.comparingByKey()).forEach(
                entry -> sb.append(entry.getKey())
                           .append('=')
                           .append(entry.getValue().getClass().getName()).append(", "));
        sb.setLength(sb.length() - 2);
        sb.append('}');

        logger.debug("Available {}s: {}", ConfigValueConverter.class.getName(), sb);
    }

    /**
     * Converts the specified {@code value} using {@link ConfigValueConverter} if the specified {@code value}
     * starts with a prefix followed by a colon {@code ':'}.
     */
    @Nullable
    public static String convertValue(@Nullable String value, String propertyName) {
        if (value == null) {
            return null;
        }

        final int index = value.indexOf(':');
        if (index <= 0) {
            // no prefix or starts with ':'.
            return value;
        }

        final String prefix = value.substring(0, index);
        if (!PREFIX_PATTERN.matcher(prefix).matches()) {
            // Not a prefix.
            return value;
        }

        final String rest = value.substring(index + 1);

        final ConfigValueConverter converter = CONFIG_VALUE_CONVERTERS.get(prefix);
        if (converter != null) {
            return converter.convert(prefix, rest);
        }
        logger.warn("No {} found for {}. prefix: {}",
                    ConfigValueConverter.class.getSimpleName(), propertyName, prefix);
        return value;
    }

    /**
     * Loads the configuration from the specified {@link File}.
     */
    public static CentralDogmaConfig load(File configFile) throws JsonMappingException, JsonParseException {
        requireNonNull(configFile, "configFile");
        try {
            return objectMapper.readValue(configFile, CentralDogmaConfig.class);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    /**
     * Loads the configuration from the specified JSON string.
     */
    @VisibleForTesting
    public static CentralDogmaConfig load(String json) throws JsonMappingException, JsonParseException {
        requireNonNull(json, "json");
        try {
            return objectMapper.readValue(json, CentralDogmaConfig.class);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

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
    @Nullable
    private final List<String> trustedProxyAddresses;
    @Nullable
    private final List<String> clientAddressSources;

    private final Predicate<InetAddress> trustedProxyAddressPredicate;
    private final List<ClientAddressSource> clientAddressSourceList;

    // Repository
    private final Integer numRepositoryWorkers;
    private final long maxRemovedRepositoryAgeMillis;

    // Cache
    private final String repositoryCacheSpec;

    // Web dashboard
    private final boolean webAppEnabled;

    @Nullable
    private final String webAppTitle;

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

    @Nullable
    private final QuotaConfig writeQuotaPerRepository;

    @Nullable
    private final CorsConfig corsConfig;

    private final List<PluginConfig> pluginConfigs;

    @Nullable
    private final ManagementConfig managementConfig;

    @Nullable
    private final ZoneConfig zoneConfig;

    CentralDogmaConfig(
            @JsonProperty(value = "dataDir", required = true) File dataDir,
            @JsonProperty(value = "ports", required = true)
            @JsonDeserialize(contentUsing = ServerPortDeserializer.class)
            List<ServerPort> ports,
            @JsonProperty("tls") @Nullable TlsConfig tls,
            @JsonProperty("trustedProxyAddresses") @Nullable List<String> trustedProxyAddresses,
            @JsonProperty("clientAddressSources") @Nullable List<String> clientAddressSources,
            @JsonProperty("numWorkers") @Nullable Integer numWorkers,
            @JsonProperty("maxNumConnections") @Nullable Integer maxNumConnections,
            @JsonProperty("requestTimeoutMillis") @Nullable Long requestTimeoutMillis,
            @JsonProperty("idleTimeoutMillis") @Nullable Long idleTimeoutMillis,
            @JsonProperty("maxFrameLength") @Nullable Integer maxFrameLength,
            @JsonProperty("numRepositoryWorkers") @Nullable Integer numRepositoryWorkers,
            @JsonProperty("repositoryCacheSpec") @Nullable String repositoryCacheSpec,
            @JsonProperty("maxRemovedRepositoryAgeMillis") @Nullable Long maxRemovedRepositoryAgeMillis,
            @JsonProperty("gracefulShutdownTimeout") @Nullable GracefulShutdownTimeout gracefulShutdownTimeout,
            @JsonProperty("webAppEnabled") @Nullable Boolean webAppEnabled,
            @JsonProperty("webAppTitle") @Nullable String webAppTitle,
            @JsonProperty("replication") ReplicationConfig replicationConfig,
            @JsonProperty("csrfTokenRequiredForThrift") @Nullable Boolean csrfTokenRequiredForThrift,
            @JsonProperty("accessLogFormat") @Nullable String accessLogFormat,
            @JsonProperty("authentication") @Nullable AuthConfig authConfig,
            @JsonProperty("writeQuotaPerRepository") @Nullable QuotaConfig writeQuotaPerRepository,
            @JsonProperty("cors") @Nullable CorsConfig corsConfig,
            @JsonProperty("pluginConfigs") @Nullable List<PluginConfig> pluginConfigs,
            @JsonProperty("management") @Nullable ManagementConfig managementConfig,
            @JsonProperty("zone") @Nullable ZoneConfig zoneConfig) {

        super(pluginConfigs);

        this.dataDir = requireNonNull(dataDir, "dataDir");
        this.ports = ImmutableList.copyOf(requireNonNull(ports, "ports"));
        checkArgument(!ports.isEmpty(), "ports must have at least one port.");
        this.tls = tls;
        this.trustedProxyAddresses = trustedProxyAddresses;
        this.clientAddressSources = clientAddressSources;

        this.numWorkers = numWorkers;

        this.maxNumConnections = maxNumConnections;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.maxFrameLength = maxFrameLength;
        this.numRepositoryWorkers = firstNonNull(numRepositoryWorkers, DEFAULT_NUM_REPOSITORY_WORKERS);
        checkArgument(this.numRepositoryWorkers > 0,
                      "numRepositoryWorkers: %s (expected: > 0)", this.numRepositoryWorkers);
        this.maxRemovedRepositoryAgeMillis = firstNonNull(maxRemovedRepositoryAgeMillis,
                                                          DEFAULT_MAX_REMOVED_REPOSITORY_AGE_MILLIS);
        checkArgument(this.maxRemovedRepositoryAgeMillis >= 0,
                      "maxRemovedRepositoryAgeMillis: %s (expected: >= 0)", this.maxRemovedRepositoryAgeMillis);
        this.repositoryCacheSpec = validateCacheSpec(
                firstNonNull(repositoryCacheSpec, DEFAULT_REPOSITORY_CACHE_SPEC));

        this.webAppEnabled = firstNonNull(webAppEnabled, true);
        this.webAppTitle = webAppTitle;
        this.gracefulShutdownTimeout = gracefulShutdownTimeout;
        this.replicationConfig = firstNonNull(replicationConfig, ReplicationConfig.NONE);
        this.csrfTokenRequiredForThrift = firstNonNull(csrfTokenRequiredForThrift, true);
        this.accessLogFormat = accessLogFormat;

        this.authConfig = authConfig;

        final boolean hasTrustedProxyAddrCfg =
                trustedProxyAddresses != null && !trustedProxyAddresses.isEmpty();
        trustedProxyAddressPredicate =
                hasTrustedProxyAddrCfg ? toTrustedProxyAddressPredicate(trustedProxyAddresses)
                                       : addr -> false;
        clientAddressSourceList =
                toClientAddressSourceList(clientAddressSources, hasTrustedProxyAddrCfg,
                                          ports.stream().anyMatch(ServerPort::hasProxyProtocol));

        this.writeQuotaPerRepository = writeQuotaPerRepository;
        this.corsConfig = corsConfig;
        this.pluginConfigs = firstNonNull(pluginConfigs, ImmutableList.of());
        this.managementConfig = managementConfig;
        this.zoneConfig = zoneConfig;
    }

    @JsonProperty
    @Override
    public File dataDir() {
        return dataDir;
    }

    @JsonProperty
    @JsonSerialize(contentUsing = ServerPortSerializer.class)
    @Override
    public List<ServerPort> ports() {
        return ports;
    }

    @Nullable
    @JsonProperty
    @Override
    public TlsConfigSpec tls() {
        return tls;
    }

    @Nullable
    @JsonProperty
    @Override
    public List<String> trustedProxyAddresses() {
        return trustedProxyAddresses;
    }

    @Nullable
    @JsonProperty
    @Override
    public List<String> clientAddressSources() {
        return clientAddressSources;
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    @Override
    public Optional<Integer> numWorkers() {
        return Optional.ofNullable(numWorkers);
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    @Override
    public Optional<Integer> maxNumConnections() {
        return Optional.ofNullable(maxNumConnections);
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    @Override
    public Optional<Long> requestTimeoutMillis() {
        return Optional.ofNullable(requestTimeoutMillis);
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    @Override
    public Optional<Long> idleTimeoutMillis() {
        return Optional.ofNullable(idleTimeoutMillis);
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    @Override
    public Optional<Integer> maxFrameLength() {
        return Optional.ofNullable(maxFrameLength);
    }

    @JsonProperty
    @Override
    public int numRepositoryWorkers() {
        return numRepositoryWorkers;
    }

    @JsonProperty
    @Override
    public long maxRemovedRepositoryAgeMillis() {
        return maxRemovedRepositoryAgeMillis;
    }

    /**
     * Replaced to {@link CentralDogmaConfig#repositoryCacheSpec() }.
     * @deprecated This cache spec is replaced to {@link CentralDogmaConfig#repositoryCacheSpec() }.
     */
    @JsonProperty
    @Deprecated
    public String cacheSpec() {
        return repositoryCacheSpec;
    }

    @JsonProperty
    @Override
    public String repositoryCacheSpec() {
        return repositoryCacheSpec;
    }

    @JsonProperty
    @JsonSerialize(converter = OptionalConverter.class)
    @Override
    public Optional<GracefulShutdownTimeoutSpec> gracefulShutdownTimeout() {
        return Optional.ofNullable(gracefulShutdownTimeout);
    }

    @JsonProperty
    @Override
    public boolean isWebAppEnabled() {
        return webAppEnabled;
    }

    @Nullable
    @JsonProperty("webAppTitle")
    @Override
    public String webAppTitle() {
        return webAppTitle;
    }

    @JsonProperty("replication")
    @Override
    public ReplicationConfig replicationConfig() {
        return replicationConfig;
    }

    @JsonProperty
    @Override
    public boolean isCsrfTokenRequiredForThrift() {
        return csrfTokenRequiredForThrift;
    }

    @JsonProperty
    @Nullable
    @Override
    public String accessLogFormat() {
        return accessLogFormat;
    }

    @Nullable
    @JsonProperty("authentication")
    @Override
    public AuthConfigSpec authConfig() {
        return authConfig;
    }

    @Nullable
    @JsonProperty("writeQuotaPerRepository")
    @Override
    public QuotaConfigSpec writeQuotaPerRepository() {
        return writeQuotaPerRepository;
    }

    @Nullable
    @JsonProperty("cors")
    @Override
    public CorsConfigSpec corsConfig() {
        return corsConfig;
    }

    @JsonProperty("pluginConfigs")
    @Override
    public List<PluginConfig> pluginConfigs() {
        return pluginConfigs;
    }

    @Nullable
    @JsonProperty("management")
    @Override
    public ManagementConfigSpec managementConfig() {
        return managementConfig;
    }

    @Nullable
    @JsonProperty("zone")
    @Override
    public ZoneConfig zone() {
        return zoneConfig;
    }

    @Override
    public String toString() {
        try {
            return Jackson.writeValueAsPrettyString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Predicate<InetAddress> trustedProxyAddressPredicate() {
        return trustedProxyAddressPredicate;
    }

    @Override
    public List<ClientAddressSource> clientAddressSourceList() {
        return clientAddressSourceList;
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
