/*
 * Copyright 2018 LINE Corporation
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

import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.internal.Jackson;

public class ZooKeeperReplicationConfigTest {
    @Test
    public void testJsonConversion() throws Exception {
        final ZooKeeperReplicationConfig cfg = new ZooKeeperReplicationConfig(
                1, ImmutableMap.of(1, new ZooKeeperAddress("2", 3, 4, 5),
                                   6, new ZooKeeperAddress("7", 8, 9, 10)),
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
                             "  }," +
                             "  \"secret\": \"11\"," +
                             "  \"additionalProperties\": { \"12\": \"13\", \"14\": \"15\" }," +
                             "  \"timeoutMillis\": 16," +
                             "  \"numWorkers\": 17," +
                             "  \"maxLogCount\": 18," +
                             "  \"minLogAgeMillis\": 19" +
                             '}');
    }

    @Test
    public void testJsonConversionWithoutOptionalProperties() throws Exception {
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
                        10, ImmutableMap.of(10, new ZooKeeperAddress("foo", 100, 101, 0),
                                            11, new ZooKeeperAddress("bar", 200, 201, 0)),
                        null, null, null, null, null, null));
    }

    @Test
    public void autoDetection() throws Exception {
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
    public void autoDetectionFailure_TwoMatches() throws Exception {
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
    public void autoDetectionFailure_NoMatch() throws Exception {
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
}
