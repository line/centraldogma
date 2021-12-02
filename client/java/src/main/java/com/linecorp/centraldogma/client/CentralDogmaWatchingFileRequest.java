/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.centraldogma.client;

import static com.linecorp.centraldogma.internal.Util.unsafeCast;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send a {@link CentralDogma#watchFile(String, String, Revision, Query, long)} request to the
 * Central Dogma repository or create
 * a new {@link CentralDogma#fileWatcher(String, String, Query, Function, Executor)}.
 */
public final class CentralDogmaWatchingFileRequest<T> extends CentralDogmaWatchingRequest {

    private final CentralDogmaRequestPreparation requestPreparation;
    private final Query<?> query;
    private final Function<Object, ? extends T> function;
    private final Executor functionExecutor;

    <U> CentralDogmaWatchingFileRequest(CentralDogmaRequestPreparation requestPreparation,
                                        Query<U> query, Function<? super U, ? extends T> function,
                                        @Nullable Executor functionExecutor) {
        this.requestPreparation = requestPreparation;
        this.query = query;
        this.function = unsafeCast(function);
        this.functionExecutor = functionExecutor != null ? functionExecutor : defaultExecutor;
    }

    private CentralDogmaWatchingFileRequest(CentralDogmaRequestPreparation requestPreparation,
                                            Query<?> query, Function<Object, ? extends T> function,
                                            Executor functionExecutor, long timeoutMillis) {
        super(timeoutMillis);
        this.requestPreparation = requestPreparation;
        this.query = query;
        this.function = function;
        this.functionExecutor = functionExecutor;
    }

    @Override
    public CentralDogmaWatchingFileRequest<T> timeout(Duration timeout) {
        //noinspection unchecked
        return (CentralDogmaWatchingFileRequest<T>) super.timeout(timeout);
    }

    @Override
    public CentralDogmaWatchingFileRequest<T> timeoutMillis(long timeoutMillis) {
        //noinspection unchecked
        return (CentralDogmaWatchingFileRequest<T>) super.timeoutMillis(timeoutMillis);
    }

    /**
     * Sets the {@link Function} that convert the result of a watch. The {@link Function} is executed by
     * a {@code CommonPools#blockingTaskExecutor()} if Armeria dependency is found or {@link ForkJoinPool}.
     */
    public <U> CentralDogmaWatchingFileRequest<U> map(Function<? super T, ? extends U> function) {
        return map(function, functionExecutor);
    }

    /**
     * Sets the {@link Function} that convert the result of a watch. The {@link Function} is executed by
     * a the specified {@link Executor}.
     */
    public <U> CentralDogmaWatchingFileRequest<U> map(Function<? super T, ? extends U> function,
                                                      Executor executor) {
        requireNonNull(function, "function");
        requireNonNull(executor, "executor");
        return new CentralDogmaWatchingFileRequest<>(requestPreparation, query, this.function.andThen(function),
                                                     executor, timeoutMillis());
    }

    /**
     * Waits for the file matched by the {@link Query} to be changed since the specified
     * {@code lastKnownRevision}. If no changes were made within the {@link #timeoutMillis(long)}, the
     * returned {@link CompletableFuture} will be completed with {@code null}.
     *
     * @return the {@link Entry} which contains the latest known {@link Query} result.
     *         {@code null} if the file was not changed for {@link #timeoutMillis(long)} milliseconds
     *         since the invocation of this method.
     */
    public CompletableFuture<Latest<T>> watch(Revision lastKnownRevision) {
        requireNonNull(lastKnownRevision, "lastKnownRevision");
        final CompletableFuture<? extends Entry<?>> future =
                requestPreparation.centralDogma().watchFile(requestPreparation.projectName(),
                                                            requestPreparation.repositoryName(),
                                                            lastKnownRevision, query, timeoutMillis());
        return future.thenApplyAsync(result -> {
            if (result == null) {
                return null;
            }
            return new Latest<>(result.revision(), function.apply(result.content()));
        }, functionExecutor);
    }

    /**
     * Returns a {@link Watcher} which notifies its listeners when the result of the
     * {@link Query} becomes available or changes. e.g:
     * <pre>{@code
     * Watcher<JsonNode> watcher = client.forRepo("foo", "bar")
     *                                   .watchingFile(Query.ofJson("/baz.json"))
     *                                   .newWatcher();
     *
     * watcher.watch((revision, content) -> {
     *     assert content instanceof JsonNode;
     *     ...
     * });}</pre>
     */
    public Watcher<T> newWatcher() { // TODO(minwoox): Add a method that takes lastKnownRevision.
        return requestPreparation.centralDogma().fileWatcher(requestPreparation.projectName(),
                                                             requestPreparation.repositoryName(),
                                                             query, function, functionExecutor);
    }
}
