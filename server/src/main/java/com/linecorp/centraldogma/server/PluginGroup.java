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
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.util.StartStopSupport;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * Provides asynchronous start-stop life cycle support for the {@link Plugin}s.
 */
// TODO(minwoox): move to internal
public final class PluginGroup {

    private static final Logger logger = LoggerFactory.getLogger(PluginGroup.class);

    /**
     * Returns a new {@link PluginGroup} which holds the {@link Plugin}s loaded from the classpath.
     * {@code null} is returned if there is no {@link Plugin} whose target equals to the specified
     * {@code target}.
     *
     * @param target the {@link PluginTarget} which would be loaded
     */
    @Nullable
    public static PluginGroup loadPlugins(PluginTarget target, CentralDogmaConfig config) {
        return loadPlugins(PluginGroup.class.getClassLoader(), target, config);
    }

    /**
     * Returns a new {@link PluginGroup} which holds the {@link Plugin}s loaded from the classpath.
     * {@code null} is returned if there is no {@link Plugin} whose target equals to the specified
     * {@code target}.
     *
     * @param classLoader which is used to load the {@link Plugin}s
     * @param target the {@link PluginTarget} which would be loaded
     */
    @Nullable
    public static PluginGroup loadPlugins(ClassLoader classLoader, PluginTarget target,
                                          CentralDogmaConfig config) {
        requireNonNull(classLoader, "classLoader");
        requireNonNull(target, "target");
        requireNonNull(config, "config");

        final ServiceLoader<Plugin> loader = ServiceLoader.load(Plugin.class, classLoader);
        final Builder<Plugin> plugins = new Builder<>();
        for (Plugin plugin : loader) {
            if (target == plugin.target() && plugin.isEnabled(config)) {
                plugins.add(plugin);
            }
        }

        final List<Plugin> list = plugins.build();
        if (list.isEmpty()) {
            return null;
        }

        return new PluginGroup(list, Executors.newSingleThreadExecutor(new DefaultThreadFactory(
                "plugins-for-" + target.name().toLowerCase().replace("_", "-"), true)));
    }

    private final List<Plugin> plugins;
    private final PluginGroupStartStop startStop;

    private PluginGroup(Iterable<Plugin> plugins, Executor executor) {
        this.plugins = ImmutableList.copyOf(requireNonNull(plugins, "plugins"));
        startStop = new PluginGroupStartStop(requireNonNull(executor, "executor"));
    }

    /**
     * Returns the {@link Plugin}s managed by this {@link PluginGroup}.
     */
    List<Plugin> plugins() {
        return plugins;
    }

    /**
     * Returns the first {@link Plugin} of the specified {@code clazz} as wrapped by an {@link Optional}.
     */
    public <T extends Plugin> Optional<T> findFirstPlugin(Class<T> clazz) {
        requireNonNull(clazz, "clazz");
        return plugins.stream().filter(clazz::isInstance).map(clazz::cast).findFirst();
    }

    /**
     * Starts the {@link Plugin}s managed by this {@link PluginGroup}.
     */
    CompletableFuture<Void> start(CentralDogmaConfig config, ProjectManager projectManager,
                                  CommandExecutor commandExecutor, MeterRegistry meterRegistry,
                                  ScheduledExecutorService purgeWorker) {
        final PluginContext context = new PluginContext(config, projectManager, commandExecutor, meterRegistry,
                                                        purgeWorker);
        return startStop.start(context, context, true);
    }

    /**
     * Stops the {@link Plugin}s managed by this {@link PluginGroup}.
     */
    CompletableFuture<Void> stop(CentralDogmaConfig config, ProjectManager projectManager,
                                 CommandExecutor commandExecutor, MeterRegistry meterRegistry,
                                 ScheduledExecutorService purgeWorker) {
        return startStop.stop(
                new PluginContext(config, projectManager, commandExecutor, meterRegistry, purgeWorker));
    }

    private class PluginGroupStartStop extends StartStopSupport<PluginContext, PluginContext, Void, Void> {

        PluginGroupStartStop(Executor executor) {
            super(executor);
        }

        @Override
        protected CompletionStage<Void> doStart(@Nullable PluginContext arg) throws Exception {
            assert arg != null;
            final List<CompletionStage<Void>> futures = plugins.stream().map(
                    plugin -> plugin.start(arg)
                                    .thenAccept(unused -> logger.info("Plugin started: {}", plugin))
                                    .exceptionally(cause -> {
                                        logger.info("Failed to start plugin: {}", plugin, cause);
                                        return null;
                                    })).collect(toImmutableList());
            return CompletableFutures.allAsList(futures).thenApply(unused -> null);
        }

        @Override
        protected CompletionStage<Void> doStop(@Nullable PluginContext arg) throws Exception {
            assert arg != null;
            final List<CompletionStage<Void>> futures = plugins.stream().map(
                    plugin -> plugin.stop(arg)
                                    .thenAccept(unused -> logger.info("Plugin stopped: {}", plugin))
                                    .exceptionally(cause -> {
                                        logger.info("Failed to stop plugin: {}", plugin, cause);
                                        return null;
                                    })).collect(toImmutableList());
            return CompletableFutures.allAsList(futures).thenApply(unused -> null);
        }
    }
}
