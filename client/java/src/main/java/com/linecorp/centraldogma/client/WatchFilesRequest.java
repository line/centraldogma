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

import static com.linecorp.centraldogma.client.WatcherOptions.ofDefault;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send a {@link CentralDogma#watchRepository(String, String, Revision, String, long)} request to
 * the Central Dogma repository or create
 * a new {@link CentralDogma#repositoryWatcher(String, String, String, Function, Executor)}.
 */
public final class WatchFilesRequest extends WatchOptions {

    private final CentralDogmaRepository centralDogmaRepo;
    private final PathPattern pathPattern;
    private final ScheduledExecutorService blockingTaskExecutor;

    WatchFilesRequest(CentralDogmaRepository centralDogmaRepo, PathPattern pathPattern,
                      ScheduledExecutorService blockingTaskExecutor) {
        this.centralDogmaRepo = centralDogmaRepo;
        this.pathPattern = pathPattern;
        this.blockingTaskExecutor = blockingTaskExecutor;
    }

    @Override
    public WatchFilesRequest timeout(Duration timeout) {
        return (WatchFilesRequest) super.timeout(timeout);
    }

    @Override
    public WatchFilesRequest timeoutMillis(long timeoutMillis) {
        return (WatchFilesRequest) super.timeoutMillis(timeoutMillis);
    }

    @Override
    public WatchFilesRequest errorOnEntryNotFound(boolean errorOnEntryNotFound) {
        return (WatchFilesRequest) super.errorOnEntryNotFound(errorOnEntryNotFound);
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
    public CompletableFuture<Revision> once(Revision lastKnownRevision) {
        requireNonNull(lastKnownRevision, "lastKnownRevision");
        return centralDogmaRepo.centralDogma().watchRepository(centralDogmaRepo.projectName(),
                                                               centralDogmaRepo.repositoryName(),
                                                               lastKnownRevision, pathPattern,
                                                               timeoutMillis(), errorOnEntryNotFound());
    }

    /**
     * Returns a {@link Watcher} which notifies its listeners when the repository has a new commit
     * that contains the changes for the files matched by the {@link PathPattern}. e.g:
     * <pre>{@code
     * Watcher<Revision> watcher = client.forRepo("foo", "bar")
     *                                   .watch(PathPattern.of("/*.json"))
     *                                   .watcher()
     *                                   .build();
     * watcher.watch(revision -> {
     *     ...
     * });}</pre>
     */
    public Watcher<Revision> forever() {
        return forever(ofDefault());
    }

    public Watcher<Revision> forever(WatcherOptions watcherOptions) {
        requireNonNull(watcherOptions, "watcherOptions");
        final String proName = centralDogmaRepo.projectName();
        final String repoName = centralDogmaRepo.repositoryName();
        return new DefaultWatcher<>(
                blockingTaskExecutor, proName, repoName, pathPattern.get(),
                lastKnownRevision ->
                        centralDogmaRepo.centralDogma()
                                        .watchRepository(proName, repoName, lastKnownRevision, pathPattern,
                                                         timeoutMillis(), errorOnEntryNotFound())
                                        .thenApply(revision -> new Latest<>(revision, revision)),
                errorOnEntryNotFound(), watcherOptions);
    }
}
