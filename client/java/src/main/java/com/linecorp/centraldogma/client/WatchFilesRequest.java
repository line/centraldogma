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

import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send a {@link CentralDogma#watchRepository(String, String, Revision, PathPattern, long, boolean)}
 * request to the Central Dogma repository.
 */
public final class WatchFilesRequest extends WatchOptions {

    private final CentralDogmaRepository centralDogmaRepo;
    private final PathPattern pathPattern;

    WatchFilesRequest(CentralDogmaRepository centralDogmaRepo, PathPattern pathPattern) {
        this.centralDogmaRepo = centralDogmaRepo;
        this.pathPattern = pathPattern;
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
     * Waits for the files matched by the {@link PathPattern} to be changed since the {@link Revision#HEAD}.
     * If no changes were made within the {@link #timeoutMillis(long)}, the
     * returned {@link CompletableFuture} will be completed with {@code null}.
     *
     * @return the latest known {@link Revision} which contains the changes for the matched files.
     *         {@code null} if the files were not changed for {@code timeoutMillis} milliseconds
     *         since the invocation of this method. {@link EntryNotFoundException} is raised if the
     *         target does not exist.
     */
    public CompletableFuture<Revision> start() {
        return start(Revision.HEAD);
    }

    /**
     * Waits for the files matched by the {@link PathPattern} to be changed since the {@code lastKnownRevision}.
     * If no changes were made within the {@link #timeoutMillis(long)}, the
     * returned {@link CompletableFuture} will be completed with {@code null}.
     *
     * @return the latest known {@link Revision} which contains the changes for the matched files.
     *         {@code null} if the files were not changed for {@code timeoutMillis} milliseconds
     *         since the invocation of this method. {@link EntryNotFoundException} is raised if the
     *         target does not exist.
     */
    public CompletableFuture<Revision> start(Revision lastKnownRevision) {
        requireNonNull(lastKnownRevision, "lastKnownRevision");
        return centralDogmaRepo.centralDogma().watchRepository(centralDogmaRepo.projectName(),
                                                               centralDogmaRepo.repositoryName(),
                                                               lastKnownRevision, pathPattern,
                                                               timeoutMillis(), errorOnEntryNotFound());
    }
}
