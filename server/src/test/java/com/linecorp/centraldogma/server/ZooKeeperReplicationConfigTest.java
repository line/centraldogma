/*
 * Copyright 2020 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.internal.Jackson;

class ZooKeeperReplicationConfigTest {

    private static final String TEST_SECRET = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";

    @Test
    void testJsonConversion() throws Exception {
        final String json =
                '{' +
                "  \"method\": \"ZOOKEEPER\"," +
                "  \"serverId\": 1," +
                "  \"servers\": {" +
                "    \"1\": {" +
                "      \"host\": \"2\"," +
                "      \"quorumPort\": 3," +
                "      \"electionPort\": 4," +
                "      \"clientPort\": 5," +
                "      \"groupId\": null," +
                "      \"weight\": 1" +
                "    }," +
                "    \"6\": {" +
                "      \"host\": \"7\"," +
                "      \"quorumPort\": 8," +
                "      \"electionPort\": 9," +
                "      \"clientPort\": 10," +
                "      \"groupId\": null," +
                "      \"weight\": 1" +
                "    }" +
                "  }," +
                "  \"secret\": \"" + TEST_SECRET + "\"," +
                "  \"additionalProperties\":" +
                " { \"12\": \"13\", \"14\": \"15\", \"quorumListenOnAllIPs\": \"true\"  }," +
                "  \"timeoutMillis\": 16," +
                "  \"numWorkers\": 17," +
                "  \"maxLogCount\": 18," +
                "  \"minLogAgeMillis\": 19" +
                '}';

        final ReplicationConfig cfg = Jackson.readValue(json, ReplicationConfig.class);
        assertThat(cfg).isEqualTo(
                new ZooKeeperReplicationConfig(
                        1, ImmutableMap.of(
                                1, new ZooKeeperServerConfig("2", 3, 4, 5, null, 1),
                                6, new ZooKeeperServerConfig("7", 8, 9, 10, null, 1)),
                        TEST_SECRET, ImmutableMap.of("12", "13", "14", "15",
                                                     "quorumListenOnAllIPs", "true"),
                        16, 17, 18, 19));

        // secret is not serialized in JSON output.
        final String serialized = Jackson.writeValueAsString(cfg);
        assertThat(serialized).doesNotContain("secret");
    }

    @Test
    void testJsonConversionWithoutOptionalProperties() throws Exception {
        final ReplicationConfig defaultCfg =
                Jackson.readValue(
                        '{' +
                        "  \"method\": \"ZOOKEEPER\"," +
                        "  \"serverId\": 10," +
                        "  \"secret\": \"" + TEST_SECRET + "\"," +
                        "  \"servers\": {" +
                        "    \"10\": {" +
                        "      \"host\": \"foo\"," +
                        "      \"quorumPort\": 100," +
                        "      \"electionPort\": 101" +
                        "    }," +
                        "    \"11\": {" +
                        "      \"host\": \"bar\"," +
                        "      \"quorumPort\": 200," +
                        "      \"electionPort\": 201" +
                        "    }" +
                        "  }" +
                        '}',
                        ReplicationConfig.class);

        assertThat(defaultCfg).isEqualTo(
                new ZooKeeperReplicationConfig(
                        10, ImmutableMap
                        .of(10, new ZooKeeperServerConfig("foo", 100, 101,
                                                          0, /* groupId */ null, /* weight */ 1),
                            11, new ZooKeeperServerConfig("bar", 200, 201,
                                                          0, /* groupId */ null, /* weight */ 1)),
                        TEST_SECRET, null, null, null, null, null, null));
    }

    @Test
    void autoDetection() throws Exception {
        final ZooKeeperReplicationConfig cfg = Jackson.readValue(
                '{' +
                "  \"method\": \"ZOOKEEPER\"," +
                "  \"secret\": \"" + TEST_SECRET + "\"," +
                "  \"servers\": {" +
                "    \"1\": {" +
                "      \"host\": \"127.0.0.1\"," +
                "      \"quorumPort\": 100," +
                "      \"electionPort\": 101" +
                "    }," +
                "    \"2\": {" +
                "      \"host\": \"255.255.255.255\"," +
                "      \"quorumPort\": 200," +
                "      \"electionPort\": 201" +
                "    }" +
                "  }" +
                '}',
                ZooKeeperReplicationConfig.class);
        assertThat(cfg.serverId()).isEqualTo(1);
    }

    @Test
    void autoDetectionFailure_TwoMatches() {
        final String json =
                '{' +
                "  \"method\": \"ZOOKEEPER\"," +
                "  \"secret\": \"" + TEST_SECRET + "\"," +
                "  \"servers\": {" +
                "    \"1\": {" +
                "      \"host\": \"127.0.0.1\"," +
                "      \"quorumPort\": 100," +
                "      \"electionPort\": 101" +
                "    }," +
                "    \"2\": {" +
                "      \"host\": \"localhost\"," +
                "      \"quorumPort\": 200," +
                "      \"electionPort\": 201" +
                "    }" +
                "  }" +
                '}';

        assertThatThrownBy(() -> Jackson.readValue(json, ZooKeeperReplicationConfig.class))
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(cause -> {
                    assertThat(cause.getCause().getMessage()).contains("more than one IP address match");
                });
    }

    @Test
    void autoDetectionFailure_NoMatch() {
        final String json =
                '{' +
                "  \"method\": \"ZOOKEEPER\"," +
                "  \"secret\": \"" + TEST_SECRET + "\"," +
                "  \"servers\": {" +
                "    \"1\": {" +
                "      \"host\": \"255.255.255.255\"," +
                "      \"quorumPort\": 200," +
                "      \"electionPort\": 201" +
                "    }" +
                "  }" +
                '}';

        assertThatThrownBy(() -> Jackson.readValue(json, ZooKeeperReplicationConfig.class))
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(cause -> {
                    assertThat(cause.getCause().getMessage()).contains("no matching IP address");
                });
    }

    @Test
    void hierarchicalQuorums() throws Exception {
        final ReplicationConfig defaultCfg =
                Jackson.readValue(
                        '{' +
                        "  \"method\": \"ZOOKEEPER\"," +
                        "  \"serverId\": 10," +
                        "  \"secret\": \"" + TEST_SECRET + "\"," +
                        "  \"servers\": {" +
                        "    \"10\": {" +
                        "      \"host\": \"foo-1\"," +
                        "      \"quorumPort\": 100," +
                        "      \"electionPort\": 101," +
                        "      \"groupId\": 1," +
                        "      \"weight\": 2" +
                        "    }," +
                        "    \"11\": {" +
                        "      \"host\": \"foo-2\"," +
                        "      \"quorumPort\": 200," +
                        "      \"electionPort\": 201," +
                        "      \"groupId\": 1," +
                        "      \"weight\": 2" +
                        "    }," +
                        "    \"12\": {" +
                        "      \"host\": \"bar-1\"," +
                        "      \"quorumPort\": 100," +
                        "      \"electionPort\": 101," +
                        "      \"groupId\": 2," +
                        "      \"weight\": 1" +
                        "    }," +
                        "    \"13\": {" +
                        "      \"host\": \"bar-2\"," +
                        "      \"quorumPort\": 200," +
                        "      \"electionPort\": 201," +
                        "      \"groupId\": 2," +
                        "      \"weight\": 3" +
                        "    }" +
                        "  }" +
                        '}',
                        ReplicationConfig.class);

        assertThat(defaultCfg).isEqualTo(
                new ZooKeeperReplicationConfig(
                        10, ImmutableMap
                        .of(10, new ZooKeeperServerConfig("foo-1", 100, 101,
                                                          0, /* groupId */ 1, /* weight */ 2),
                            11, new ZooKeeperServerConfig("foo-2", 200, 201,
                                                          0, /* groupId */ 1, /* weight */ 2),
                            12, new ZooKeeperServerConfig("bar-1", 100, 101,
                                                          0, /* groupId */ 2, /* weight */ 1),
                            13, new ZooKeeperServerConfig("bar-2", 200, 201,
                                                          0, /* groupId */ 2, /* weight */ 3)),
                        TEST_SECRET, null, null, null, null, null, null));
    }

    @Test
    void noSecret_throws() {
        final ImmutableMap<Integer, ZooKeeperServerConfig> servers = ImmutableMap.of(
                1, new ZooKeeperServerConfig("127.0.0.1", 100, 101, 0, null, 1));
        assertThatThrownBy(() -> new ZooKeeperReplicationConfig(
                1, servers, null, null, 10000, 16, 1024, 86400000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replication.secret");
    }

    @Test
    void noSecretFromJson_throws() {
        final String json =
                '{' +
                "  \"method\": \"ZOOKEEPER\"," +
                "  \"serverId\": 1," +
                "  \"servers\": {" +
                "    \"1\": {" +
                "      \"host\": \"127.0.0.1\"," +
                "      \"quorumPort\": 100," +
                "      \"electionPort\": 101" +
                "    }" +
                "  }" +
                '}';

        assertThatThrownBy(() -> Jackson.readValue(json, ZooKeeperReplicationConfig.class))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .satisfies(cause -> {
                    assertThat(cause.getCause().getMessage()).contains("replication.secret");
                });
    }

    @Test
    void placeholderSecret_throws() {
        final ImmutableMap<Integer, ZooKeeperServerConfig> servers = ImmutableMap.of(
                1, new ZooKeeperServerConfig("127.0.0.1", 100, 101, 0, null, 1));
        assertThatThrownBy(() -> new ZooKeeperReplicationConfig(
                1, servers, "ch4n63m3", null, 10000, 16, 1024, 86400000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least");
    }

    @Test
    void shortSecret_throws() {
        final ImmutableMap<Integer, ZooKeeperServerConfig> servers = ImmutableMap.of(
                1, new ZooKeeperServerConfig("127.0.0.1", 100, 101, 0, null, 1));
        assertThatThrownBy(() -> new ZooKeeperReplicationConfig(
                1, servers, "abc", null, 10000, 16, 1024, 86400000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least");
    }

    @Test
    void emptySecret_throws() {
        final ImmutableMap<Integer, ZooKeeperServerConfig> servers = ImmutableMap.of(
                1, new ZooKeeperServerConfig("127.0.0.1", 100, 101, 0, null, 1));
        assertThatThrownBy(() -> new ZooKeeperReplicationConfig(
                1, servers, "", null, 10000, 16, 1024, 86400000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replication.secret");
    }

    @Test
    void validSecret_accepted() {
        final ImmutableMap<Integer, ZooKeeperServerConfig> servers = ImmutableMap.of(
                1, new ZooKeeperServerConfig("127.0.0.1", 100, 101, 0, null, 1));
        final ZooKeeperReplicationConfig cfg = new ZooKeeperReplicationConfig(
                1, servers, TEST_SECRET, null, 10000, 16, 1024, 86400000);
        assertThat(cfg.secret()).isEqualTo(TEST_SECRET);
    }

    @Test
    void allowInsecureSecret_withNullSecret() {
        final ImmutableMap<Integer, ZooKeeperServerConfig> servers = ImmutableMap.of(
                1, new ZooKeeperServerConfig("127.0.0.1", 100, 101, 0, null, 1));
        final ZooKeeperReplicationConfig cfg = new ZooKeeperReplicationConfig(
                1, servers, null, null, 10000, 16, 1024, 86400000, true);
        assertThat(cfg.secret()).isEqualTo("ch4n63m3");
    }

    @Test
    void allowInsecureSecret_withShortSecret() {
        final ImmutableMap<Integer, ZooKeeperServerConfig> servers = ImmutableMap.of(
                1, new ZooKeeperServerConfig("127.0.0.1", 100, 101, 0, null, 1));
        final ZooKeeperReplicationConfig cfg = new ZooKeeperReplicationConfig(
                1, servers, "abc", null, 10000, 16, 1024, 86400000, true);
        assertThat(cfg.secret()).isEqualTo("abc");
    }

    @Test
    void allowInsecureSecret_withPlaceholderSecret() {
        final ImmutableMap<Integer, ZooKeeperServerConfig> servers = ImmutableMap.of(
                1, new ZooKeeperServerConfig("127.0.0.1", 100, 101, 0, null, 1));
        final ZooKeeperReplicationConfig cfg = new ZooKeeperReplicationConfig(
                1, servers, "ch4n63m3", null, 10000, 16, 1024, 86400000, true);
        assertThat(cfg.secret()).isEqualTo("ch4n63m3");
    }

    @Test
    void allowInsecureSecret_false_stillRejects() {
        final ImmutableMap<Integer, ZooKeeperServerConfig> servers = ImmutableMap.of(
                1, new ZooKeeperServerConfig("127.0.0.1", 100, 101, 0, null, 1));
        assertThatThrownBy(() -> new ZooKeeperReplicationConfig(
                1, servers, null, null, 10000, 16, 1024, 86400000, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replication.secret");
    }

    @Test
    void allowInsecureSecret_fromJson() throws Exception {
        final ZooKeeperReplicationConfig cfg = Jackson.readValue(
                '{' +
                "  \"method\": \"ZOOKEEPER\"," +
                "  \"serverId\": 1," +
                "  \"allowInsecureSecret\": true," +
                "  \"servers\": {" +
                "    \"1\": {" +
                "      \"host\": \"127.0.0.1\"," +
                "      \"quorumPort\": 100," +
                "      \"electionPort\": 101" +
                "    }" +
                "  }" +
                '}',
                ZooKeeperReplicationConfig.class);
        assertThat(cfg.secret()).isEqualTo("ch4n63m3");
    }
}
