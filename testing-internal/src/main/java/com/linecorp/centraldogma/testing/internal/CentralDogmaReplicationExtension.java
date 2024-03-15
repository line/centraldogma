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

package com.linecorp.centraldogma.testing.internal;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.GracefulShutdownTimeout;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.server.ZooKeeperServerConfig;
import com.linecorp.centraldogma.server.auth.AuthProviderFactory;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.AbstractAllOrEachExtension;

import io.netty.util.NetUtil;

/**
 * A JUnit {@link Extension} that starts an embedded Central Dogma cluster based on ZooKeeper.
 */
public class CentralDogmaReplicationExtension extends AbstractAllOrEachExtension {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaReplicationExtension.class);

    private static final int MAX_RETRIES = 10;

    private final TemporaryFolder tmpDir = new TemporaryFolder();
    private final AuthProviderFactory factory = new TestAuthProviderFactory();

    private final int numReplicas;
    private final boolean useTls;
    @Nullable
    private List<CentralDogmaRuleDelegate> dogmaCluster;

    public CentralDogmaReplicationExtension(int numReplicas) {
        this(numReplicas, false);
    }

    public CentralDogmaReplicationExtension(int numReplicas, boolean useTls) {
        this.numReplicas = numReplicas;
        this.useTls = useTls;
    }

    public List<CentralDogmaRuleDelegate> servers() {
        if (dogmaCluster == null) {
            throw new IllegalStateException("Central Dogma cluster is not started yet");
        }
        return dogmaCluster;
    }

    private List<CentralDogmaRuleDelegate> newDogmaCluster(int numReplicas) throws IOException {
        final ImmutableMap.Builder<Integer, ZooKeeperServerConfig> builder =
                ImmutableMap.builderWithExpectedSize(numReplicas);
        final int[] unusedPorts = TestUtil.unusedTcpPorts(numReplicas * 4);
        for (int i = 0; i < numReplicas; i++) {
            final int portIndex = i * 4;
            final int zkQuorumPort = unusedPorts[portIndex];
            final int zkElectionPort = unusedPorts[portIndex + 1];
            final int zkClientPort = unusedPorts[portIndex + 2];

            builder.put(i + 1, new ZooKeeperServerConfig("127.0.0.1", zkQuorumPort, zkElectionPort,
                                                         zkClientPort, /* groupId */ null, /* weight */ 1));
        }

        final Map<Integer, ZooKeeperServerConfig> zooKeeperServers = builder.build();

        return zooKeeperServers.keySet().stream().map(serverId -> {
            final int dogmaPort = unusedPorts[(serverId - 1) * 4 + 3];
            return new CentralDogmaRuleDelegate(useTls) {

                @Override
                protected void configure(CentralDogmaBuilder builder) {
                    builder.port(new InetSocketAddress(NetUtil.LOCALHOST4, dogmaPort), SessionProtocol.HTTP)
                           .administrators(TestAuthMessageUtil.USERNAME)
                           .authProviderFactory(factory)
                           .mirroringEnabled(false)
                           .gracefulShutdownTimeout(new GracefulShutdownTimeout(0, 0))
                           .replication(new ZooKeeperReplicationConfig(serverId, zooKeeperServers));
                }

                @Override
                protected String accessToken() {
                    // TODO(ikhoon): Add an option to disable the authentication.
                    try {
                        final WebClient client = WebClient.builder("http://127.0.0.1:" + dogmaPort)
                                                          .decorator(LoggingClient.newDecorator())
                                                          .build();
                        final AggregatedHttpResponse response =
                                TestAuthMessageUtil.login(client,
                                                          TestAuthMessageUtil.USERNAME,
                                                          TestAuthMessageUtil.PASSWORD);

                        assertThat(response.status()).isEqualTo(HttpStatus.OK);
                        try {
                            return Jackson.readValue(response.content().array(), AccessToken.class)
                                          .accessToken();
                        } catch (JsonProcessingException e) {
                            throw new IllegalStateException("Failed to parse the access token", e);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to get the access token", e);
                    }
                }
            };
        }).collect(toImmutableList());
    }

    @Override
    protected void before(ExtensionContext context) throws Exception {
        tmpDir.create();
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                final List<CentralDogmaRuleDelegate> dogmaCluster = newDogmaCluster(numReplicas);
                dogmaCluster.stream()
                            .map(dogma -> {
                                try {
                                    return dogma.startAsync(tmpDir.newFolder().toFile());
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .collect(Collectors.toList())
                            .forEach(CompletableFuture::join);
                this.dogmaCluster = dogmaCluster;
                break;
            } catch (Exception ex) {
                if (i == 9) {
                    throw ex;
                }
            }
        }

        // Wait for the ZooKeeper cluster to be ready.
        Thread.sleep(500);
    }

    @Override
    protected void after(ExtensionContext context) throws Exception {
        final List<CentralDogmaRuleDelegate> dogmaCluster = this.dogmaCluster;
        if (dogmaCluster != null) {
            final List<CompletableFuture<Void>> futures =
                    dogmaCluster.stream()
                                .map(CentralDogmaRuleDelegate::stopAsync)
                                .collect(Collectors.toList());
            CompletableFutures.allAsList(futures).whenComplete((unused1, unused2) -> {
                try {
                    tmpDir.delete();
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            });
        }
    }

    public void start() {
        if (dogmaCluster == null) {
            throw new IllegalStateException("Central Dogma cluster is not created yet");
        }

        dogmaCluster.stream().map(CentralDogmaRuleDelegate::dogma)
                    .map(CentralDogma::start)
                    .collect(Collectors.toList())
                    .forEach(CompletableFuture::join);
    }

    public void stop() {
        if (dogmaCluster == null) {
            throw new IllegalStateException("Central Dogma cluster is not started yet");
        }

        dogmaCluster.stream().map(CentralDogmaRuleDelegate::dogma)
                    .map(CentralDogma::stop)
                    .collect(Collectors.toList())
                    .forEach(CompletableFuture::join);
    }
}
