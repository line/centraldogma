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
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class MirroringMigrationServiceTest {

    // The static fields are shared with MirroringMigrationServiceClusterTest.
    static final String TEST_PROJ = "fooProj";
    static final String TEST_REPO0 = "repo0";
    static final String TEST_REPO1 = "repo1";
    static final String TEST_REPO2 = "repo2";
    static final String TEST_REPO3 = "repo3";

    // The real key pair generated using:
    //
    //   ssh-keygen -t rsa -b 768 -N sesame
    //
    static final String PUBLIC_KEY =
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAYQCmkW9HjZE5q0EM06MUWXYFTNTi" +
            "KkfYD/pH2GwJw6yi20Gi0TzjJ6YBLueU48vxkwWmw6sTOEuBxtzefTxs4kQuatev" +
            "uXn7tWX9fhSIAEp+zdyQY7InyCqfHFwRwswemCM= trustin@localhost";

    static final String PRIVATE_KEY =
            "-----BEGIN RSA PRIVATE KEY-----\n" +
            "Proc-Type: 4,ENCRYPTED\n" +
            "DEK-Info: AES-128-CBC,C35856D3C524AA2FD32D878F4409B97E\n" +
            '\n' +
            "X3HRmqg2bUqfqxkWjHsr4KeN1UyN5QbypGd7Jov/nDSyiIWe4zPJD/3oji0xOK+h\n" +
            "Lxq+c8DDu7ItpC6dwe5WexcyIKGF7WqlkqeEhVM3VOkQtbpbdnb7bA8mLja2unMW\n" +
            "bFLgQiTF1Y8SlG4Q70N0iY638AeIG/ZUU14LSBFSQDkrtZ+f7bhIhVDDavANMF+B\n" +
            "+eiQ4u3W59Cpbm83AfzqotrPXuBusfyBjH7Wfj0XRvOGRjTQT0jXIWWpLqnIy5ms\n" +
            "HNGlMoJElUQuPpbQUiFvmqiMj40r9V/Wx/8+GciADOs4FsTvGFKIcouWDhjIWg0b\n" +
            "DKFqV/Hw/AjkAafkySxxmk1+EIen4XfkghtlWLwT2Xp4RtJXYiVC9q9483jDv3+Z\n" +
            "iTa5rjFuro4WJkDZp6/N6l+/HcbBXL8L6y66xsJwP+6GLuDLpXjGZrneV1ip2dtG\n" +
            "BQzvlgCOr9pTAa4Ar7MC3E2C6+qPhOwO4B/f1cigwRaEB92MHz5gJsITU3xVfTjV\n" +
            "yf4THKipBDxqnET6F2FMZJFolVzFEXDaCFNC1TjBqS0+A8KaMcO/lXjJxtfvV37l\n" +
            "zmB/ey0dZ8WBCazCp9OX3dYgNkVR1yYNlJWOGJS8Cwc=\n" +
            "-----END RSA PRIVATE KEY-----";

    static final String PASSPHRASE = "sesame";

    // A mirror config without ID
    static final String REPO0_MIRROR =
            "{\n" +
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
    static final String REPO1_MIRROR =
            "{\n" +
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
    static final String REPO2_MIRROR =
            "{\n" +
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

    // A mirror config with duplicate ID
    static final String REPO3_MIRROR =
            "{\n" +
            "  \"id\": \"mirror-1\",\n" +
            "  \"type\": \"single\",\n" +
            "  \"enabled\": true,\n" +
            "  \"schedule\": \"0 * * * * ?\",\n" +
            "  \"direction\": \"REMOTE_TO_LOCAL\",\n" +
            "  \"localRepo\": \"" + TEST_REPO3 + "\",\n" +
            "  \"localPath\": \"/\",\n" +
            "  \"remoteUri\": \"git+ssh://git.qux.com/foo.git/settings#release\",\n" +
            "  \"gitignore\": [\n" +
            "      \"/credential.txt\",\n" +
            "      \"private_dir\"\n" +
            "  ]\n" +
            '}';

    // A credential without ID
    static final String PUBLIC_KEY_CREDENTIAL =
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
    static final String PASSWORD_CREDENTIAL =
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
    static final String ACCESS_TOKEN_CREDENTIAL =
            '{' +
            "  \"id\": \"credential-1\"," +
            "  \"type\": \"access_token\"," +
            "  \"hostnamePatterns\": [" +
            "    \"^bar\\\\.com$\"" +
            "  ]," +
            "  \"accessToken\": \"sesame\"" +
            '}';

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
            repoManager.create(TEST_REPO3, Author.SYSTEM);

            final MetadataService mds = new MetadataService(projectManager, projectManagerExtension.executor());
            mds.addRepo(Author.SYSTEM, TEST_PROJ, TEST_REPO0).join();
            mds.addRepo(Author.SYSTEM, TEST_PROJ, TEST_REPO1).join();
            mds.addRepo(Author.SYSTEM, TEST_PROJ, TEST_REPO2).join();
            mds.addRepo(Author.SYSTEM, TEST_PROJ, TEST_REPO3).join();
        }
    };

    @Test
    void shouldMigrateMirrorsJson() throws Exception {
        final ProjectManager projectManager = projectManagerExtension.projectManager();
        final Project project = projectManager.get(TEST_PROJ);

        final String mirrorsJson =
                '[' + REPO0_MIRROR + ',' + REPO1_MIRROR + ',' + REPO2_MIRROR + ',' + REPO3_MIRROR + ']';
        project.metaRepo().commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                                  "Create a new mirrors.json",
                                  Change.ofJsonUpsert(PATH_LEGACY_MIRRORS, mirrorsJson)).join();
        final MirroringMigrationService migrationService = new MirroringMigrationService(
                projectManager, projectManagerExtension.executor());
        migrationService.migrate();

        final Map<String, Entry<?>> entries = project.metaRepo()
                                                     .find(Revision.HEAD, "/mirrors/*.json")
                                                     .join();

        assertThat(entries).hasSize(4);
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
                           REPO0_MIRROR);
        assertMirrorConfig(mirrors.get(TEST_REPO1), "mirror-1", REPO1_MIRROR);
        // "-1" suffix is added because the mirror ID is duplicated.
        assertMirrorConfig(mirrors.get(TEST_REPO2), "mirror-1-1", REPO2_MIRROR);
        // "-2" suffix is added because the mirror ID is duplicated.
        assertMirrorConfig(mirrors.get(TEST_REPO3), "mirror-1-2", REPO3_MIRROR);
    }

    static void assertMirrorConfig(Map.Entry<String, Entry<?>> actualMirrorConfig, String mirrorId,
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

        final String credentialJson = '[' + PUBLIC_KEY_CREDENTIAL + ',' + PASSWORD_CREDENTIAL + ',' +
                                      ACCESS_TOKEN_CREDENTIAL + ']';

        project.metaRepo().commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                                  "Create a new credentials.json",
                                  Change.ofJsonUpsert(PATH_LEGACY_CREDENTIALS, credentialJson)).join();
        final MirroringMigrationService migrationService = new MirroringMigrationService(
                projectManager, projectManagerExtension.executor());
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
                         PUBLIC_KEY_CREDENTIAL);
        assertCredential(credentials.get("password"), "credential-1", PASSWORD_CREDENTIAL);
        // "-1" suffix is added because the credential ID is duplicated.
        assertCredential(credentials.get("access_token"), "credential-1-1", ACCESS_TOKEN_CREDENTIAL);
    }

    @Test
    void shouldUpdateCredentialIdToMirrorConfig() throws Exception {
        final ProjectManager projectManager = projectManagerExtension.projectManager();
        final Project project = projectManager.get(TEST_PROJ);
        final String mirrorsJson = '[' + REPO0_MIRROR + ',' + REPO1_MIRROR + ',' + REPO2_MIRROR + ']';
        project.metaRepo().commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                                  "Create a new mirrors.json",
                                  Change.ofJsonUpsert(PATH_LEGACY_MIRRORS, mirrorsJson)).join();

        final String credentialJson = '[' + PUBLIC_KEY_CREDENTIAL + ',' + PASSWORD_CREDENTIAL + ',' +
                                      ACCESS_TOKEN_CREDENTIAL + ']';

        project.metaRepo().commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                                  "Create a new credentials.json",
                                  Change.ofJsonUpsert(PATH_LEGACY_CREDENTIALS, credentialJson)).join();

        final MirroringMigrationService migrationService = new MirroringMigrationService(
                projectManager, projectManagerExtension.executor());
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

    static void assertCredential(Map.Entry<String, Entry<?>> actualCredential, String credentialId,
                                 String expectedCredential) throws JsonParseException {
        assertThat(actualCredential.getKey()).matches("/credentials/" + credentialId + "\\.json");
        final JsonNode credential = actualCredential.getValue().contentAsJson();
        assertThat(credential.get("id").asText()).matches(credentialId);
        assertThatJson(credential).whenIgnoringPaths("id")
                                  .isEqualTo(expectedCredential);
    }
}
