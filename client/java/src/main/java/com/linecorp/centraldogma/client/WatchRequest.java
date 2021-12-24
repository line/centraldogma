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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send a {@link CentralDogma#watchFile(String, String, Revision, Query, long, boolean)}
 * request to the Central Dogma repository or create a new {@link Watcher}.
 */
public final class WatchRequest<T> extends WatchOptions {

    private final CentralDogmaRepository centralDogmaRepo;
    private final Query<T> query;
    private Revision lastKnownRevision = Revision.HEAD;

    WatchRequest(CentralDogmaRepository centralDogmaRepo, Query<T> query) {
        this.centralDogmaRepo = centralDogmaRepo;
        this.query = query;
    }

    /**
     * Sets the last known {@link Revision} to get notified if there's a change since the {@link Revision}.
     * {@link Revision#HEAD} is used by default.
     */
    public WatchRequest from(Revision lastKnownRevision) {
        this.lastKnownRevision = requireNonNull(lastKnownRevision, "lastKnownRevision");
        return this;
    }

    @Override
    public WatchRequest<T> timeout(Duration timeout) {
        //noinspection unchecked
        return (WatchRequest<T>) super.timeout(timeout);
    }

    @Override
    public WatchRequest<T> timeoutMillis(long timeoutMillis) {
        //noinspection unchecked
        return (WatchRequest<T>) super.timeoutMillis(timeoutMillis);
    }

    @Override
    public WatchRequest<T> errorOnEntryNotFound(boolean errorOnEntryNotFound) {
        //noinspection unchecked
        return (WatchRequest<T>) super.errorOnEntryNotFound(errorOnEntryNotFound);
    }

    /**
     * Waits for the file matched by the {@link Query} to be changed since the {@code lastKnownRevision}.
     * If no changes were made within the {@link #timeoutMillis(long)}, the
     * returned {@link CompletableFuture} will be completed with {@code null}.
     *
     * @return the {@link Entry} which contains the latest known {@link Query} result.
     *         {@code null} if the file was not changed for {@link #timeoutMillis(long)} milliseconds
     *         since the invocation of this method. {@link EntryNotFoundException} is raised if the
     *         target does not exist.
     */
    public CompletableFuture<Entry<T>> await() {
        return centralDogmaRepo.centralDogma().watchFile(centralDogmaRepo.projectName(),
                                                         centralDogmaRepo.repositoryName(),
                                                         lastKnownRevision, query,
                                                         timeoutMillis(), errorOnEntryNotFound());
    }
}
