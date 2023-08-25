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

import com.google.common.base.Objects;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.CommitRetentionConfig;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * A {@link Command} which is used for creating a new rolling repository.
 *
 * @see CommitRetentionConfig
 */
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

    /**
     * Returns a {@link Revision} that will be the initial revision of the rolling repository.
     */
    public Revision initialRevision() {
        return initialRevision;
    }

    /**
     * Returns the minimum number of commits that a {@link Repository} should retain. {@code 0} means that
     * the number of commits are not taken into account when
     * {@link Repository#shouldCreateRollingRepository(int, int)} is called.
     */
    public int minRetentionCommits() {
        return minRetentionCommits;
    }

    /**
     * Returns the minimum number of days of a commit that a {@link Repository} should retain.
     */
    public int minRetentionDays() {
        return minRetentionDays;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CreateRollingRepositoryCommand)) {
            return false;
        }
        final CreateRollingRepositoryCommand that = (CreateRollingRepositoryCommand) o;
        return super.equals(o) &&
               minRetentionCommits == that.minRetentionCommits &&
               minRetentionDays == that.minRetentionDays &&
               Objects.equal(initialRevision, that.initialRevision);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), initialRevision, minRetentionCommits, minRetentionDays);
    }

    //TODO(minwoox): Add toString() after removing ToStringHelper from public API
}
