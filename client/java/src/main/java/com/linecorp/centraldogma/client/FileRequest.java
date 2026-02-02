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

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryType;
import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send a {@link CentralDogma#getFile(String, String, Revision, Query)} request to the
 * Central Dogma repository.
 */
public final class FileRequest<T> extends AbstractFileRequest<FileRequest<T>> {

    private final Query<T> query;
    private final CentralDogmaRepository centralDogmaRepo;

    FileRequest(CentralDogmaRepository centralDogmaRepo, Query<T> query) {
        this.query = query;
        this.centralDogmaRepo = centralDogmaRepo;
    }

    /**
     * Retrieves a file located at {@link Query#path()} at the {@link Revision#HEAD}.
     *
     * @return the {@link Entry} that is matched by the given {@link Query}
     */
    public CompletableFuture<Entry<T>> get() {
        return get(Revision.HEAD);
    }

    /**
     * Retrieves a file located at {@link Query#path()} at the {@link Revision}.
     *
     * @return the {@link Entry} that is matched by the given {@link Query}
     */
    public CompletableFuture<Entry<T>> get(Revision revision) {
        requireNonNull(revision, "revision");
        if (viewRaw() && query.type() == QueryType.JSON_PATH) {
            throw new IllegalStateException("JSON_PATH query cannot be used with raw view");
        }
        return centralDogmaRepo.centralDogma().getFile(centralDogmaRepo.projectName(),
                                                       centralDogmaRepo.repositoryName(),
                                                       revision, query, viewRaw(),
                                                       renderTemplate(), variableFile());
    }
}
