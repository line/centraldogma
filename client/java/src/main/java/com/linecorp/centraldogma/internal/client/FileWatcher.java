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

import static com.linecorp.centraldogma.internal.Util.unsafeCast;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.Latest;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

public final class FileWatcher<T> extends AbstractWatcher<T> {
    private final Query<?> query;
    private final Function<Object, ? extends T> function;
    private final Executor callbackExecutor;

    /**
     * Creates a new instance.
     */
    public <U> FileWatcher(CentralDogma client, ScheduledExecutorService watchScheduler,
                           Executor callbackExecutor,
                           String projectName, String repositoryName,
                           Query<U> query, Function<? super U, ? extends T> function) {

        super(client, watchScheduler, projectName, repositoryName, requireNonNull(query, "query").path());
        this.query = query;
        this.function = unsafeCast(requireNonNull(function, "function"));
        this.callbackExecutor = requireNonNull(callbackExecutor, "callbackExecutor");
    }

    @Override
    protected CompletableFuture<Latest<T>> doWatch(CentralDogma client, String projectName,
                                                   String repositoryName, Revision lastKnownRevision) {
        return client.watchFile(projectName, repositoryName, lastKnownRevision, query)
                     .thenApplyAsync(result -> {
                         if (result == null) {
                             return null;
                         }
                         return new Latest<>(result.revision(), function.apply(result.content()));
                     }, callbackExecutor);
    }
}
