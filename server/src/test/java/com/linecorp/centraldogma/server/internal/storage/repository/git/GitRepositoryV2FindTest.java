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

import com.google.common.collect.Iterables;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;

class GitRepositoryV2FindTest {

    @TempDir
    static File repoDir;
    private static GitRepositoryV2 repo;

    @BeforeAll
    static void setUp() {
        // The repository contains commits from 10 to 20(inclusive).
        repo = createRepository(repoDir);
    }

    @AfterAll
    static void tearDown() {
        repo.internalClose();
    }

    @Test
    void normalFind() {
        Map<String, Entry<?>> entries = repo.find(new Revision(20), "/**").join();
        assertThat(entries).hasSize(19); // 2 to 20 (inclusively)

        entries = repo.find(new Revision(15), "/file_15.txt").join();
        assertThat(entries.size()).isOne();
        Entry<?> entry = Iterables.get(entries.values(), 0);
        assertThat(entry.revision().major()).isEqualTo(15);
        assertThat(entry.path()).isEqualTo("/file_15.txt");

        // file_2.txt is committed together when the current primary repository is created.
        entries = repo.find(new Revision(10), "/file_2.txt").join();
        assertThat(entries.size()).isOne();
        entry = Iterables.get(entries.values(), 0);
        assertThat(entry.revision().major()).isEqualTo(10);
        assertThat(entry.path()).isEqualTo("/file_2.txt");

        entries = repo.find(new Revision(10), "/**").join();
        assertThat(entries).hasSize(9); // from 2 ~ 10
        final List<String> expected = IntStream.range(2, 11).mapToObj(i -> "/file_" + i + ".txt")
                                               .collect(toImmutableList());
        final List<String> actual = entries.values().stream().map(Entry::path).collect(toImmutableList());
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);

        // If the revision is INIT, the result is same as Revision(10) which is the first revision
        // of the new primary repository.
        assertThat(repo.find(Revision.INIT, "/**").join()).isEqualTo(entries);
    }

    @Test
    void invalidRevisionFind() {
        assertThatThrownBy(() -> repo.find(new Revision(9), "/**").join())
                .hasCauseInstanceOf(RevisionNotFoundException.class)
                .hasMessageContaining("revision: Revision(9) (expected: >= 10)");
    }

    @Test
    void findLatestRevision() {
        Revision revision = repo.findLatestRevision(new Revision(20), "/**").join();
        assertThat(revision).isNull(); // there's no commits after the revision 20 so it's null.

        revision = repo.findLatestRevision(new Revision(19), "/**").join();
        assertThat(revision.major()).isEqualTo(20);

        revision = repo.findLatestRevision(new Revision(13), "/file_15.txt").join();
        // file_15 is committed at the revision 15 which is before the revision 19.
        assertThat(revision.major()).isEqualTo(20);

        revision = repo.findLatestRevision(new Revision(19), "/file_15.txt").join();
        // file_15 is committed at the revision 15 which is before the revision 19.
        assertThat(revision).isNull();

        revision = repo.findLatestRevision(new Revision(10), "/file_5.txt").join();
        assertThat(revision).isNull(); // It's null because file_5 is not changed after the revision 10.

        revision = repo.findLatestRevision(new Revision(4), "/file_5.txt").join();
        assertThat(revision.major()).isEqualTo(20);

        revision = repo.findLatestRevision(new Revision(6), "/file_5.txt").join();
        assertThat(revision.major()).isEqualTo(20);
    }
}
