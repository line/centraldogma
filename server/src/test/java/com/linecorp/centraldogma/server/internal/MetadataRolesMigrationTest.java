/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.server.internal;

import static com.linecorp.centraldogma.internal.jsonpatch.JsonPatchOperation.asJsonArray;
import static com.linecorp.centraldogma.server.metadata.MetadataService.METADATA_JSON;
import static com.linecorp.centraldogma.server.storage.project.Project.REPO_DOGMA;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.jsonpatch.AddOperation;
import com.linecorp.centraldogma.internal.jsonpatch.TestAbsenceOperation;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class MetadataRolesMigrationTest {

    private static final String TEST_PROJ = "fooProj";
    private static final String TEST_REPO = "barRepo";

    @RegisterExtension
    static ProjectManagerExtension projectManagerExtension = new ProjectManagerExtension() {

        @Override
        protected void afterExecutorStarted() {
            final ProjectManager projectManager = projectManagerExtension.projectManager();
            final Project project = projectManager.create(TEST_PROJ, Author.SYSTEM);
            final RepositoryManager repoManager = project.repos();
            repoManager.create(TEST_REPO, Author.SYSTEM);

            final JsonPointer path = JsonPointer.compile("/repos/" + TEST_REPO);
            final String legacyFormat = '{' +
                                        "  \"name\": \"" + TEST_REPO + "\"," +
                                        "  \"perRolePermissions\": {" +
                                        "    \"owner\": [" +
                                        "      \"READ\"," +
                                        "      \"WRITE\"" +
                                        "    ]," +
                                        "    \"member\": []," +
                                        "    \"guest\": []" +
                                        "  }," +
                                        "  \"perUserPermissions\": {}," +
                                        "  \"perTokenPermissions\": {" +
                                        "    \"token1\": [" +
                                        "      \"READ\"" +
                                        "    ]" +
                                        "  }," +
                                        "  \"creation\": {" +
                                        "    \"user\": \"aaa@linecorp.com\"," +
                                        "    \"timestamp\": \"2024-11-23T12:28:05.472983Z\"" +
                                        "  }" +
                                        '}';
            try {
                final Change<JsonNode> change =
                        Change.ofJsonPatch(METADATA_JSON,
                                           asJsonArray(new TestAbsenceOperation(path),
                                                       new AddOperation(path,
                                                                        Jackson.readTree(legacyFormat))));
                projectManagerExtension.executor().execute(
                        Command.push(Author.SYSTEM, TEST_PROJ, REPO_DOGMA, Revision.HEAD,
                                     "Add", "", Markup.PLAINTEXT, change)).join();
            } catch (JsonParseException e) {
                throw new RuntimeException(e);
            }
        }
    };

    @Test
    void migrate() throws Exception {
        final ProjectManager projectManager = projectManagerExtension.projectManager();
        final Project project = projectManager.get(TEST_PROJ);
        final Repository dogmaRepository = project.repos().get(REPO_DOGMA);
        final MetadataService metadataService = new MetadataService(projectManager,
                                                                    projectManagerExtension.executor());
        metadataService.migrateMetadata(TEST_PROJ).join();
        final Entry<JsonNode> entry = dogmaRepository.get(Revision.HEAD, Query.ofJson(METADATA_JSON)).join();
        assertThatJson(entry.content().get("repos").get(TEST_REPO))
                .isEqualTo('{' +
                           "  \"name\": \"barRepo\"," +
                           "  \"creation\": {" +
                           "    \"user\": \"aaa@linecorp.com\"," +
                           "    \"timestamp\": \"2024-11-23T12:28:05.472983Z\"" +
                           "  }," +
                           "  \"roles\": {" +
                           "    \"projects\": {" +
                           "      \"member\": null," +
                           "      \"guest\": null" +
                           "    }," +
                           "    \"users\": { }," +
                           "    \"tokens\": {" +
                           "      \"token1\": \"READ\"" +
                           "    }" +
                           "  }" +
                           '}');
    }
}
