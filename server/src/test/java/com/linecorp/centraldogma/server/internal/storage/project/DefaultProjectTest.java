/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.storage.project;

import static com.linecorp.centraldogma.server.internal.storage.project.DefaultProject.META_TO_DOGMA_MIGRATED;
import static com.linecorp.centraldogma.server.internal.storage.project.DefaultProject.META_TO_DOGMA_MIGRATION_JOB;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class DefaultProjectTest {

    @SuppressWarnings("JUnitMalformedDeclaration")
    @RegisterExtension
    final ProjectManagerExtension extension = new ProjectManagerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void resetMetaRepository() {
        ProjectManager projectManager = extension.projectManager();
        Project foo = projectManager.create("foo", Author.SYSTEM);
        // Meta repository is created when a project is created.
        assertThat(foo.repos().list().keySet()).containsExactlyInAnyOrder("dogma", "meta");
        assertThat(foo.metaRepo().name()).isEqualTo("meta");

        // Push migrated commit to the dogma repository in foo project.
        final Change<JsonNode> change = Change.ofJsonUpsert(META_TO_DOGMA_MIGRATED, "{ \"a\": \"b\" }");
        foo.repos().get("dogma").commit(Revision.HEAD, 0L, Author.SYSTEM, "", ImmutableList.of(change)).join();

        final MetaRepository metaRepository = foo.resetMetaRepository();
        assertThat(metaRepository.name()).isEqualTo("dogma");

        // Recreate the project manager to open the foo project again.
        extension.recreateProjectManager();
        projectManager = extension.projectManager();
        foo = projectManager.get("foo");
        // It still has the meta repository which isn't removed.
        assertThat(foo.repos().list().keySet()).containsExactlyInAnyOrder("dogma", "meta");
        // Now, it's the dogma repository.
        assertThat(foo.metaRepo().name()).isEqualTo("dogma");
    }

    @Test
    void metaRepositoryNotCreatedIfMigrationDone() {
        final ProjectManager projectManager = extension.projectManager();
        // Push migration job commit to the dogma project.
        final Change<JsonNode> change = Change.ofJsonUpsert(META_TO_DOGMA_MIGRATION_JOB, "{ \"a\": \"b\" }");
        projectManager.get("dogma").repos().get("dogma")
                      .commit(Revision.HEAD, 0L, Author.SYSTEM, "", ImmutableList.of(change)).join();

        final Project foo = projectManager.create("foo", Author.SYSTEM);
        // Meta repository is not created when a project is created.
        assertThat(foo.repos().list().keySet()).containsExactlyInAnyOrder("dogma");
        assertThat(foo.metaRepo().name()).isEqualTo("dogma");

        // No exception is raised while reopening the project.
        extension.recreateProjectManager();
    }
}
