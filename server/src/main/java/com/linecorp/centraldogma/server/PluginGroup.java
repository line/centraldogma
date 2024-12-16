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
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.util.StartStopSupport;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * Provides asynchronous start-stop life cycle support for the {@link Plugin}s.
 */
final class PluginGroup {

    private static final Logger logger = LoggerFactory.getLogger(PluginGroup.class);

    /**
     * Returns a new {@link PluginGroup} which holds the {@link Plugin}s loaded from the classpath.
     * {@code null} is returned if there is no {@link Plugin} whose target equals to the specified
     * {@code target}.
     *
     * @param target the {@link PluginTarget} which would be loaded
     */
    @VisibleForTesting
    @Nullable
    static PluginGroup loadPlugins(PluginTarget target, CentralDogmaConfig config) {
        return loadPlugins(PluginGroup.class.getClassLoader(), config, ImmutableList.of()).get(target);
    }

    /**
     * Returns a new {@link PluginGroup} which holds the {@link Plugin}s loaded from the classpath.
     * An empty map is returned if there is no {@link Plugin} whose target equals to the specified
     * {@code target}.
     *
     * @param classLoader which is used to load the {@link Plugin}s
     */
    static Map<PluginTarget, PluginGroup> loadPlugins(ClassLoader classLoader, CentralDogmaConfig config,
                                                      List<Plugin> plugins) {
        requireNonNull(classLoader, "classLoader");
        requireNonNull(config, "config");

        final ServiceLoader<Plugin> loader = ServiceLoader.load(Plugin.class, classLoader);
        final ImmutableMap.Builder<Class<?>, Plugin> allPlugins = new ImmutableMap.Builder<>();
        for (Plugin plugin : Iterables.concat(plugins, loader)) {
            if (plugin.isEnabled(config)) {
                allPlugins.put(plugin.configType(), plugin);
            }
        }

        // IllegalArgumentException is thrown if there are duplicate keys.
        final Map<Class<?>, Plugin> pluginMap = allPlugins.build();
        if (pluginMap.isEmpty()) {
            return ImmutableMap.of();
        }

        final Map<PluginTarget, PluginGroup> pluginGroups =
                pluginMap.values()
                         .stream()
                         .collect(Collectors.groupingBy(plugin -> plugin.target(config)))
                         .entrySet()
                         .stream()
                         .collect(toImmutableMap(Entry::getKey, e -> {
                             final PluginTarget target = e.getKey();
                             final List<Plugin> targetPlugins = e.getValue();
                             final String poolName =
                                     "plugins-for-" + target.name().toLowerCase().replace("_", "-");
                             return new PluginGroup(targetPlugins,
                                                    Executors.newSingleThreadExecutor(
                                                            new DefaultThreadFactory(poolName, true)));
                         }));

        pluginGroups.forEach((target, group) -> {
            logger.debug("Loaded plugins for target {}: {}", target,
                         group.plugins().stream().map(plugin -> plugin.getClass().getName())
                              .collect(toImmutableList()));
        });
        return pluginGroups;
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
    @Nullable
    <T extends Plugin> T findFirstPlugin(Class<T> clazz) {
        requireNonNull(clazz, "clazz");
        return plugins.stream().filter(clazz::isInstance).map(clazz::cast).findFirst().orElse(null);
    }

    /**
     * Starts the {@link Plugin}s managed by this {@link PluginGroup}.
     */
    CompletableFuture<Void> start(CentralDogmaConfig config, ProjectManager projectManager,
                                  CommandExecutor commandExecutor, MeterRegistry meterRegistry,
                                  ScheduledExecutorService purgeWorker,
                                  InternalProjectInitializer internalProjectInitializer) {
        final PluginContext context = new PluginContext(config, projectManager, commandExecutor, meterRegistry,
                                                        purgeWorker, internalProjectInitializer);
        return startStop.start(context, context, true);
    }

    /**
     * Stops the {@link Plugin}s managed by this {@link PluginGroup}.
     */
    CompletableFuture<Void> stop(CentralDogmaConfig config, ProjectManager projectManager,
                                 CommandExecutor commandExecutor, MeterRegistry meterRegistry,
                                 ScheduledExecutorService purgeWorker,
                                 InternalProjectInitializer internalProjectInitializer) {
        return startStop.stop(
                new PluginContext(config, projectManager, commandExecutor, meterRegistry, purgeWorker,
                                  internalProjectInitializer));
    }

    private class PluginGroupStartStop extends StartStopSupport<PluginContext, PluginContext, Void, Void> {

        PluginGroupStartStop(Executor executor) {
            super(executor);
        }

        @Override
        protected CompletionStage<Void> doStart(@Nullable PluginContext arg) throws Exception {
            assert arg != null;
            // Wait until the internal project is initialized.
            arg.internalProjectInitializer().whenInitialized().get();
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
