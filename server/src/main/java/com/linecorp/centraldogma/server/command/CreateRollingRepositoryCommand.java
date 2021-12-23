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
package com.linecorp.centraldogma.server.command;

import static java.util.Objects.requireNonNull;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;

public final class CreateRollingRepositoryCommand extends RepositoryCommand<Void> {

    private final Revision initialRevision;
    private final int minRetentionCommits;
    private final int minRetentionDays;

    CreateRollingRepositoryCommand(String projectName, String repositoryName,
                                   Revision initialRevision, int minRetentionCommits, int minRetentionDays) {
        super(CommandType.CREATE_ROLLING_REPOSITORY, null, Author.SYSTEM, projectName, repositoryName);
        this.initialRevision = requireNonNull(initialRevision, "initialRevision");
        this.minRetentionCommits = minRetentionCommits;
        this.minRetentionDays = minRetentionDays;
    }

    public Revision initialRevision() {
        return initialRevision;
    }

    public int minRetentionCommits() {
        return minRetentionCommits;
    }

    public int minRetentionDays() {
        return minRetentionDays;
    }
}
