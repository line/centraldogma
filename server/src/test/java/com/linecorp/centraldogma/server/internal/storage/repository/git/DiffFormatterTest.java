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

import static com.linecorp.centraldogma.common.Revision.HEAD;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.CrossRepositoryDiffFormatter.scan;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.pathPatternFilterOrTreeFilter;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.project.Project;

class DiffFormatterTest {

    private static final String SUMMARY = "summary";

    @TempDir
    @SuppressWarnings("WeakerAccess")
    static File repoDir;

    private static GitRepository singleRepo;

    private static GitRepository oldRepo;
    private static GitRepository newRepo;

    @BeforeAll
    static void setUp() {
        singleRepo = createRepo("single");
        oldRepo = createRepo("old");
        newRepo = createRepo("new");
        makeCommits();
    }

    @AfterAll
    static void destroy() {
        singleRepo.internalClose();
        oldRepo.internalClose();
        newRepo.internalClose();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/**", "/a.json", "/a.txt", "/b/b.json", "/b/b.txt", "/c.json", "/c.txt",
            "/aa.json", "/non-exist.json",
    })
    void testDiff(String pathPattern) throws IOException {
        //  Single repo:
        //  ----------------------------------------------------------------------------------------------------
        //  Revision     1|           2|             3|              4|               5|           6|         7|
        //  initial commit| Add /a.json|              | Modify /a.json|                |            | Rename to|
        //  initial commit| Add /a.txt |              |               |                |            | /aa.json |
        //  initial commit|            | Add /b/b.json|               |                |            |          |
        //  initial commit|            | Add /b/b.txt |               | Remove /b/b.txt|            |          |
        //  initial commit|            |              |               |                | Add /c.json|          |
        //  initial commit|            |              |               |                | Add /c.txt |          |
        //  ----------------------------------------------------------------------------------------------------

        //  Old repo contains up to the revision 3 commits of Single repo.

        //  Old repo:
        //  -------------------------------------------
        //  Revision     1|           2|             3|
        //  initial commit| Add /a.json|              |
        //  initial commit| Add /a.txt |              |
        //  initial commit|            | Add /b/b.json|
        //  initial commit|            | Add /b/b.txt |
        //  initial commit|            |              |
        //  initial commit|            |              |
        //  -------------------------------------------

        //  New repo is created with the revision 3 of old repo and contains 4 ~ 7 commits of Single repo.
        //  The revision number - 1 of Single repo is the corresponding revision of New repo.

        //  New repo:
        //  ----------------------------------------------------------------------------------------------------
        //  Revision     1|           2               |              3|               4|           5|         6|
        //  initial commit| Add /a.json               | Modify /a.json|                |            | Rename to|
        //  initial commit| Add /a.txt                |               |                |            | /aa.json |
        //  initial commit| Add /b/b.json             |               |                |            |          |
        //  initial commit| Add /b/b.txt              |               | Remove /b/b.txt|            |          |
        //  initial commit|                           |               |                | Add /c.json|          |
        //  initial commit|                           |               |                | Add /c.txt |          |
        //  ----------------------------------------------------------------------------------------------------

        for (int oldRevision = 2; oldRevision <= 3; oldRevision++) {
            for (int newRevision = 4; newRevision <= 7; newRevision++) {
                diff(pathPattern, oldRevision, newRevision);
            }
        }
    }

    private void diff(String pathPattern, int oldRevision, int newRevision) throws IOException {
        final List<DiffEntry> singleRepoDiffEntries = singleRepo.diffEntries(
                new Revision(oldRevision), new Revision(newRevision), pathPattern);

        final org.eclipse.jgit.lib.Repository oldJGitRepo = oldRepo.jGitRepository();
        final org.eclipse.jgit.lib.Repository newJGitRepo = newRepo.jGitRepository();

        final ObjectId oldObjectId = oldRepo.commitIdDatabase()
                                            .get(new Revision(oldRevision));
        final ObjectId newObjectId = newRepo.commitIdDatabase()
                                            .get(new Revision(convertedNewRevision(newRevision)));

        try (CrossRepositoryTreeWalk walk =
                     new CrossRepositoryTreeWalk(oldJGitRepo, oldObjectId, newJGitRepo,
                                                 newObjectId, pathPatternFilterOrTreeFilter(pathPattern))) {
            final List<DiffEntry> crossRepoDiffEntries = scan(walk);
            assertThat(singleRepoDiffEntries).usingElementComparator(new DiffEntryComparator())
                                             .containsExactlyElementsOf(crossRepoDiffEntries);
        }
    }

    private int convertedNewRevision(int newRevision) {
        return newRevision - 1;
    }

    private static GitRepository createRepo(String repo) {
        return new GitRepository(mock(Project.class), new File(repoDir, repo),
                                 commonPool(), 0L, Author.SYSTEM);
    }

    private static void makeCommits() {
        final Change<JsonNode> aJson = Change.ofJsonUpsert("/a.json", "{ \"a\": 1}");
        final Change<String> aTxt = Change.ofTextUpsert("/a.txt", "value:\na");
        assertThat(commit(singleRepo, aJson, aTxt)).isEqualTo(new Revision(2));
        assertThat(commit(oldRepo, aJson, aTxt)).isEqualTo(new Revision(2));

        final Change<JsonNode> bJson = Change.ofJsonUpsert("/b/b.json", "{ \"b\": 1}");
        final Change<String> bTxt = Change.ofTextUpsert("/b/b.txt", "value:\nb");
        assertThat(commit(singleRepo, bJson, bTxt)).isEqualTo(new Revision(3));
        assertThat(commit(oldRepo, bJson, bTxt)).isEqualTo(new Revision(3));

        assertThat(commit(newRepo, aJson, aTxt, bJson, bTxt)).isEqualTo(new Revision(2));

        final Change<JsonNode> aJsonModified = Change.ofJsonUpsert("/a.json", "{ \"aa\": 2}");
        assertThat(commit(singleRepo, aJsonModified)).isEqualTo(new Revision(4));
        assertThat(commit(newRepo, aJsonModified)).isEqualTo(new Revision(3));

        final Change<Void> aTxtRemoval = Change.ofRemoval("/b/b.txt");
        assertThat(commit(singleRepo, aTxtRemoval)).isEqualTo(new Revision(5));
        assertThat(commit(newRepo, aTxtRemoval)).isEqualTo(new Revision(4));

        final Change<JsonNode> cJson = Change.ofJsonUpsert("/c.json", "{ \"c\": 1}");
        final Change<String> cTxt = Change.ofTextUpsert("/c.txt", "value:\nc");
        assertThat(commit(singleRepo, cJson, cTxt)).isEqualTo(new Revision(6));
        assertThat(commit(newRepo, cJson, cTxt)).isEqualTo(new Revision(5));

        final Change<String> aRename = Change.ofRename("/a.json", "/aa.json");
        assertThat(commit(singleRepo, aRename)).isEqualTo(new Revision(7));
        assertThat(commit(newRepo, aRename)).isEqualTo(new Revision(6));
    }

    private static Revision commit(GitRepository repo, Change<?>... changes) {
        return repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, changes).join().revision();
    }

    private static class DiffEntryComparator implements Comparator<DiffEntry> {
        @Override
        public int compare(DiffEntry o1, DiffEntry o2) {
            if (o1.getOldId().equals(o2.getOldId()) &&
                o1.getOldPath().equals(o2.getOldPath()) &&
                o1.getOldMode().equals(o2.getOldMode()) &&
                o1.getNewId().equals(o2.getNewId()) &&
                o1.getNewPath().equals(o2.getNewPath()) &&
                o1.getNewMode().equals(o2.getNewMode()) &&
                o1.getChangeType() == o2.getChangeType()) {
                return 0;
            }
            return -1;
        }
    }
}
