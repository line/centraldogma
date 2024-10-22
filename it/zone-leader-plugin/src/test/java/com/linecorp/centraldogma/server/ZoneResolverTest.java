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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

class ZoneResolverTest {

    @Test
    void plainText() {
        assertThat(ZoneResolver.resolve("zoneA")).isEqualTo("zoneA");
        assertThat(ZoneResolver.resolve(null)).isNull();
    }

    @SetEnvironmentVariable(key = "ZONE", value = "ZONE_A")
    @SetEnvironmentVariable(key = "MY_ZONE", value = "ZONE_B")
    @Test
    void environmentVariable() {
        assertThat(ZoneResolver.resolve("$ZONE")).isEqualTo("ZONE_A");
        assertThat(ZoneResolver.resolve("$MY_ZONE")).isEqualTo("ZONE_B");
    }

    @Test
    void zoneProvider() {
        final String zone = ZoneResolver.resolve(
                "classpath:com.linecorp.centraldogma.server.MyZoneProvider");
        assertThat(zone).isEqualTo("ZONE_C");
    }
}
