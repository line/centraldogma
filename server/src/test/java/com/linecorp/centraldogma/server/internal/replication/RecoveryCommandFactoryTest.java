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
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.ApplyRepositoryRecoveryCommand;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.RepositoryHead;

class RecoveryCommandFactoryTest {

    private static final ReplayCommit COMMIT =
            new ReplayCommit(new Revision(3), 1_000L, Author.SYSTEM, "summary", "detail", Markup.PLAINTEXT,
                             ImmutableList.of(Change.ofTextUpsert("/a.txt", "a")),
                             "0123456789012345678901234567890123456789");

    @Test
    void createsPaddedCommandFromSourceRepository() {
        final ProjectManager projectManager = mock(ProjectManager.class, RETURNS_DEEP_STUBS);
        when(projectManager.get(anyString()).repos()
                           .buildRecoveryPayload(anyString(), any(Revision.class), any(Revision.class)))
                .thenReturn(ImmutableList.of(COMMIT));
        when(projectManager.get(anyString()).repos().get(anyString()).head())
                .thenReturn(new RepositoryHead(new Revision(3), "commit", "tree"));

        final RecoveryCommandFactory factory = new RecoveryCommandFactory(projectManager);
        final Command<Revision> command = factory.blockingNewCommand(
                Author.SYSTEM, "foo", "bar", 1, new Revision(3), new Revision(3), 3);

        assertThat(command).isInstanceOf(ApplyRepositoryRecoveryCommand.class);
        final ApplyRepositoryRecoveryCommand recoverCommand = (ApplyRepositoryRecoveryCommand) command;
        assertThat(recoverCommand.resetToRevision()).isEqualTo(new Revision(2));
        assertThat(recoverCommand.toRevision()).isEqualTo(new Revision(4));
        assertThat(recoverCommand.commits()).hasSize(2);
        assertThat(recoverCommand.commits().get(0)).isEqualTo(COMMIT);
        final ReplayCommit padding = recoverCommand.commits().get(1);
        assertThat(padding.revision()).isEqualTo(new Revision(4));
        assertThat(padding.timestampMillis()).isZero();
        assertThat(padding.author()).isEqualTo(Author.SYSTEM);
        assertThat(padding.summary()).isEqualTo("Recovery padding");
        assertThat(padding.changes()).isEmpty();
        assertThat(padding.expectedTreeId()).isEqualTo(COMMIT.expectedTreeId());
        assertThat(recoverCommand.sourceServerId()).isEqualTo(1);
    }

    @Test
    void carriesTheRecoveryRevisionPastTheClusterMaximum() {
        final ProjectManager projectManager = mock(ProjectManager.class, RETURNS_DEEP_STUBS);
        when(projectManager.get(anyString()).repos()
                           .buildRecoveryPayload(anyString(), any(Revision.class), any(Revision.class)))
                .thenReturn(ImmutableList.of(COMMIT));
        when(projectManager.get(anyString()).repos().get(anyString()).head())
                .thenReturn(new RepositoryHead(new Revision(3), "commit", "tree"));

        final RecoveryCommandFactory factory = new RecoveryCommandFactory(projectManager);
        final ApplyRepositoryRecoveryCommand command =
                (ApplyRepositoryRecoveryCommand) factory.blockingNewCommand(
                        Author.SYSTEM, "foo", "bar", 1, new Revision(3), new Revision(3), 5);

        assertThat(command.toRevision()).isEqualTo(new Revision(6));
        assertThat(command.commits()).extracting(ReplayCommit::revision)
                                     .containsExactly(new Revision(3), new Revision(4),
                                                      new Revision(5), new Revision(6));
        assertThat(command.commits().subList(1, 4))
                .allSatisfy(commit -> {
                    assertThat(commit.changes()).isEmpty();
                    assertThat(commit.expectedTreeId()).isEqualTo(COMMIT.expectedTreeId());
                });
    }

    @Test
    void rejectsARecoveryWhenTheSourceHeadExceedsTheClusterMaximum() {
        final ProjectManager projectManager = mock(ProjectManager.class, RETURNS_DEEP_STUBS);
        when(projectManager.get("foo").repos().get("bar").head())
                .thenReturn(new RepositoryHead(new Revision(6), "commit", "tree"));
        final RecoveryCommandFactory factory = new RecoveryCommandFactory(projectManager);

        assertThatThrownBy(() -> factory.blockingNewCommand(
                Author.SYSTEM, "foo", "bar", 1, new Revision(3), new Revision(3), 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source head")
                .hasMessageContaining("6")
                .hasMessageContaining("5");
    }
}
