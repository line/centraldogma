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

import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send a {@link CentralDogma#watchRepository(String, String, Revision, String, long)} request to
 * the Central Dogma repository or create
 * a new {@link CentralDogma#repositoryWatcher(String, String, String, Function, Executor)}.
 */
public final class CentralDogmaWatchingFilesRequest<T> extends CentralDogmaWatchingRequest {

    private final CentralDogmaRequestPreparation requestPreparation;
    private final String pathPattern;
    private final Function<Object, ? extends T> function;
    private final Executor functionExecutor;

    <U> CentralDogmaWatchingFilesRequest(CentralDogmaRequestPreparation requestPreparation,
                                         String pathPattern, Function<? super U, ? extends T> function,
                                         @Nullable Executor functionExecutor) {
        this.requestPreparation = requestPreparation;
        this.pathPattern = pathPattern;
        this.function = unsafeCast(function);
        this.functionExecutor = functionExecutor != null ? functionExecutor : defaultExecutor;
    }

    CentralDogmaWatchingFilesRequest(CentralDogmaRequestPreparation requestPreparation,
                                     String pathPattern, Function<Object, ? extends T> function,
                                     Executor functionExecutor, long timeoutMillis) {
        super(timeoutMillis);
        this.requestPreparation = requestPreparation;
        this.pathPattern = pathPattern;
        this.function = function;
        this.functionExecutor = functionExecutor;
    }

    @Override
    public CentralDogmaWatchingFilesRequest<T> timeout(Duration timeout) {
        //noinspection unchecked
        return (CentralDogmaWatchingFilesRequest<T>) super.timeout(timeout);
    }

    @Override
    public CentralDogmaWatchingFilesRequest<T> timeoutMillis(long timeoutMillis) {
        //noinspection unchecked
        return (CentralDogmaWatchingFilesRequest<T>) super.timeoutMillis(timeoutMillis);
    }

    /**
     * Sets the {@link Function} that convert the result of a watch. The {@link Function} is executed by
     * a {@code CommonPools#blockingTaskExecutor()} if Armeria dependency is found or {@link ForkJoinPool}.
     */
    public <U> CentralDogmaWatchingFilesRequest<U> map(Function<? super T, ? extends U> function) {
        return map(function, functionExecutor);
    }

    /**
     * Sets the {@link Function} that convert the result of a watch. The {@link Function} is executed by
     * a the specified {@link Executor}.
     */
    public <U> CentralDogmaWatchingFilesRequest<U> map(Function<? super T, ? extends U> function,
                                                       Executor executor) {
        requireNonNull(function, "function");
        requireNonNull(executor, "executor");
        return new CentralDogmaWatchingFilesRequest<>(requestPreparation, pathPattern,
                                                      this.function.andThen(function),
                                                      executor, timeoutMillis());
    }

    /**
     * Waits for the files matched by the {@code pathPattern} to be changed since the specified
     * {@code lastKnownRevision}. If no changes were made within the {@link #timeoutMillis(long)}, the
     * returned {@link CompletableFuture} will be completed with {@code null}.
     *
     * @return the latest known {@link Revision} which contains the changes for the matched files.
     *         {@code null} if the files were not changed for {@code timeoutMillis} milliseconds
     *         since the invocation of this method.
     */
    public CompletableFuture<T> watch(Revision lastKnownRevision) {
        requireNonNull(lastKnownRevision, "lastKnownRevision");
        final CompletableFuture<Revision> future =
                requestPreparation.centralDogma().watchRepository(requestPreparation.projectName(),
                                                                  requestPreparation.repositoryName(),
                                                                  lastKnownRevision, pathPattern,
                                                                  timeoutMillis());
        return future.thenApplyAsync(result -> {
            if (result == null) {
                return null;
            }
            return function.apply(result);
        }, functionExecutor);
    }

    /**
     * Returns a {@link Watcher} which notifies its listeners when the repository has a new commit
     * that contains the changes for the files matched by the {@code pathPattern}. e.g:
     * <pre>{@code
     * Watcher<Revision> watcher = client.forRepo("foo", "bar").watchingFiles("/*.json").newWatcher();
     *
     * watcher.watch(revision -> {
     *     ...
     * });}</pre>
     */
    public Watcher<T> newWatcher() { // TODO(minwoox): Add a method that takes lastKnownRevision.
        return requestPreparation.centralDogma().repositoryWatcher(requestPreparation.projectName(),
                                                                   requestPreparation.repositoryName(),
                                                                   pathPattern, unsafeCast(function),
                                                                   functionExecutor);
    }
}
