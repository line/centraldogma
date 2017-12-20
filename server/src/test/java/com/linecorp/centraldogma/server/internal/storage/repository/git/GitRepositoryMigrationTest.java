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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryFormat.V0;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryFormat.V1;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

public class GitRepositoryMigrationTest {

    private static final Logger logger = LoggerFactory.getLogger(GitRepositoryMigrationTest.class);

    private static final Project proj = mock(Project.class);

    @Rule
    public final TemporaryFolder tempDir = new TemporaryFolder();

    /**
     * Makes sure {@link GitRepository} can create the legacy format repository, with Git repository format
     * version 0 and hexadecimal tag encoding.
     */
    @Test
    public void legacyFormatCreation() {
        final File repoDir0 = tempDir.getRoot();
        final GitRepository repo0 = new GitRepository(proj, repoDir0, V0, commonPool(), 0L, Author.SYSTEM);
        try {
            assertThat(repo0.format()).isSameAs(V0);
            assertThat(Paths.get(repoDir0.getPath(), "refs", "heads", "master"))
                    .existsNoFollowLinks()
                    .isRegularFile();
        } finally {
            repo0.close();
        }
    }

    /**
     * Makes sure {@link GitRepository} can create the modern format repository, with Git repository format
     * version 1 and simple tag encoding.
     */
    @Test
    public void modernFormatCreation() {
        final File repoDir1 = tempDir.getRoot();
        final GitRepository repo1 = new GitRepository(proj, repoDir1, commonPool(), 0L, Author.SYSTEM);
        try {
            assertThat(repo1.format()).isSameAs(V1);
            assertThat(Paths.get(repoDir1.getPath(), "refs", "heads", "master"))
                    .existsNoFollowLinks()
                    .isRegularFile();
        } finally {
            repo1.close();
        }
    }

    /**
     * Makes sure a legacy {@link GitRepository} is migrated to the modern format.
     */
    @Test
    public void singleRepositoryMigration() {
        final File repoDir0 = new File(tempDir.getRoot(), "legacy");
        final File repoDir1 = new File(tempDir.getRoot(), "modern");
        final GitRepository repo0 = new GitRepository(proj, repoDir0, V0, commonPool(), 0, Author.SYSTEM);
        try {
            assertThat(repo0.format()).isSameAs(V0);

            // Put some commits into the legacy repo.
            for (int i = 1; i < 128; i++) {
                repo0.commit(new Revision(i), i * 1000, new Author("user" + rnd() + "@example.com"),
                             "Summary " + rnd(), "Detail " + rnd(), Markup.PLAINTEXT,
                             Change.ofTextUpsert("/file_" + rnd() + ".txt", "content " + i)).join();
            }

            // Build a clone in modern format.
            repo0.cloneTo(repoDir1);

            final GitRepository repo1 = new GitRepository(proj, repoDir1, commonPool());
            try {
                assertThat(repo1.format()).isSameAs(V1);

                // Make sure all commits are identical.
                final List<Commit> commits0 = repo0.history(
                        Revision.INIT, Revision.HEAD, Repository.ALL_PATH, Integer.MAX_VALUE).join();
                final List<Commit> commits1 = repo1.history(
                        Revision.INIT, Revision.HEAD, Repository.ALL_PATH, Integer.MAX_VALUE).join();

                assertThat(commits1).isEqualTo(commits0);

                // Make sure all commits are ordered correctly.
                for (int i = 0; i < commits0.size(); i++) {
                    assertThat(commits0.get(i).revision().major()).isEqualTo(i + 1);
                }

                // Make sure the changes of all commits are identical.
                for (Commit c : commits0) {
                    if (c.revision().major() == 1) {
                        continue;
                    }

                    final Map<String, Change<?>> changes0 = repo0.diff(
                            c.revision().backward(1), c.revision(), Repository.ALL_PATH).join();
                    final Map<String, Change<?>> changes1 = repo0.diff(
                            c.revision().backward(1), c.revision(), Repository.ALL_PATH).join();

                    if (changes0.equals(changes1)) {
                        logger.debug("{}: {}", c.revision().major(), changes0);
                    } else {
                        logger.warn("{}: {} vs. {}", c.revision().major(), changes0, changes1);
                    }

                    assertThat(changes1)
                            .withFailMessage("mismatching changes for revision %s", c.revision().major())
                            .isEqualTo(changes0);
                }
            } finally {
                repo1.close();
            }
        } finally {
            repo0.close();
        }
    }

    /**
     * Makes sure {@link GitRepositoryManager} performs migration.
     */
    @Test
    public void multipleRepositoryMigration() {
        final File tempDir = this.tempDir.getRoot();
        // Create repositories of older format.
        try (GitRepositoryManager manager = new GitRepositoryManager(proj, tempDir, V0, commonPool())) {
            assertThat(((GitRepository) manager.create("foo")).format()).isSameAs(V0);
            assertThat(((GitRepository) manager.create("bar")).format()).isSameAs(V0);
        }

        // Load the repositories with newer format to trigger automatic migration.
        try (GitRepositoryManager manager = new GitRepositoryManager(proj, tempDir, commonPool())) {
            assertThat(((GitRepository) manager.get("foo")).format()).isSameAs(V1);
            assertThat(((GitRepository) manager.get("bar")).format()).isSameAs(V1);
        }
    }

    private static int rnd() {
        return ThreadLocalRandom.current().nextInt(10);
    }
}
