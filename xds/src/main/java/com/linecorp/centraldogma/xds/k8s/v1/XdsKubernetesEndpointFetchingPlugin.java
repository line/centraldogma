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
package com.linecorp.centraldogma.xds.k8s.v1;

import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static io.fabric8.kubernetes.client.Config.KUBERNETES_DISABLE_AUTO_CONFIG_SYSTEM_PROPERTY;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;

/**
 * A plugin that fetches Kubernetes endpoints from Central Dogma and provides them to the control plane.
 */
public final class XdsKubernetesEndpointFetchingPlugin implements Plugin {

    static {
        System.setProperty(KUBERNETES_DISABLE_AUTO_CONFIG_SYSTEM_PROPERTY, "true");
    }

    @Nullable
    private XdsKubernetesEndpointFetchingService fetchingService;

    @Override
    public PluginTarget target(CentralDogmaConfig config) {
        return PluginTarget.LEADER_ONLY;
    }

    @Override
    public synchronized CompletionStage<Void> start(PluginContext context) {
        requireNonNull(context, "context");
        if (fetchingService != null) {
            return UnmodifiableFuture.completedFuture(null);
        }
        context.internalProjectInitializer().initialize(XDS_CENTRAL_DOGMA_PROJECT);

        fetchingService = new XdsKubernetesEndpointFetchingService(
                context.projectManager().get(XDS_CENTRAL_DOGMA_PROJECT), context.commandExecutor(),
                context.meterRegistry());
        fetchingService.start();
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> stop(PluginContext context) {
        if (fetchingService == null) {
            return UnmodifiableFuture.completedFuture(null);
        }
        fetchingService.stop();
        fetchingService = null;
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public Class<?> configType() {
        return getClass();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("target", PluginTarget.LEADER_ONLY)
                          .add("configType", configType().getName())
                          .toString();
    }
}
