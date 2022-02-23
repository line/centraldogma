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

import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send a {@link CentralDogma#getDiff(String, String, Revision, Revision, Query)} request to the
 * Central Dogma repository.
 */
public final class DiffRequest<T> {

    private final CentralDogmaRepository centralDogmaRepo;
    private final Query<T> query;

    DiffRequest(CentralDogmaRepository centralDogmaRepo, Query<T> query) {
        this.centralDogmaRepo = centralDogmaRepo;
        this.query = query;
    }

    /**
     * Queries a file at two different revisions and returns the diff of the two {@link Query} results.
     *
     * @return the {@link Change} that contains the diff of the file matched by the given {@code query}
     *         between the specified two revisions
     */
    public CompletableFuture<Change<T>> get(Revision from, Revision to) {
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        return centralDogmaRepo.centralDogma().getDiff(centralDogmaRepo.projectName(),
                                                       centralDogmaRepo.repositoryName(),
                                                       from, to, query);
    }
}
