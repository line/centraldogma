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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send a {@link CentralDogma#getFiles(String, String, Revision, PathPattern)} or
 * {@link CentralDogma#listFiles(String, String, Revision, PathPattern)} request to the
 * Central Dogma repository.
 */
public final class FilesRequest extends AbstractFileRequest<FilesRequest> {

    private final CentralDogmaRepository centralDogmaRepo;
    private final PathPattern pathPattern;

    FilesRequest(CentralDogmaRepository centralDogmaRepo, PathPattern pathPattern) {
        this.centralDogmaRepo = centralDogmaRepo;
        this.pathPattern = pathPattern;
    }

    /**
     * Retrieves the list of the files matched by the given path pattern at the {@link Revision#HEAD}.
     *
     * @return a {@link Map} of file path and type pairs
     */
    public CompletableFuture<Map<String, EntryType>> list() {
        return list(Revision.HEAD);
    }

    /**
     * Retrieves the list of the files matched by the given path pattern at the {@link Revision}.
     *
     * @return a {@link Map} of file path and type pairs
     */
    public CompletableFuture<Map<String, EntryType>> list(Revision revision) {
        requireNonNull(revision, "revision");
        return centralDogmaRepo.centralDogma().listFiles(centralDogmaRepo.projectName(),
                                                         centralDogmaRepo.repositoryName(),
                                                         revision, pathPattern);
    }

    /**
     * Retrieves the files matched by the path pattern at the {@link Revision#HEAD}.
     *
     * @return a {@link Map} of file path and {@link Entry} pairs
     */
    public CompletableFuture<Map<String, Entry<?>>> get() {
        return get(Revision.HEAD);
    }

    /**
     * Retrieves the files matched by the path pattern at the {@link Revision}.
     *
     * @return a {@link Map} of file path and {@link Entry} pairs
     */
    public CompletableFuture<Map<String, Entry<?>>> get(Revision revision) {
        requireNonNull(revision, "revision");
        return centralDogmaRepo.centralDogma().getFiles(centralDogmaRepo.projectName(),
                                                        centralDogmaRepo.repositoryName(),
                                                        revision, pathPattern, viewRaw());
    }
}
