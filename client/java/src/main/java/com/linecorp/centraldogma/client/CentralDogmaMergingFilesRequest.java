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

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send a {@link CentralDogma#mergeFiles(String, String, Revision, MergeQuery)} request to the
 * Central Dogma repository.
 */
public final class CentralDogmaMergingFilesRequest<T> {

    private final CentralDogmaRequestPreparation requestPreparation;
    private final MergeQuery<T> mergeQuery;

    CentralDogmaMergingFilesRequest(CentralDogmaRequestPreparation requestPreparation,
                                    MergeQuery<T> mergeQuery) {
        this.requestPreparation = requestPreparation;
        this.mergeQuery = mergeQuery;
    }

    /**
     * Retrieves the merged entry of the {@link MergeQuery} at the specified revision.
     * Only JSON entry merge is currently supported. The JSON files are merged sequentially as specified in
     * the {@link MergeQuery}.
     *
     * <p>Note that only {@link ObjectNode} is recursively merged traversing the children. Other node types are
     * simply replaced.
     *
     * @return the {@link MergedEntry} which contains the result of the merge
     */
    public CompletableFuture<MergedEntry<T>> get(Revision revision) {
        requireNonNull(revision, "revision");
        return requestPreparation.centralDogma().mergeFiles(requestPreparation.projectName(),
                                                            requestPreparation.repositoryName(),
                                                            revision, mergeQuery);
    }
}
