/*
 * Copyright 2026 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.transaction.CuratorOp;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ZKConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ZooKeeperClientConfigTest {

    private static final String LOG_PATH = "/logs";
    private static final int CHILD_COUNT = 67;
    private static final int CHILDREN_RESPONSE_SIZE = 88 + 14 * CHILD_COUNT;

    private TestingServer server;
    private CuratorFramework writer;

    @BeforeEach
    void setUp() throws Exception {
        server = new TestingServer();
        writer = newClient(new ZKClientConfig());
        assertThat(writer.blockUntilConnected(10, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (writer != null) {
            writer.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 64, 65, 66, 67, 68, 99, 100, 101, 1024 })
    void enforcesChildrenResponsePacketLimit(int childCount) throws Exception {
        final List<String> children = createChildren(childCount);
        for (int byteOffset = -1; byteOffset <= 1; byteOffset++) {
            // Disable the minimum allowance and headroom to test the helper's exact calculation.
            final ZKClientConfig config = clientConfig(childCount, 0, 0);
            final int calculatedLimit = config.getInt(ZKConfig.JUTE_MAXBUFFER, -1);
            assertThat(calculatedLimit).isPositive();
            config.setProperty(ZKConfig.JUTE_MAXBUFFER, Integer.toString(calculatedLimit + byteOffset));
            try (CuratorFramework client = newClient(config)) {
                assertThat(client.blockUntilConnected(10, TimeUnit.SECONDS)).isTrue();
                // A small response still works, even though listing the children may exceed the limit.
                assertThat(client.checkExists().forPath(LOG_PATH).getNumChildren()).isEqualTo(childCount);

                if (byteOffset < 0) {
                    assertThatThrownBy(() -> client.getChildren().forPath(LOG_PATH))
                            .isInstanceOf(ConnectionLossException.class);
                } else {
                    assertThat(client.getChildren().forPath(LOG_PATH))
                            .containsExactlyInAnyOrderElementsOf(children);
                }
            }
        }
    }

    @Test
    void changingConfigurationOnlyAffectsNewClients() throws Exception {
        final List<String> children = createChildren();
        final ZKClientConfig config = clientConfig(CHILDREN_RESPONSE_SIZE - 1);
        try (CuratorFramework existingClient = newClient(config)) {
            assertThat(existingClient.blockUntilConnected(10, TimeUnit.SECONDS)).isTrue();

            config.setProperty(ZKConfig.JUTE_MAXBUFFER, Integer.toString(CHILDREN_RESPONSE_SIZE));
            assertThatThrownBy(() -> existingClient.getChildren().forPath(LOG_PATH))
                    .isInstanceOf(ConnectionLossException.class);

            try (CuratorFramework newClient = newClient(config)) {
                assertThat(newClient.blockUntilConnected(10, TimeUnit.SECONDS)).isTrue();
                assertThat(newClient.getChildren().forPath(LOG_PATH))
                        .containsExactlyInAnyOrderElementsOf(children);
            }
        }
    }

    @Test
    void keepsDefaultPacketLimitForSmallThreshold() {
        final ZKClientConfig config = clientConfig(1024, 10);
        assertThat(config.getInt(ZKConfig.JUTE_MAXBUFFER, -1))
                .isEqualTo(ZKClientConfig.CLIENT_MAX_PACKET_LENGTH_DEFAULT);
    }

    @Test
    void leavesPacketLimitUnsetWhenThresholdIsDisabled() {
        assertThat(clientConfig(Integer.MAX_VALUE, 10).getProperty(ZKConfig.JUTE_MAXBUFFER)).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = { 1024, 4194304 })
    void preservesExplicitPacketLimit(int maxPacketLength) {
        final ZKClientConfig config = clientConfig(maxPacketLength);
        ZooKeeperCommandExecutor.configureClientMaxBuffer(
                config, 100000, ZKClientConfig.CLIENT_MAX_PACKET_LENGTH_DEFAULT, 10);
        assertThat(config.getInt(ZKConfig.JUTE_MAXBUFFER, -1)).isEqualTo(maxPacketLength);
    }

    @Test
    void rejectsThresholdWhoseResponseCannotFitInPacket() {
        assertThatThrownBy(() -> clientConfig(Integer.MAX_VALUE - 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumLogCount is too large");
    }

    @Test
    void sizesClientsIndependentlyWithoutChangingSystemProperties() {
        final String systemProperty = System.getProperty(ZKConfig.JUTE_MAXBUFFER);
        final ZKClientConfig first = clientConfig(100000, 10);
        final ZKClientConfig second = clientConfig(200000, 10);
        assertThat(second.getInt(ZKConfig.JUTE_MAXBUFFER, -1))
                .isGreaterThan(first.getInt(ZKConfig.JUTE_MAXBUFFER, -1));
        assertThat(System.getProperty(ZKConfig.JUTE_MAXBUFFER)).isEqualTo(systemProperty);
    }

    private List<String> createChildren() throws Exception {
        return createChildren(CHILD_COUNT);
    }

    private List<String> createChildren(int count) throws Exception {
        writer.create().forPath(LOG_PATH);
        final List<String> children = new ArrayList<>(count);
        final List<CuratorOp> operations = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final String child = String.format("%010d", i);
            operations.add(writer.transactionOp().create().forPath(LOG_PATH + '/' + child, new byte[0]));
            children.add(child);
        }
        writer.transaction().forOperations(operations);
        return children;
    }

    private CuratorFramework newClient(ZKClientConfig config) {
        final CuratorFramework client = CuratorFrameworkFactory.builder()
                                                              .connectString(server.getConnectString())
                                                              .sessionTimeoutMs(10000)
                                                              .connectionTimeoutMs(10000)
                                                              // Fail promptly when the packet is rejected.
                                                              .retryPolicy(new RetryNTimes(0, 0))
                                                              .zkClientConfig(config)
                                                              .build();
        client.start();
        return client;
    }

    private static ZKClientConfig clientConfig(int maxPacketLength) {
        final ZKClientConfig config = new ZKClientConfig();
        config.setProperty(ZKConfig.JUTE_MAXBUFFER, Integer.toString(maxPacketLength));
        return config;
    }

    private static ZKClientConfig clientConfig(int maximumLogCount, int headroomPercentage) {
        return clientConfig(maximumLogCount, ZKClientConfig.CLIENT_MAX_PACKET_LENGTH_DEFAULT,
                            headroomPercentage);
    }

    private static ZKClientConfig clientConfig(int maximumLogCount, int minimumPacketLength,
                                              int headroomPercentage) {
        final ZKClientConfig config = new ZKClientConfig();
        config.setProperty(ZKConfig.JUTE_MAXBUFFER, null);
        ZooKeeperCommandExecutor.configureClientMaxBuffer(config, maximumLogCount,
                                                         minimumPacketLength, headroomPercentage);
        return config;
    }
}
