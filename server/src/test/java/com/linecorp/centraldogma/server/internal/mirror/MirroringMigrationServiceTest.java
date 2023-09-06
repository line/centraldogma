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
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationService.PATH_LEGACY_CREDENTIALS;
import static com.linecorp.centraldogma.server.internal.mirror.MirroringMigrationService.PATH_LEGACY_MIRRORS;
import static com.linecorp.centraldogma.server.internal.mirror.credential.PublicKeyMirrorCredentialTest.PASSPHRASE;
import static com.linecorp.centraldogma.server.internal.mirror.credential.PublicKeyMirrorCredentialTest.PRIVATE_KEY;
import static com.linecorp.centraldogma.server.internal.mirror.credential.PublicKeyMirrorCredentialTest.PUBLIC_KEY;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
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
    static ProjectManagerExtension projectManagerExtension = new ProjectManagerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void afterExecutorStarted() {
            final ProjectManager projectManager = projectManagerExtension.projectManager();
            final Project project = projectManager.create(TEST_PROJ, Author.SYSTEM);
            final RepositoryManager repoManager = project.repos();
            repoManager.create(TEST_REPO0, Author.SYSTEM);
            repoManager.create(TEST_REPO1, Author.SYSTEM);
            repoManager.create(TEST_REPO2, Author.SYSTEM);
        }
    };

    // A mirror config without ID
    private final String repo0Mirror = "{\n" +
                               "  \"type\": \"single\",\n" +
                               "  \"enabled\": true,\n" +
                               "  \"schedule\": \"0 * * * * ?\",\n" +
                               "  \"direction\": \"REMOTE_TO_LOCAL\",\n" +
                               "  \"localRepo\": \"" + TEST_REPO0 + "\",\n" +
                               "  \"localPath\": \"/\",\n" +
                               "  \"remoteUri\": \"git+ssh://git.foo.com/foo.git/settings#release\",\n" +
                               "  \"gitignore\": [\n" +
                               "      \"/credential.txt\",\n" +
                               "      \"private_dir\"\n" +
                               "  ]\n" +
                               '}';

    // A mirror config with ID
    private final String repo1Mirror = "{\n" +
                               "  \"id\": \"mirror-1\",\n" +
                               "  \"type\": \"single\",\n" +
                               "  \"enabled\": true,\n" +
                               "  \"schedule\": \"0 * * * * ?\",\n" +
                               "  \"direction\": \"REMOTE_TO_LOCAL\",\n" +
                               "  \"localRepo\": \"" + TEST_REPO1 + "\",\n" +
                               "  \"localPath\": \"/\",\n" +
                               "  \"remoteUri\": \"git+ssh://git.bar.com/foo.git/settings#release\",\n" +
                               "  \"credentialId\": \"credential-1\",\n" +
                               "  \"gitignore\": [\n" +
                               "      \"/credential.txt\",\n" +
                               "      \"private_dir\"\n" +
                               "  ]\n" +
                               '}';

    // A mirror config with duplicate ID
    private final String repo2Mirror = "{\n" +
                               "  \"id\": \"mirror-1\",\n" +
                               "  \"type\": \"single\",\n" +
                               "  \"enabled\": true,\n" +
                               "  \"schedule\": \"0 * * * * ?\",\n" +
                               "  \"direction\": \"REMOTE_TO_LOCAL\",\n" +
                               "  \"localRepo\": \"" + TEST_REPO2 + "\",\n" +
                               "  \"localPath\": \"/\",\n" +
                               "  \"remoteUri\": \"git+ssh://git.qux.com/foo.git/settings#release\",\n" +
                               "  \"gitignore\": [\n" +
                               "      \"/credential.txt\",\n" +
                               "      \"private_dir\"\n" +
                               "  ]\n" +
                               '}';

    // A credential without ID
    private final String publicKeyCredential =
            '{' +
            "  \"type\": \"public_key\"," +
            "  \"hostnamePatterns\": [" +
            "    \"^git\\\\.foo\\\\.com$\"" +
            "  ]," +
            "  \"username\": \"trustin\"," +
            "  \"publicKey\": \"" + Jackson.escapeText(PUBLIC_KEY) + "\"," +
            "  \"privateKey\": \"" + Jackson.escapeText(PRIVATE_KEY) + "\"," +
            "  \"passphrase\": \"" + Jackson.escapeText(PASSPHRASE) + '"' +
            '}';

    // A credential with ID
    private final String passwordCredential =
            '{' +
            "  \"id\": \"credential-1\"," +
            "  \"type\": \"password\"," +
            "  \"hostnamePatterns\": [" +
            "    \".*.bar\\\\.com$\"" +
            "  ]," +
            "  \"username\": \"trustin\"," +
            "  \"password\": \"sesame\"" +
            '}';

    // A credential with duplicate ID
    private final String accessTokenCredential =
            '{' +
            "  \"id\": \"credential-1\"," +
            "  \"type\": \"access_token\"," +
            "  \"hostnamePatterns\": [" +
            "    \"^bar\\\\.com$\"" +
            "  ]," +
            "  \"accessToken\": \"sesame\"" +
            '}';

    @Test
    void shouldMigrateMirrorsJson() throws Exception {
        final ProjectManager projectManager = projectManagerExtension.projectManager();
        final Project project = projectManager.get(TEST_PROJ);

        final String mirrorsJson = '[' + repo0Mirror + ',' + repo1Mirror + ',' + repo2Mirror + ']';
        project.metaRepo().commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                                  "Create a new mirrors.json",
                                  Change.ofJsonUpsert(PATH_LEGACY_MIRRORS, mirrorsJson)).join();
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
        assertThatJson(mirrorConfig).whenIgnoringPaths("id", "credentialId")
                                    .isEqualTo(expectedMirrorConfig);
    }

    @Test
    void shouldMigrateCredential() throws Exception {
        final ProjectManager projectManager = projectManagerExtension.projectManager();
        final Project project = projectManager.get(TEST_PROJ);

        final String credentialJson = '[' + publicKeyCredential + ',' + passwordCredential + ',' +
                                      accessTokenCredential + ']';

        project.metaRepo().commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                                  "Create a new credentials.json",
                                  Change.ofJsonUpsert(PATH_LEGACY_CREDENTIALS, credentialJson)).join();
        final MirroringMigrationService migrationService = new MirroringMigrationService(projectManager);
        migrationService.migrate();

        final Map<String, Entry<?>> entries = project.metaRepo()
                                                     .find(Revision.HEAD, "/credentials/*.json")
                                                     .join();

        assertThat(entries).hasSize(3);
        final Map<String, Map.Entry<String, Entry<?>>> credentials =
                entries.entrySet().stream()
                       .collect(toImmutableMap(e -> {
                           try {
                               return e.getValue().contentAsJson().get("type").asText();
                           } catch (JsonParseException ex) {
                               throw new RuntimeException(ex);
                           }
                       }, Function.identity()));

        assertCredential(credentials.get("public_key"), "credential-" + TEST_PROJ + "-[a-z]+",
                           publicKeyCredential);
        assertCredential(credentials.get("password"), "credential-1", passwordCredential);
        assertCredential(credentials.get("access_token"), "credential-1-[0-9a-f]+", accessTokenCredential);
    }

    @Test
    void shouldUpdateCredentialIdToMirrorConfig() throws Exception {
        final ProjectManager projectManager = projectManagerExtension.projectManager();
        final Project project = projectManager.get(TEST_PROJ);
        final String mirrorsJson = '[' + repo0Mirror + ',' + repo1Mirror + ',' + repo2Mirror + ']';
        project.metaRepo().commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                                  "Create a new mirrors.json",
                                  Change.ofJsonUpsert(PATH_LEGACY_MIRRORS, mirrorsJson)).join();

        final String credentialJson = '[' + publicKeyCredential + ',' + passwordCredential + ',' +
                                      accessTokenCredential + ']';

        project.metaRepo().commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                                  "Create a new credentials.json",
                                  Change.ofJsonUpsert(PATH_LEGACY_CREDENTIALS, credentialJson)).join();

        final MirroringMigrationService migrationService = new MirroringMigrationService(projectManager);
        migrationService.migrate();

        final List<Mirror> mirrors = project.metaRepo().mirrors().join();
        assertThat(mirrors).hasSize(3);
        for (Mirror mirror : mirrors) {
            if ("mirror-1".equals(mirror.id())) {
                assertThat(mirror.credential().id()).isEqualTo("credential-1");
            } else if (mirror.id().startsWith("mirror-" + TEST_PROJ + '-' + TEST_REPO0)) {
                assertThat(mirror.credential().id()).matches("credential-" + TEST_PROJ + "-[a-z]+");
            } else if (mirror.id().startsWith("mirror-1-")) {
                // No matched credential was found.
                assertThat(mirror.credential().id()).matches("");
                assertThat(mirror.credential()).isSameAs(MirrorCredential.FALLBACK);
            } else {
                throw new AssertionError("Unexpected mirror ID: " + mirror.id());
            }
        }
    }

    private static void assertCredential(Map.Entry<String, Entry<?>> actualCredential, String credentialId,
                                           String expectedCredential) throws JsonParseException {
        assertThat(actualCredential.getKey()).matches("/credentials/" + credentialId + "\\.json");
        final JsonNode credential = actualCredential.getValue().contentAsJson();
        assertThat(credential.get("id").asText()).matches(credentialId);
        assertThatJson(credential).whenIgnoringPaths("id")
                                    .isEqualTo(expectedCredential);
    }
}
