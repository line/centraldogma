/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.storage.repository;

import java.util.List;
import java.util.function.BiConsumer;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.storage.StorageManager;
import com.linecorp.centraldogma.server.storage.project.Project;

/**
 * Manages repositories which belong to a project.
 */
public interface RepositoryManager extends StorageManager<Repository> {
    /**
     * Returns the project that the repositories belong to.
     */
    Project parent();

    /**
     * Migrates the specified repository to an encrypted repository.
     */
    void migrateToEncryptedRepository(String repositoryName);

    /**
     * Falls back the specified encrypted repository to a file-based repository.
     */
    void fallbackToFileRepository(String repositoryName);

    /**
     * Recovers the specified repository by resetting it to {@code resetToRevision} and replaying the given
     * {@code commits} on top of it. Used to reconcile a diverged replica with a source replica. See
     * {@link com.linecorp.centraldogma.server.command.RecoverRepositoryCommand}.
     *
     * @return {@code true} if the repository was rewritten and its {@link Repository} instance replaced;
     *         {@code false} if it was already converged with {@code commits} and thus left untouched, which
     *         is the outcome on the source replica and on every replica that did not diverge.
     */
    default boolean recoverRepository(String repositoryName, Revision resetToRevision,
                                      List<ReplayCommit> commits) {
        throw new UnsupportedOperationException();
    }

    /**
     * Builds the {@link ReplayCommit}s of {@code fromRevision..HEAD} of the specified repository, to be
     * carried by a {@link com.linecorp.centraldogma.server.command.RecoverRepositoryCommand}. Invoked only
     * on the source replica of a recovery. {@code fromRevision} must be an absolute revision greater than 1
     * and not greater than the HEAD revision.
     */
    default List<ReplayCommit> buildRecoveryPayload(String repositoryName, Revision fromRevision) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets a callback that is invoked after a repository is migrated or fallen back.
     * The callback receives the repository name and the new {@link Repository} instance.
     */
    void setPostMigrationCallback(BiConsumer<String, Repository> callback);
}
