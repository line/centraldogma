/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.centraldogma.client.armeria.xds.configsource;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.GenericSecretSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsResourceReader;
import com.linecorp.armeria.xds.XdsType;
import com.linecorp.armeria.xds.configsource.InterestedResources;
import com.linecorp.armeria.xds.configsource.SotwConfigSourceSubscriptionFactory;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.stream.RefCountedStream;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.client.WatcherRequest;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.internal.CsrfToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.xds.v1.CentralDogmaConfigSource;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.netty.util.concurrent.EventExecutor;

final class CentralDogmaSotwConfigSourceSubscriptionFactory
        implements SotwConfigSourceSubscriptionFactory {

    static final String NAME = "centraldogma.config_source";
    static final String TYPE_URL =
            "type.googleapis.com/com.linecorp.centraldogma.xds.v1.CentralDogmaConfigSource";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> typeUrls() {
        return ImmutableList.of(TYPE_URL);
    }

    @Override
    public SnapshotStream<DiscoveryResponse> create(ConfigSource configSource,
                                                    FactoryContext factoryContext,
                                                    SnapshotStream<InterestedResources> interestedResources) {
        final CentralDogmaConfigSource cdConfig =
                factoryContext.validator().unpack(
                        configSource.getCustomConfigSource().getTypedConfig(),
                        CentralDogmaConfigSource.class);
        checkArgument(!cdConfig.getClusterName().isEmpty(),
                      "CentralDogmaConfigSource.cluster_name must not be empty");
        final SnapshotStream<ClusterSnapshot> clusterStream =
                factoryContext.clusterStream(cdConfig.getClusterName());
        final EventExecutor eventLoop = factoryContext.eventLoop();
        final SnapshotStream<String> accessTokenStream;
        if (cdConfig.hasBearerTokenCredential()) {
            final SdsSecretConfig tokenSecret = cdConfig.getBearerTokenCredential().getTokenSecret();
            accessTokenStream = factoryContext.genericSecretStream(tokenSecret)
                                              .map(GenericSecretSnapshot::credential);
        } else {
            accessTokenStream = SnapshotStream.just(CsrfToken.ANONYMOUS);
        }
        final SnapshotStream<CentralDogma> cdStream =
                SnapshotStream.combineLatest(clusterStream, accessTokenStream, Map::entry)
                              .switchMapEager(entry -> {
                                  return new CentralDogmaClientStream(entry.getKey(), entry.getValue(),
                                                                      factoryContext);
                              });
        return cdStream.switchMapEager(centralDogma -> {
            final Map<XdsType, InterestedResources> accumulated = new EnumMap<>(XdsType.class);
            final Function<String, SnapshotStream<JsonNode>> watcherCache =
                    SnapshotStream.caching(
                            name -> new WatcherStream(centralDogma, ResourcePath.parse(name))
                                    .rescheduleEventsOn(eventLoop));
            return interestedResources
                    .map(interest -> {
                        accumulated.put(interest.type(), interest);
                        return ImmutableMap.copyOf(accumulated);
                    })
                    .switchMapEager(interests ->
                                            new Interest2ResponsesStream(interests, watcherCache));
        });
    }

    private static final class Interest2ResponsesStream extends RefCountedStream<DiscoveryResponse> {

        private final Map<XdsType, InterestedResources> interests;
        private final Function<String, SnapshotStream<JsonNode>> watcherCache;

        Interest2ResponsesStream(Map<XdsType, InterestedResources> interests,
                                 Function<String, SnapshotStream<JsonNode>> watcherCache) {
            this.interests = interests;
            this.watcherCache = watcherCache;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<DiscoveryResponse> watcher) {
            final List<Subscription> subs = new ArrayList<>();
            for (InterestedResources interested : interests.values()) {
                final String typeUrl = interested.type().typeUrl();
                final List<SnapshotStream<Any>> streams =
                        interested.resourceNames().stream()
                                  .map(name -> watcherCache.apply(name)
                                                           .map(jsonNode -> toAny(jsonNode, typeUrl)))
                                  .collect(ImmutableList.toImmutableList());
                subs.add(SnapshotStream.combineNLatest(streams)
                                       .map(resources -> DiscoveryResponse.newBuilder()
                                                                          .setTypeUrl(typeUrl)
                                                                          .addAllResources(resources)
                                                                          .build())
                                       .subscribe(this::emit));
            }
            return () -> {
                subs.forEach(Subscription::close);
                subs.clear();
            };
        }

        private static Any toAny(JsonNode jsonNode, String typeUrl) {
            ((ObjectNode) jsonNode).put("@type", typeUrl);
            return XdsResourceReader.from(jsonNode.toString(), Any.class);
        }
    }

    private static final class CentralDogmaClientStream extends RefCountedStream<CentralDogma> {

        private final ClusterSnapshot clusterSnapshot;
        private final String accessToken;
        private final FactoryContext factoryContext;

        CentralDogmaClientStream(ClusterSnapshot clusterSnapshot, String accessToken,
                                 FactoryContext factoryContext) {
            this.clusterSnapshot = clusterSnapshot;
            this.accessToken = accessToken;
            this.factoryContext = factoryContext;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<CentralDogma> watcher) {
            final CentralDogma centralDogma = PreprocessorBasedCentralDogma.of(
                    clusterSnapshot.preprocessor(), accessToken, factoryContext.meterRegistry());
            emit(centralDogma, null);
            return () -> {
                try {
                    centralDogma.close();
                } catch (Exception e) {
                    Exceptions.throwUnsafely(e);
                }
            };
        }
    }

    private static final class WatcherStream extends RefCountedStream<JsonNode> {

        private final CentralDogma centralDogma;
        private final ResourcePath resourcePath;

        WatcherStream(CentralDogma centralDogma, ResourcePath resourcePath) {
            this.centralDogma = centralDogma;
            this.resourcePath = resourcePath;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<JsonNode> watcher) {
            final Watcher<?> cdWatcher;
            if (resourcePath.isFtl()) {
                // .ftl files are stored as TEXT, so we use Query.ofText and parse after rendering.
                final WatcherRequest<String> textRequest =
                        centralDogma.forRepo(resourcePath.project(), resourcePath.repo())
                                    .watcher(Query.ofText(resourcePath.path()));
                textRequest.renderTemplate(true);
                if (resourcePath.profile() != null) {
                    textRequest.renderTemplate(resourcePath.profile());
                }
                final Watcher<String> textWatcher = textRequest.start();
                textWatcher.watch((revision, text) -> emitText(text));
                cdWatcher = textWatcher;
            } else {
                final WatcherRequest<JsonNode> jsonRequest =
                        centralDogma.forRepo(resourcePath.project(), resourcePath.repo())
                                    .watcher(resourcePath.query());
                final Watcher<JsonNode> jsonWatcher = jsonRequest.start();
                jsonWatcher.watch((revision, jsonNode) -> emit(jsonNode, null));
                cdWatcher = jsonWatcher;
            }
            return cdWatcher::close;
        }

        private void emitText(String text) {
            try {
                emit(Jackson.readTree(resourcePath.basePath(), text), null);
            } catch (Exception e) {
                emit(null, e);
            }
        }
    }
}
