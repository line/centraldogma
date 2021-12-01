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
package com.linecorp.centraldogma.internal.client;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.Latest;
import com.linecorp.centraldogma.client.WatchOptions;
import com.linecorp.centraldogma.common.Revision;

public final class RepositoryWatcher<T> extends AbstractWatcher<T> {
    private final String pathPattern;
    private final Function<Revision, ? extends T> function;
    private final Executor callbackExecutor;

    /**
     * Creates a new instance.
     */
    public RepositoryWatcher(CentralDogma client, ScheduledExecutorService watchScheduler,
                             Executor callbackExecutor,
                             String projectName, String repositoryName,
                             String pathPattern, Function<Revision, ? extends T> function,
                             WatchOptions watchOptions) {
        super(client, watchScheduler, projectName, repositoryName, pathPattern, watchOptions);
        this.pathPattern = requireNonNull(pathPattern, "pathPattern");
        this.function = requireNonNull(function, "function");
        this.callbackExecutor = requireNonNull(callbackExecutor, "callbackExecutor");
    }

    @Override
    protected CompletableFuture<Latest<T>> doWatch(CentralDogma client, String projectName,
                                                   String repositoryName, Revision lastKnownRevision,
                                                   WatchOptions watchOptions) {
        return client.watchRepository(projectName, repositoryName, lastKnownRevision, pathPattern, watchOptions)
                     .thenApplyAsync(revision -> {
                         if (revision == null) {
                             return null;
                         }
                         return new Latest<>(revision, function.apply(revision));
                     }, callbackExecutor);
    }
}
