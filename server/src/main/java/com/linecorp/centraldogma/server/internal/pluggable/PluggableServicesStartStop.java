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
package com.linecorp.centraldogma.server.internal.pluggable;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import com.google.common.collect.ImmutableList;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.util.StartStopSupport;
import com.linecorp.centraldogma.server.pluggable.PluggableService;

public final class PluggableServicesStartStop extends StartStopSupport<Void, Void> {

    private final List<PluggableService> services;

    public PluggableServicesStartStop(Executor executor, Iterable<PluggableService> services) {
        super(executor);
        this.services = ImmutableList.copyOf(requireNonNull(services, "services"));
    }

    @Override
    protected CompletionStage<Void> doStart() throws Exception {
        final List<CompletionStage<Void>> futures =
                services.stream().map(PluggableService::start).collect(toImmutableList());
        return CompletableFutures.allAsList(futures).thenApply(unused -> null);
    }

    @Override
    protected CompletionStage<Void> doStop() throws Exception {
        final List<CompletionStage<Void>> futures =
                services.stream().map(PluggableService::stop).collect(toImmutableList());
        return CompletableFutures.allAsList(futures).thenApply(unused -> null);
    }
}
