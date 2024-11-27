/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.ContentTransformer;

final class TransformingCommitExecutor extends AbstractCommitExecutor {

    private final ContentTransformer<JsonNode> transformer;

    TransformingCommitExecutor(GitRepository gitRepository, long commitTimeMillis,
                               Author author, String summary, String detail,
                               Markup markup, ContentTransformer<?> transformer) {
        super(gitRepository, commitTimeMillis, author, summary, detail, markup, false);
        checkArgument(transformer.entryType() == EntryType.JSON,
                      "transformer: %s (expected: JSON type)", transformer);
        //noinspection unchecked
        this.transformer = (ContentTransformer<JsonNode>) transformer;
    }

    @Override
    Iterable<Change<?>> getOrCreateApplyingChanges(Revision normBaseRevision) {
        return gitRepository.blockingPreviewDiff(normBaseRevision, new TransformingChangesApplier(transformer))
                            .values();
    }
}
