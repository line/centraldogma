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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.server.internal.mirror.DefaultMirroringServicePlugin;
import com.linecorp.centraldogma.server.internal.storage.PurgeSchedulingServicePlugin;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfigSpec;
import com.linecorp.centraldogma.server.plugin.AbstractNoopPlugin;
import com.linecorp.centraldogma.server.plugin.NoopPluginForAllReplicas;
import com.linecorp.centraldogma.server.plugin.NoopPluginForLeader;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;

@SuppressWarnings("DataFlowIssue")
class PluginGroupTest {

    @Test
    void confirmPluginsForAllReplicasLoaded() {
        final CentralDogmaConfigSpec cfg = mock(CentralDogmaConfigSpec.class);
        final PluginGroup group = PluginGroup.loadPlugins(PluginTarget.ALL_REPLICAS, cfg);
        assertThat(group).isNotNull();
        confirmPluginStartStop(group.findFirstPlugin(NoopPluginForAllReplicas.class));
    }

    @Test
    void confirmPluginsForLeaderLoaded() {
        final CentralDogmaConfigSpec cfg = mock(CentralDogmaConfigSpec.class);
        final PluginGroup group = PluginGroup.loadPlugins(PluginTarget.LEADER_ONLY, cfg);
        assertThat(group).isNotNull();
        confirmPluginStartStop(group.findFirstPlugin(NoopPluginForLeader.class));
    }

    @Test
    void confirmDefaultMirroringServiceLoadedDependingOnConfig() {
        final CentralDogmaConfigSpec cfg = mock(CentralDogmaConfigSpec.class);
        final PluginGroup group1 = PluginGroup.loadPlugins(PluginTarget.LEADER_ONLY, cfg);
        assertThat(group1).isNotNull();
        assertThat(group1.findFirstPlugin(DefaultMirroringServicePlugin.class)).isNotNull();

        when(cfg.pluginConfig(any())).thenReturn(null);
        when(cfg.pluginConfig(MirroringServicePluginConfigSpec.class))
                .thenReturn(new MirroringServicePluginConfig(true));
        final PluginGroup group2 = PluginGroup.loadPlugins(PluginTarget.LEADER_ONLY, cfg);
        assertThat(group2).isNotNull();
        assertThat(group2.findFirstPlugin(DefaultMirroringServicePlugin.class)).isNotNull();

        when(cfg.pluginConfig(MirroringServicePluginConfigSpec.class))
                .thenReturn(new MirroringServicePluginConfig(false));
        final PluginGroup group3 = PluginGroup.loadPlugins(PluginTarget.LEADER_ONLY, cfg);
        assertThat(group3).isNotNull();
        assertThat(group3.findFirstPlugin(DefaultMirroringServicePlugin.class)).isNull();
    }

    /**
     * The {@link PurgeSchedulingServicePlugin} must be loaded only if the
     * {@link CentralDogmaConfig#maxRemovedRepositoryAgeMillis()} property is greater then 0.
     */
    @Test
    void confirmScheduledPurgingServiceLoadedDependingOnConfig() {
        final CentralDogmaConfigSpec cfg = mock(CentralDogmaConfigSpec.class);
        when(cfg.maxRemovedRepositoryAgeMillis()).thenReturn(1L);
        final PluginGroup group1 = PluginGroup.loadPlugins(PluginTarget.LEADER_ONLY, cfg);
        assertThat(group1).isNotNull();
        assertThat(group1.findFirstPlugin(PurgeSchedulingServicePlugin.class)).isNotNull();

        when(cfg.maxRemovedRepositoryAgeMillis()).thenReturn(0L);
        final PluginGroup group2 = PluginGroup.loadPlugins(PluginTarget.LEADER_ONLY, cfg);
        assertThat(group2).isNotNull();
        assertThat(group2.findFirstPlugin(PurgeSchedulingServicePlugin.class)).isNull();
    }

    private static void confirmPluginStartStop(@Nullable AbstractNoopPlugin plugin) {
        assertThat(plugin).isNotNull();

        final PluginContext pluginContext = mock(PluginContext.class);
        plugin.start(pluginContext).toCompletableFuture().join();
        assertThat(plugin.isStarted()).isTrue();

        plugin.stop(pluginContext).toCompletableFuture().join();
        assertThat(plugin.isStarted()).isFalse();
    }
}
