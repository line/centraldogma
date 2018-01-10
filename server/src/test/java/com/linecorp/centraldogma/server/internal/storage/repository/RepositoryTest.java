/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.server.internal.storage.repository;

import static com.linecorp.centraldogma.common.Revision.HEAD;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.util.concurrent.ForkJoinPool;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository.NormalizedFromToRevision;

public class RepositoryTest {

    @ClassRule
    public static final TemporaryFolder rootDir = new TemporaryFolder();

    private static ProjectManager pm;

    @Rule
    public final TestName testName = new TestName();

    private Project project;
    private Repository repo;

    @BeforeClass
    public static void init() throws Exception {
        pm = new DefaultProjectManager(rootDir.getRoot(), ForkJoinPool.commonPool(), null);
    }

    @AfterClass
    public static void destroy() {
        pm.close();
    }

    @Before
    public void setUp() {
        project = pm.create(testName.getMethodName());
        project.repos().create(Project.REPO_MAIN);
        repo = project.mainRepo();

        for (int i = 0; i < 4; i++) {
            repo.commit(HEAD, 0L, Author.UNKNOWN, "summary",
                        Change.ofJsonUpsert("/" + i + ".json", "{ \"" + i + "\": " + i + " }")).join();
        }
    }

    @Test
    public void normalizeFromToRevision() {
        final Revision revisionNegativeTwo = new Revision(-2);
        final Revision revisionTwo = new Revision(2);

        NormalizedFromToRevision normalizedFromToRevision =
                repo.normalizeFromToRevision(revisionNegativeTwo, revisionTwo);
        assertThat(normalizedFromToRevision.from()).isEqualTo(new Revision(4));
        assertThat(normalizedFromToRevision.to()).isEqualTo(new Revision(2));

        normalizedFromToRevision = repo.normalizeFromToRevision(revisionNegativeTwo, revisionTwo, true);
        assertThat(normalizedFromToRevision.from()).isEqualTo(new Revision(2));
        assertThat(normalizedFromToRevision.to()).isEqualTo(new Revision(4));

        normalizedFromToRevision = repo.normalizeFromToRevision(revisionNegativeTwo, revisionTwo, false);
        assertThat(normalizedFromToRevision.from()).isEqualTo(new Revision(4));
        assertThat(normalizedFromToRevision.to()).isEqualTo(new Revision(2));

        normalizedFromToRevision = repo.normalizeFromToRevision(revisionTwo, revisionNegativeTwo, true);
        assertThat(normalizedFromToRevision.from()).isEqualTo(new Revision(2));
        assertThat(normalizedFromToRevision.to()).isEqualTo(new Revision(4));

        normalizedFromToRevision = repo.normalizeFromToRevision(revisionTwo, revisionNegativeTwo, false);
        assertThat(normalizedFromToRevision.from()).isEqualTo(new Revision(4));
        assertThat(normalizedFromToRevision.to()).isEqualTo(new Revision(2));

        assertThatThrownBy(() -> repo.normalizeFromToRevision(new Revision(-6), revisionTwo, true))
                .isExactlyInstanceOf(RevisionNotFoundException.class);
    }
}
