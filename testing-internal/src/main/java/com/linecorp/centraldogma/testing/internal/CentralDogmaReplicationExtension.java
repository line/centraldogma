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
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.google.common.collect.ImmutableMap;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.GracefulShutdownTimeout;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.server.ZooKeeperServerConfig;
import com.linecorp.centraldogma.server.auth.AuthProviderFactory;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.AbstractAllOrEachExtension;

import io.netty.util.NetUtil;

/**
 * A JUnit {@link Extension} that starts an embedded Central Dogma cluster based on ZooKeeper.
 */
public class CentralDogmaReplicationExtension extends AbstractAllOrEachExtension {

    private static final int MAX_RETRIES = 16;

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

        final AtomicReference<String> accessToken = new AtomicReference<>();
        return zooKeeperServers.keySet().stream().map(serverId -> {
            final int dogmaPort = unusedPorts[(serverId - 1) * 4 + 3];
            return new CentralDogmaRuleDelegate(useTls) {

                @Override
                protected void configure(CentralDogmaBuilder builder) {
                    builder.port(new InetSocketAddress(NetUtil.LOCALHOST4, dogmaPort), SessionProtocol.HTTP)
                           .systemAdministrators(TestAuthMessageUtil.USERNAME)
                           .authProviderFactory(factory)
                           .gracefulShutdownTimeout(new GracefulShutdownTimeout(0, 0))
                           .replication(new ZooKeeperReplicationConfig(serverId, zooKeeperServers));
                    configureEach(serverId, builder);
                    final boolean isMirrorConfigured =
                            builder.pluginConfigs()
                                   .stream()
                                   .anyMatch(pluginCfg -> pluginCfg instanceof MirroringServicePluginConfig);
                    if (!isMirrorConfigured) {
                        // Disable the mirroring service when it is not explicitly configured.
                        builder.pluginConfigs(new MirroringServicePluginConfig(false));
                    }
                }

                @Override
                protected String accessToken() {
                    // TODO(ikhoon): Add an option to disable the authentication.
                    if (accessToken.get() == null) {
                        final WebClient client = WebClient.builder("http://127.0.0.1:" + dogmaPort)
                                                          .decorator(LoggingClient.newDecorator())
                                                          .build();
                        accessToken.set(getAccessToken(client, TestAuthMessageUtil.USERNAME,
                                                       TestAuthMessageUtil.PASSWORD, true));
                    }
                    return accessToken.get();
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
            }).join();
        }
    }

    /**
     * Override this method to configure each server of the Central Dogma cluster.
     * @param serverId the ID of the server that starts from {@code 1} to {@link #numReplicas}
     */
    protected void configureEach(int serverId, CentralDogmaBuilder builder) {}

    public void start() throws InterruptedException {
        if (dogmaCluster == null) {
            throw new IllegalStateException("Central Dogma cluster is not created yet");
        }

        final List<Integer> ports = new ArrayList<>(12);
        final ZooKeeperReplicationConfig zkConfig =
                (ZooKeeperReplicationConfig) dogmaCluster.get(0).dogma().config().replicationConfig();
        for (ZooKeeperServerConfig config : zkConfig.servers().values()) {
            ports.add(config.clientPort());
            ports.add(config.electionPort());
            ports.add(config.quorumPort());
        }
        for (CentralDogmaRuleDelegate delegate : dogmaCluster) {
            for (ServerPort port : delegate.dogma().config().ports()) {
                ports.add(port.localAddress().getPort());
            }
        }

        // This logic won't completely prevent port duplication, but it is best efforts to reduce flakiness.
        boolean success = true;
        for (int i = 0; i < MAX_RETRIES * 2; i++) {
            success = true;
            for (Integer port : ports) {
                if (!isTcpPortAvailable(port)) {
                    success = false;
                    break;
                }
            }
            if (success) {
                break;
            } else if (i < MAX_RETRIES - 1) {
                Thread.sleep(1500);
            }
        }
        if (!success) {
            throw new IllegalStateException("Failed to find available ports for the Central Dogma cluster. " +
                                            "candidates: " + ports);
        }

        dogmaCluster.stream().map(CentralDogmaRuleDelegate::dogma)
                    .map(CentralDogma::start)
                    .collect(Collectors.toList())
                    .forEach(CompletableFuture::join);
        // Wait for the Central Dogma cluster to be ready.
        Thread.sleep(500);
    }

    private static boolean isTcpPortAvailable(int port) {
        try (ServerSocket ignored = new ServerSocket(port, 1,
                                                     InetAddress.getByName("127.0.0.1"))) {
            return true;
        } catch (IOException e) {
            // Port in use or unable to bind.
            return false;
        }
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
