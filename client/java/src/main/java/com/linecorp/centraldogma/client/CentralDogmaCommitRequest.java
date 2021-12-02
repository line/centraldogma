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
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send a {@link CentralDogma#push(String, String, Revision, String, String, Markup, Iterable)}
 * request to the Central Dogma repository.
 */
public final class CentralDogmaCommitRequest {

    private final CentralDogmaRequestPreparation requestPreparation;
    private final String summary;
    private final Iterable<? extends Change<?>> changes;

    private String detail = "";
    private Markup markup = Markup.PLAINTEXT;

    CentralDogmaCommitRequest(CentralDogmaRequestPreparation requestPreparation,
                              String summary, Iterable<? extends Change<?>> changes) {
        this.requestPreparation = requestPreparation;
        this.summary = summary;
        this.changes = changes;
    }

    /**
     * Sets the detail and {@link Markup} of a {@link Commit}.
     */
    public CentralDogmaCommitRequest detail(String detail, Markup markup) {
        this.detail = requireNonNull(detail, "detail");
        this.markup = requireNonNull(markup, "markup");
        return this;
    }

    /**
     * Pushes the {@link Change}s to the repository.
     *
     * @return the {@link PushResult} which tells the {@link Revision} and timestamp of the new {@link Commit}
     */
    public CompletableFuture<PushResult> push(Revision baseRevision) {
        requireNonNull(baseRevision, "baseRevision");
        return requestPreparation.centralDogma().push(requestPreparation.projectName(),
                                                      requestPreparation.repositoryName(),
                                                      baseRevision, summary, detail, markup, changes);
    }
}
