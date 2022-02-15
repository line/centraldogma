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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.HistoryConstants;

/**
 * Prepares to send a {@link CentralDogma#getHistory(String, String, Revision, Revision, PathPattern, int)}
 * request to the Central Dogma repository.
 */
public final class HistoryRequest {

    private final CentralDogmaRepository centralDogmaRepo;
    private final PathPattern pathPattern;
    private int maxCommits;

    HistoryRequest(CentralDogmaRepository centralDogmaRepo, PathPattern pathPattern) {
        this.centralDogmaRepo = centralDogmaRepo;
        this.pathPattern = pathPattern;
    }

    /**
     * Sets the maximum number of commits to retrieve. {@code 0} is used by default which means to retreive
     * all commits. The number of retrieved commits can't be greater than
     * {@value HistoryConstants#MAX_MAX_COMMITS}.
     */
    public HistoryRequest maxCommits(int maxCommits) {
        checkArgument(maxCommits >= 0 && maxCommits <= HistoryConstants.MAX_MAX_COMMITS,
                      "maxCommits: %s (expected: 0 <= maxCommits <= %s)",
                      maxCommits, HistoryConstants.MAX_MAX_COMMITS);
        this.maxCommits = maxCommits;
        return this;
    }

    /**
     * Retrieves the history of the files matched by the given path pattern between two {@link Revision}s.
     *
     * <p>Note that this method does not retrieve the diffs but only metadata about the changes.
     *
     * @return a {@link List} that contains the {@link Commit}s of the files matched by the given
     *         {@link PathPattern} in the specified repository
     */
    public CompletableFuture<List<Commit>> get(Revision from, Revision to) {
        requireNonNull(from, "from");
        requireNonNull(to, "to");
        return centralDogmaRepo.centralDogma().getHistory(centralDogmaRepo.projectName(),
                                                          centralDogmaRepo.repositoryName(),
                                                          from, to, pathPattern, maxCommits);
    }
}
