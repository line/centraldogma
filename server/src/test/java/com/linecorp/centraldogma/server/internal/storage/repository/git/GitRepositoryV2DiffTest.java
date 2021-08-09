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
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryV2HistoryTest.createRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;

class GitRepositoryV2DiffTest {

    @TempDir
    static File repoDir;
    private static GitRepositoryV2 repo;

    @BeforeAll
    static void setUp() {
        // The repository contains commits from 10 to 20(inclusive).
        repo = createRepository(repoDir, 10, 20);
    }

    @AfterAll
    static void tearDown() {
        repo.internalClose();
    }

    @Test
    void diff() {
        Map<String, Change<?>> diffs = repo.diff(new Revision(18), new Revision(20), "/**").join();
        assertThat(diffs.values()).containsExactly(
                Change.ofTextUpsert("/file_19.txt", "19" + System.lineSeparator()),
                Change.ofTextUpsert("/file_20.txt", "20" + System.lineSeparator()));

        // It's same when the revisions are reversed.
        assertThat(repo.diff(Revision.HEAD, new Revision(18), "/**").join().values())
                .containsExactlyInAnyOrderElementsOf(diffs.values());

        diffs = repo.diff(new Revision(10), new Revision(20), "/**").join();
        assertThat(diffs).hasSize(10); // from 11 to 20.
        final List<Change<?>> expected =
                IntStream.range(11, 21)
                         .mapToObj(i -> Change.ofTextUpsert("/file_" + i + ".txt", i + System.lineSeparator()))
                         .collect(toImmutableList());
        assertThat(diffs.values()).containsExactlyInAnyOrderElementsOf(expected);

        // If the revision is INIT, the result is same as Revision(10) which is the first revision
        // of the new primary repository.
        assertThat(repo.diff(Revision.INIT, new Revision(20), "/**").join().values())
                .containsExactlyInAnyOrderElementsOf(diffs.values());

        assertThat(repo.diff(Revision.INIT, Revision.INIT, "/**").join()).isEmpty();
    }

    @Test
    void invalidRevisionDiff() {
        assertThatThrownBy(() -> repo.diff(new Revision(9), new Revision(10), "/**").join())
                .hasRootCauseInstanceOf(RevisionNotFoundException.class)
                .hasRootCauseMessage("revision: Revision(9) (expected: >= 10)");

        assertThatThrownBy(() -> repo.diff(Revision.INIT, new Revision(9), "/**").join())
                .hasRootCauseInstanceOf(RevisionNotFoundException.class)
                .hasRootCauseMessage("revision: Revision(9) (expected: >= 10)");

        assertThatThrownBy(() -> repo.diff(Revision.HEAD, new Revision(9), "/**").join())
                .hasRootCauseInstanceOf(RevisionNotFoundException.class)
                .hasRootCauseMessage("revision: Revision(9) (expected: >= 10)");
    }
}
