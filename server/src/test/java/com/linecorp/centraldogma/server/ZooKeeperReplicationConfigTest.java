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

import static com.linecorp.centraldogma.testing.internal.TestUtil.assertJsonConversion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.internal.Jackson;

class ZooKeeperReplicationConfigTest {

    @Test
    void testJsonConversion() {
        final ImmutableMap<Integer, ZooKeeperServerConfig> servers = ImmutableMap.of(
                1, new ZooKeeperServerConfig("2", 3, 4, 5, /* groupId */ null, /* weight */ 1),
                6, new ZooKeeperServerConfig("7", 8, 9, 10, /* groupId */ null, /* weight */ 1));
        final ZooKeeperReplicationConfig cfg = new ZooKeeperReplicationConfig(
                1, servers,
                "11", ImmutableMap.of("12", "13", "14", "15"), 16, 17, 18, 19);
        assertJsonConversion(cfg, ReplicationConfig.class,
                             '{' +
                             "  \"method\": \"ZOOKEEPER\"," +
                             "  \"serverId\": 1," +
                             "  \"servers\": {" +
                             "    \"1\": {" +
                             "      \"host\": \"2\"," +
                             "      \"quorumPort\": 3," +
                             "      \"electionPort\": 4," +
                             "      \"clientPort\": 5" +
                             "    }," +
                             "    \"6\": {" +
                             "      \"host\": \"7\"," +
                             "      \"quorumPort\": 8," +
                             "      \"electionPort\": 9," +
                             "      \"clientPort\": 10" +
                             "    }" +
                             "  }," + // NB: secret is not serialized.
                             "  \"additionalProperties\": { \"12\": \"13\", \"14\": \"15\" }," +
                             "  \"timeoutMillis\": 16," +
                             "  \"numWorkers\": 17," +
                             "  \"maxLogCount\": 18," +
                             "  \"minLogAgeMillis\": 19" +
                             '}');
    }

    @Test
    void testJsonConversionWithoutOptionalProperties() throws Exception {
        final ReplicationConfig defaultCfg =
                Jackson.readValue(
                        '{' +
                        "  \"method\": \"ZOOKEEPER\"," +
                        "  \"serverId\": 10," +
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
                        null, null, null, null, null, null));
    }

    @Test
    void autoDetection() throws Exception {
        final ZooKeeperReplicationConfig cfg = Jackson.readValue(
                '{' +
                "  \"method\": \"ZOOKEEPER\"," +
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
                        null, null, null, null, null, null));
    }
}
