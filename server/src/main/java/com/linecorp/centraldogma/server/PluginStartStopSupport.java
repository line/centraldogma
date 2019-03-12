/*
 * Copyright 2019 LINE Corporation
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.util.StartStopSupport;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;

/**
 * Provides asynchronous start-stop life cycle support for the {@link Plugin}s.
 */
final class PluginStartStopSupport extends StartStopSupport<Void, Void> {

    /**
     * Returns a new {@link PluginStartStopSupport} which holds the {@link Plugin}s loaded from the classpath.
     * {@code null} is returned if there is no {@link Plugin} which target equals to the specified
     * {@code target}.
     *
     * @param classLoader which is used to load the {@link Plugin}s
     * @param target the {@link PluginTarget} which would be loaded
     */
    @Nullable
    static PluginStartStopSupport loadPlugins(ClassLoader classLoader, PluginTarget target) {
        requireNonNull(classLoader, "classLoader");
        requireNonNull(target, "target");

        final ServiceLoader<Plugin> loader = ServiceLoader.load(Plugin.class, classLoader);
        final Builder<Plugin> plugins = new Builder<>();
        for (Plugin plugin : loader) {
            if (target == plugin.target()) {
                plugins.add(plugin);
            }
        }

        final List<Plugin> list = plugins.build();
        if (list.isEmpty()) {
            return null;
        }

        // TODO(hyangtack) Need to use another executor?
        return new PluginStartStopSupport(CommonPools.blockingTaskExecutor(), list);
    }

    private final List<Plugin> plugins;

    @Nullable
    private PluginContext context;

    private PluginStartStopSupport(Executor executor, Iterable<Plugin> plugins) {
        super(executor);
        this.plugins = ImmutableList.copyOf(requireNonNull(plugins, "plugins"));
    }

    public void context(PluginContext context) {
        this.context = requireNonNull(context, "context");
    }

    @Override
    protected CompletionStage<Void> doStart() throws Exception {
        assert context != null;
        final List<CompletionStage<Void>> futures =
                plugins.stream().map(plugin -> plugin.start(context)).collect(toImmutableList());
        return CompletableFutures.allAsList(futures).thenApply(unused -> null);
    }

    @Override
    protected CompletionStage<Void> doStop() throws Exception {
        assert context != null;
        final List<CompletionStage<Void>> futures =
                plugins.stream().map(plugin -> plugin.stop(context)).collect(toImmutableList());
        return CompletableFutures.allAsList(futures).thenApply(unused -> null);
    }
}
