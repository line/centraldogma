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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send a {@link CentralDogma#getDiffs(String, String, Revision, Revision, String)} request to the
 * Central Dogma repository.
 */
public final class CentralDogmaDiffsRequest {

    private final CentralDogmaRequestPreparation requestPreparation;
    private final String pathPattern;

    CentralDogmaDiffsRequest(CentralDogmaRequestPreparation requestPreparation, String pathPattern) {
        this.requestPreparation = requestPreparation;
        this.pathPattern = pathPattern;
    }

    /**
     * Retrieves the diffs of the files matched by the given path pattern between two {@link Revision}s.
     *
     * @return a {@link List} of the {@link Change}s that contain the diffs between the files matched by the
     *         given {@code pathPattern} between two revisions.
     */
    public CompletableFuture<List<Change<?>>> get(Revision from, Revision to) {
        return requestPreparation.centralDogma().getDiffs(requestPreparation.projectName(),
                                                          requestPreparation.repositoryName(),
                                                          from, to, pathPattern);
    }
}
