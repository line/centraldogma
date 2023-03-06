/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.mirror;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class MirroringMigrationServiceTest {

    private static final String TEST_PROJ = "fooProj";
    private static final String TEST_REPO0 = "repo0";
    private static final String TEST_REPO1 = "repo1";
    private static final String TEST_REPO2 = "repo2";

    @RegisterExtension
    static ProjectManagerExtension projectManagerExtension = new ProjectManagerExtension();

    @Test
    void shouldMigrateMirrorsJson() throws Exception {
        final ProjectManager projectManager = projectManagerExtension.projectManager();
        final Project project = projectManager.create(TEST_PROJ, Author.SYSTEM);
        final RepositoryManager repoManager = project.repos();
        repoManager.create(TEST_REPO0, Author.SYSTEM);
        repoManager.create(TEST_REPO1, Author.SYSTEM);
        repoManager.create(TEST_REPO2, Author.SYSTEM);

        // A mirror config without ID
        final String repo0Mirror = "{\n" +
                                   "  \"type\": \"single\",\n" +
                                   "  \"enabled\": true,\n" +
                                   "  \"schedule\": \"0 * * * * ?\",\n" +
                                   "  \"direction\": \"REMOTE_TO_LOCAL\",\n" +
                                   "  \"localRepo\": \"" + TEST_REPO0 + "\",\n" +
                                   "  \"localPath\": \"/\",\n" +
                                   "  \"remoteUri\": \"git+ssh://git.example.com/foo.git/settings#release\",\n" +
                                   "  \"credentialId\": \"my_private_key\",\n" +
                                   "  \"gitignore\": [\n" +
                                   "      \"/credential.txt\",\n" +
                                   "      \"private_dir\"\n" +
                                   "  ]\n" +
                                   '}';

        // A mirror config with ID
        final String repo1Mirror = "{\n" +
                                   "  \"id\": \"mirror-1\",\n" +
                                   "  \"type\": \"single\",\n" +
                                   "  \"enabled\": true,\n" +
                                   "  \"schedule\": \"0 * * * * ?\",\n" +
                                   "  \"direction\": \"REMOTE_TO_LOCAL\",\n" +
                                   "  \"localRepo\": \"" + TEST_REPO1 + "\",\n" +
                                   "  \"localPath\": \"/\",\n" +
                                   "  \"remoteUri\": \"git+ssh://git.example.com/foo.git/settings#release\",\n" +
                                   "  \"credentialId\": \"my_private_key\",\n" +
                                   "  \"gitignore\": [\n" +
                                   "      \"/credential.txt\",\n" +
                                   "      \"private_dir\"\n" +
                                   "  ]\n" +
                                   '}';

        // A mirror config with duplicate ID
        final String repo2Mirror = "{\n" +
                                   "  \"id\": \"mirror-1\",\n" +
                                   "  \"type\": \"single\",\n" +
                                   "  \"enabled\": true,\n" +
                                   "  \"schedule\": \"0 * * * * ?\",\n" +
                                   "  \"direction\": \"REMOTE_TO_LOCAL\",\n" +
                                   "  \"localRepo\": \"" + TEST_REPO2 + "\",\n" +
                                   "  \"localPath\": \"/\",\n" +
                                   "  \"remoteUri\": \"git+ssh://git.example.com/foo.git/settings#release\",\n" +
                                   "  \"credentialId\": \"my_private_key\",\n" +
                                   "  \"gitignore\": [\n" +
                                   "      \"/credential.txt\",\n" +
                                   "      \"private_dir\"\n" +
                                   "  ]\n" +
                                   '}';

        final String mirrorsJson = '[' + repo0Mirror + ',' + repo1Mirror + ',' + repo2Mirror + ']';
        project.metaRepo().commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                                  "Create a new mirrors.json",
                                  Change.ofJsonUpsert("/mirrors.json", mirrorsJson)).join();
        final MirroringMigrationService migrationService = new MirroringMigrationService(projectManager);
        migrationService.migrate();

        final Map<String, Entry<?>> entries = project.metaRepo()
                                                     .find(Revision.HEAD, "/mirrors/*.json")
                                                     .join();

        assertThat(entries).hasSize(3);
        final Map<String, Map.Entry<String, Entry<?>>> mirrors =
                entries.entrySet().stream()
                       .collect(toImmutableMap(e -> {
                           try {
                               return e.getValue().contentAsJson().get("localRepo").asText();
                           } catch (JsonParseException ex) {
                               throw new RuntimeException(ex);
                           }
                       }, Function.identity()));

        assertMirrorConfig(mirrors.get(TEST_REPO0), "mirror-" + TEST_PROJ + '-' + TEST_REPO0 + "-[a-z]+",
                           repo0Mirror);
        assertMirrorConfig(mirrors.get(TEST_REPO1), "mirror-1", repo1Mirror);
        assertMirrorConfig(mirrors.get(TEST_REPO2), "mirror-1-[0-9a-f]+", repo2Mirror);
    }

    private static void assertMirrorConfig(Map.Entry<String, Entry<?>> actualMirrorConfig, String mirrorId,
                                           String expectedMirrorConfig) throws JsonParseException {
        assertThat(actualMirrorConfig.getKey()).matches("/mirrors/" + mirrorId + "\\.json");
        final JsonNode mirrorConfig = actualMirrorConfig.getValue().contentAsJson();
        assertThat(mirrorConfig.get("id").asText()).matches(mirrorId);
        assertThatJson(mirrorConfig).whenIgnoringPaths("id")
                                    .isEqualTo(expectedMirrorConfig);
    }
}
