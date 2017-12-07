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

package com.linecorp.centraldogma.server.internal.replication;

import static com.linecorp.centraldogma.testing.internal.TestUtil.assertJsonConversion;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.junit.Test;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.ReplicationConfig;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;

public class ZooKeeperReplicationConfigTest {
    @Test
    public void testJsonConversion() throws Exception {
        assertJsonConversion(new ZooKeeperReplicationConfig("foo", "bar", 42, 65, 17, 99),
                             ReplicationConfig.class,
                             '{' +
                             "  \"method\": \"ZOOKEEPER\"," +
                             "  \"connectionString\": \"foo\"," +
                             "  \"pathPrefix\": \"bar\"," +
                             "  \"timeoutMillis\": 42," +
                             "  \"numWorkers\": 65," +
                             "  \"maxLogCount\": 17," +
                             "  \"minLogAgeMillis\": 99" +
                             '}');
    }

    @Test
    public void testJsonConversionWithoutOptionalProperties() throws Exception {
        final ReplicationConfig defaultCfg =
                Jackson.readValue(
                        '{' +
                        "  \"method\": \"ZOOKEEPER\"," +
                        "  \"connectionString\": \"foo\"," +
                        "  \"pathPrefix\": \"bar\"" +
                        '}',
                        ReplicationConfig.class);

        assertThat(defaultCfg, is(new ZooKeeperReplicationConfig("foo", "bar")));
    }
}
