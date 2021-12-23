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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send a {@link CentralDogma#getPreviewDiffs(String, String, Revision, Iterable)} request to the
 * Central Dogma repository.
 */
public final class PreviewDiffRequest {

    private final CentralDogmaRepository centralDogmaRepo;
    private final Iterable<? extends Change<?>> changes;

    PreviewDiffRequest(CentralDogmaRepository centralDogmaRepo,
                       Iterable<? extends Change<?>> changes) {
        this.centralDogmaRepo = centralDogmaRepo;
        this.changes = changes;
    }

    /**
     * Retrieves the <em>preview diffs</em>, which are hypothetical diffs generated if the
     * {@link Change}s were successfully pushed to the specified repository. This operation is useful for
     * pre-checking if the {@link Change}s will be applied as expected without any conflicts.
     *
     * @return the diffs which would be committed if the {@link Change}s were pushed successfully
     */
    public CompletableFuture<List<Change<?>>> get(Revision baseRevision) {
        requireNonNull(baseRevision, "baseRevision");
        return centralDogmaRepo.centralDogma().getPreviewDiffs(centralDogmaRepo.projectName(),
                                                               centralDogmaRepo.repositoryName(),
                                                               baseRevision, changes);
    }
}
