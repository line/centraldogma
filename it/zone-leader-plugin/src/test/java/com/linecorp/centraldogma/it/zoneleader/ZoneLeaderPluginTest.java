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

package com.linecorp.centraldogma.it.zoneleader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.ZoneConfig;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;
import com.linecorp.centraldogma.testing.internal.CentralDogmaReplicationExtension;

class ZoneLeaderPluginTest {

    private static final List<ZoneLeaderTestPlugin> plugins = new ArrayList<>();
    private static final int NUM_REPLICAS = 9;
    private static final List<String> zones = ImmutableList.of("zone1", "zone2", "zone3");

    @RegisterExtension
    static CentralDogmaReplicationExtension cluster = new CentralDogmaReplicationExtension(NUM_REPLICAS) {
        @Override
        protected void configureEach(int serverId, CentralDogmaBuilder builder) {
            if (serverId <= 3) {
                builder.zone(new ZoneConfig("zone1", zones));
            } else if (serverId <= 6) {
                builder.zone(new ZoneConfig("zone2", zones));
            } else {
                builder.zone(new ZoneConfig("zone3", zones));
            }
            final ZoneLeaderTestPlugin plugin = new ZoneLeaderTestPlugin(serverId);
            plugins.add(plugin);
            builder.plugins(plugin);
        }
    };

    @AfterAll
    static void afterAll() {
        plugins.clear();
    }

    @Test
    void shouldSelectZoneLeaderOnly() throws InterruptedException {
        assertZoneLeaderSelection();
        final List<ZoneLeaderTestPlugin> zone1 = plugins.subList(0, 3);
        final int zone1LeaderId = zoneLeaderId(zone1);
        // Zone leadership should be released when the leader goes down.
        cluster.servers().get(zone1LeaderId - 1).stopAsync().join();
        // Wait for the new zone leader to be selected.
        Thread.sleep(500);
        assertZoneLeaderSelection();
        final int newZone1LeaderId = zoneLeaderId(zone1);
        assertThat(newZone1LeaderId).isNotEqualTo(zone1LeaderId);

        final List<ZoneLeaderTestPlugin> zone2 = plugins.subList(3, 6);
        final int zone2LeaderId = zoneLeaderId(zone2);
        cluster.servers().get(zone2LeaderId - 1).stopAsync().join();
        // Wait for the new zone leader to be selected.
        Thread.sleep(500);
        assertZoneLeaderSelection();
        final int newZone2LeaderId = zoneLeaderId(zone2);
        assertThat(newZone2LeaderId).isNotEqualTo(zone2LeaderId);
    }

    private static int zoneLeaderId(List<ZoneLeaderTestPlugin> plugins) {
        return plugins.stream()
                      .filter(ZoneLeaderTestPlugin::isStarted)
                      .mapToInt(p -> p.serverId)
                      .findFirst()
                      .getAsInt();
    }

    /**
     * Make sure that only zone leaders start {@link ZoneLeaderTestPlugin}.
     */
    private static void assertZoneLeaderSelection() {
        for (int i = 0; i < NUM_REPLICAS; i += 3) {
            final List<ZoneLeaderTestPlugin> zonePlugins = plugins.subList(i, i + 3);
            await().untilAsserted(() -> {
                assertThat(zonePlugins.stream().filter(ZoneLeaderTestPlugin::isStarted)).hasSize(1);
            });
        }
    }

    private static final class ZoneLeaderTestPlugin implements Plugin {

        private final int serverId;
        private boolean started;

        private ZoneLeaderTestPlugin(int serverId) {
            this.serverId = serverId;
        }

        @Override
        public PluginTarget target(CentralDogmaConfig config) {
            return PluginTarget.ZONE_LEADER_ONLY;
        }

        boolean isStarted() {
            return started;
        }

        @Override
        public CompletionStage<Void> start(PluginContext context) {
            started = true;
            return UnmodifiableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> stop(PluginContext context) {
            started = false;
            return UnmodifiableFuture.completedFuture(null);
        }

        @Override
        public Class<?> configType() {
            return getClass();
        }
    }
}
