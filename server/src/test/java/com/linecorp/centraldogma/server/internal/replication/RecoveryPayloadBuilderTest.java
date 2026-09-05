/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server.internal.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.ReplicationStatus;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.RecoverRepositoryCommand;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.internal.management.RepoStatusManager;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

class RecoveryPayloadBuilderTest {

    private static final ReplayCommit COMMIT =
            new ReplayCommit(new Revision(3), 1_000L, Author.SYSTEM, "summary", "detail", Markup.PLAINTEXT,
                             ImmutableList.of(Change.ofTextUpsert("/a.txt", "a")),
                             "0123456789012345678901234567890123456789");

    /**
     * A request travels through the replication log, so the source can react to it long after an operator
     * gave up and made the repository writable again. Building the payload then would discard every commit
     * the repository took since.
     */
    @Test
    void refusesToBuildWhileTheRepositoryIsWritable() {
        final ProjectManager projectManager = mock(ProjectManager.class);
        final RepoStatusManager repoStatusManager = mock(RepoStatusManager.class);
        when(repoStatusManager.replicationStatus("foo", "bar")).thenReturn(ReplicationStatus.WRITABLE);

        final RecoveryPayloadBuilder builder = new RecoveryPayloadBuilder(projectManager, repoStatusManager);
        assertThatThrownBy(() -> builder.build(Author.SYSTEM, "foo", "bar", 1,
                                               new Revision(3), new Revision(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("foo/bar")
                .hasMessageContaining("WRITABLE");

        // The repository was never read, so nothing could have been rewritten from it.
        verifyNoInteractions(projectManager);
    }

    @Test
    void buildsWhileTheRepositoryIsReadOnly() {
        final ProjectManager projectManager = mock(ProjectManager.class, RETURNS_DEEP_STUBS);
        when(projectManager.get(anyString()).repos()
                           .buildRecoveryPayload(anyString(), any(Revision.class), any(Revision.class)))
                .thenReturn(ImmutableList.of(COMMIT));
        final RepoStatusManager repoStatusManager = mock(RepoStatusManager.class);
        when(repoStatusManager.replicationStatus("foo", "bar")).thenReturn(ReplicationStatus.READ_ONLY);

        final RecoveryPayloadBuilder builder = new RecoveryPayloadBuilder(projectManager, repoStatusManager);
        final Command<Revision> command = builder.build(Author.SYSTEM, "foo", "bar", 1,
                                                        new Revision(3), new Revision(3));

        assertThat(command).isInstanceOf(RecoverRepositoryCommand.class);
        final RecoverRepositoryCommand recoverCommand = (RecoverRepositoryCommand) command;
        assertThat(recoverCommand.resetToRevision()).isEqualTo(new Revision(2));
        assertThat(recoverCommand.toRevision()).isEqualTo(new Revision(3));
        assertThat(recoverCommand.commits()).containsExactly(COMMIT);
        assertThat(recoverCommand.sourceServerId()).isEqualTo(1);
    }

    /**
     * A recovery is originated only from the source replica, so the status it reads is the one the operator
     * froze; the mapping of a project-scoped status lives in {@link RepoStatusManager} rather than here.
     */
    @Test
    void readsTheStatusOfTheRepositoryBeingRecovered() {
        final RepoStatusManager repoStatusManager = mock(RepoStatusManager.class);
        when(repoStatusManager.replicationStatus(anyString(), anyString()))
                .thenReturn(ReplicationStatus.WRITABLE);
        when(repoStatusManager.replicationStatus("foo", "dogma")).thenReturn(ReplicationStatus.READ_ONLY);
        final ProjectManager projectManager = mock(ProjectManager.class, RETURNS_DEEP_STUBS);
        when(projectManager.get(anyString()).repos()
                           .buildRecoveryPayload(anyString(), any(Revision.class), any(Revision.class)))
                .thenReturn(ImmutableList.of(COMMIT));

        final RecoveryPayloadBuilder builder = new RecoveryPayloadBuilder(projectManager, repoStatusManager);
        assertThat(builder.build(Author.SYSTEM, "foo", "dogma", 1, new Revision(3), new Revision(3)))
                .isInstanceOf(RecoverRepositoryCommand.class);
        assertThatThrownBy(() -> builder.build(Author.SYSTEM, "foo", "other", 1,
                                               new Revision(3), new Revision(3)))
                .isInstanceOf(IllegalStateException.class);
    }
}
