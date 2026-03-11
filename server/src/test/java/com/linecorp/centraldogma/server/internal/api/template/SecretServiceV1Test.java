/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server.internal.api.template;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.core.type.TypeReference;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService.IdAndProjectRole;
import com.linecorp.centraldogma.server.storage.repository.HasRevision;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class SecretServiceV1Test {

    private static final String FOO_PROJ = "foo-proj";
    private static final String BAR_REPO = "bar-repo";
    private static final String MEMBER_APP_ID = "memberAppId";
    private static final String OUTSIDER_APP_ID = "outsiderAppId";
    private static final TypeReference<HasRevision<Secret>> secretTypeRef = new TypeReference<>() {};

    // A client with project MEMBER role (not OWNER)
    private static BlockingWebClient memberClient;
    // A client with no project membership
    private static BlockingWebClient outsiderClient;

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected String accessToken() {
            return getAccessToken(WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                                  USERNAME, PASSWORD, true);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject(FOO_PROJ).join();
            client.createRepository(FOO_PROJ, BAR_REPO).join();
        }
    };

    @BeforeAll
    static void setUp() {
        final WebClient webClient = dogma.httpClient();
        final BlockingWebClient adminClient = dogma.blockingHttpClient();

        // Create a member token and add it as a project MEMBER
        final String memberToken = getAccessToken(webClient, USERNAME2, PASSWORD2,
                                                  MEMBER_APP_ID, false);
        memberClient = WebClient.builder(dogma.httpClient().uri())
                                .auth(AuthToken.ofOAuth2(memberToken))
                                .build()
                                .blocking();
        final HttpRequest addMemberRequest =
                HttpRequest.builder()
                           .post("/api/v1/metadata/" + FOO_PROJ + "/appIdentities")
                           .contentJson(new IdAndProjectRole(MEMBER_APP_ID, ProjectRole.MEMBER))
                           .build();
        assertThat(adminClient.execute(addMemberRequest).status()).isEqualTo(HttpStatus.OK);

        // Create an outsider token with no project membership
        final String outsiderToken = getAccessToken(webClient, USERNAME2, PASSWORD2,
                                                    OUTSIDER_APP_ID, false);
        outsiderClient = WebClient.builder(dogma.httpClient().uri())
                                  .auth(AuthToken.ofOAuth2(outsiderToken))
                                  .build()
                                  .blocking();
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void createAndReadSecret(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);
        final String namePrefix = namePrefix(projectLevel);

        // Create a secret
        final Secret secret1 = new Secret("secret-1", "my-secret-value", "A test secret");
        final ResponseEntity<HasRevision<Secret>> createResponse1 = client.prepare()
                                                                          .post(basePath)
                                                                          .contentJson(secret1)
                                                                          .asJson(secretTypeRef)
                                                                          .execute();
        assertThat(createResponse1.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse1.content().revision()).isNotNull();
        final Secret expected1 = new Secret("secret-1", namePrefix + "secret-1",
                                            "my-secret-value", null, "A test secret");
        assertThat(createResponse1.content().object())
                .usingRecursiveComparison()
                .ignoringFields("creation")
                .isEqualTo(expected1);

        // Create another secret
        final Secret secret2 = new Secret("secret-2", "another-secret", "Another test secret");
        final ResponseEntity<HasRevision<Secret>> createResponse2 =
                client.prepare()
                      .post(basePath)
                      .contentJson(secret2)
                      .asJson(secretTypeRef)
                      .execute();
        assertThat(createResponse2.status()).isEqualTo(HttpStatus.CREATED);

        // List secrets
        final ResponseEntity<List<Secret>> listResponse =
                client.prepare()
                      .get(basePath)
                      .asJson(new TypeReference<List<Secret>>() {})
                      .execute();
        assertThat(listResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.content())
                .extracting(Secret::id)
                .contains("secret-1", "secret-2");
        // List API always masks secret values
        assertThat(listResponse.content())
                .allSatisfy(secret -> assertThat(secret.value()).isEqualTo("****"));

        // Get specific secret
        final ResponseEntity<Secret> getResponse =
                client.prepare()
                      .get(basePath + "/secret-1")
                      .asJson(Secret.class)
                      .execute();
        assertThat(getResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.content())
                .usingRecursiveComparison()
                .ignoringFields("creation")
                .isEqualTo(expected1);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void updateSecret(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);
        final String namePrefix = namePrefix(projectLevel);

        // Create a secret
        final Secret createSecret = new Secret("update-secret", "initial-value", "Secret to be updated");
        final ResponseEntity<HasRevision<Secret>> createResponse = client.prepare()
                                                                         .post(basePath)
                                                                         .contentJson(createSecret)
                                                                         .asJson(secretTypeRef)
                                                                         .execute();
        assertThat(createResponse.status()).isEqualTo(HttpStatus.CREATED);

        // Update the secret
        final Secret updateSecret = new Secret("update-secret", "updated-value", "Secret to be updated");
        final ResponseEntity<HasRevision<Secret>> updateResponse =
                client.prepare()
                      .put(basePath + "/update-secret")
                      .contentJson(updateSecret)
                      .asJson(secretTypeRef)
                      .execute();
        assertThat(updateResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.content().object())
                .usingRecursiveComparison()
                .ignoringFields("creation")
                .isEqualTo(new Secret("update-secret", namePrefix + "update-secret",
                                      "updated-value", null, "Secret to be updated"));

        // Verify the update
        final ResponseEntity<Secret> getResponse = client.prepare()
                                                         .get(basePath + "/update-secret")
                                                         .asJson(Secret.class)
                                                         .execute();
        assertThat(getResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.content().value()).isEqualTo("updated-value");
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void deleteSecret(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);

        // Create a secret
        final Secret createSecret = new Secret("delete-secret", "to-delete", "Secret to be deleted");
        final ResponseEntity<HasRevision<Secret>> createResponse = client.prepare()
                                                                         .post(basePath)
                                                                         .contentJson(createSecret)
                                                                         .asJson(secretTypeRef)
                                                                         .execute();
        assertThat(createResponse.status()).isEqualTo(HttpStatus.CREATED);

        // Delete the secret
        final AggregatedHttpResponse deleteResponse = client.prepare()
                                                            .delete(basePath + "/delete-secret")
                                                            .execute();
        assertThat(deleteResponse.status()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify it's deleted
        final AggregatedHttpResponse getResponse = client.prepare()
                                                         .get(basePath + "/delete-secret")
                                                         .execute();
        assertThat(getResponse.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void getNonExistentSecret(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);

        final AggregatedHttpResponse response = client.prepare()
                                                      .get(basePath + "/non-existent")
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void updateNonExistentSecret(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);

        final Secret updateSecret = new Secret("non-existent", "value", "Non-existent secret");
        final AggregatedHttpResponse response = client.prepare()
                                                      .put(basePath + "/non-existent")
                                                      .contentJson(updateSecret)
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void updateSecretWithMismatchedId(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);

        // Create a secret
        final Secret createSecret = new Secret("mismatch-secret", "value",
                                               "Secret for ID mismatch test");
        client.prepare()
              .post(basePath)
              .contentJson(createSecret)
              .asJson(secretTypeRef)
              .execute();

        // Try to update with mismatched ID
        final Secret updateSecret = new Secret("different-id", "new-value",
                                               "Secret for ID mismatch test");
        final AggregatedHttpResponse response = client.prepare()
                                                      .put(basePath + "/mismatch-secret")
                                                      .contentJson(updateSecret)
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listSecretsAreIsolatedBetweenProjectAndRepo() {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String projectBasePath = "/api/v1/projects/" + FOO_PROJ + "/secrets";
        final String repoBasePath = "/api/v1/projects/" + FOO_PROJ + "/repos/" + BAR_REPO + "/secrets";

        // Create a project-level secret
        final Secret projectSecret = new Secret("list-proj-only", "proj-value", "Project only secret");
        assertThat(client.prepare()
                         .post(projectBasePath)
                         .contentJson(projectSecret)
                         .asJson(secretTypeRef)
                         .execute().status()).isEqualTo(HttpStatus.CREATED);

        // Create a repo-level secret
        final Secret repoSecret = new Secret("list-repo-only", "repo-value", "Repo only secret");
        assertThat(client.prepare()
                         .post(repoBasePath)
                         .contentJson(repoSecret)
                         .asJson(secretTypeRef)
                         .execute().status()).isEqualTo(HttpStatus.CREATED);

        // Project list should contain the project secret but not the repo secret
        final ResponseEntity<List<Secret>> projectList =
                client.prepare()
                      .get(projectBasePath)
                      .asJson(new TypeReference<List<Secret>>() {})
                      .execute();
        assertThat(projectList.status()).isEqualTo(HttpStatus.OK);
        assertThat(projectList.content())
                .extracting(Secret::id)
                .contains("list-proj-only")
                .doesNotContain("list-repo-only");

        // Repo list should contain the repo secret but not the project secret
        final ResponseEntity<List<Secret>> repoList =
                client.prepare()
                      .get(repoBasePath)
                      .asJson(new TypeReference<List<Secret>>() {})
                      .execute();
        assertThat(repoList.status()).isEqualTo(HttpStatus.OK);
        assertThat(repoList.content())
                .extracting(Secret::id)
                .contains("list-repo-only")
                .doesNotContain("list-proj-only");

        // Verify listed secrets have correct fields (value is masked for non-system-admin tokens)
        final Secret listedProjectSecret = projectList.content().stream()
                                                      .filter(s -> "list-proj-only".equals(s.id()))
                                                      .findFirst().get();
        assertThat(listedProjectSecret.name()).isEqualTo("projects/" + FOO_PROJ + "/secrets/list-proj-only");
        assertThat(listedProjectSecret.description()).isEqualTo("Project only secret");
        assertThat(listedProjectSecret.creation()).isNotNull();

        final Secret listedRepoSecret = repoList.content().stream()
                                                .filter(s -> "list-repo-only".equals(s.id()))
                                                .findFirst().get();
        assertThat(listedRepoSecret.name()).isEqualTo(
                "projects/" + FOO_PROJ + "/repos/" + BAR_REPO + "/secrets/list-repo-only");
        assertThat(listedRepoSecret.description()).isEqualTo("Repo only secret");
        assertThat(listedRepoSecret.creation()).isNotNull();
    }

    @Test
    void secretValueMaskedForNonAdmin() {
        final BlockingWebClient adminClient = dogma.blockingHttpClient();
        final String projectBasePath = "/api/v1/projects/" + FOO_PROJ + "/secrets";
        final String repoBasePath = "/api/v1/projects/" + FOO_PROJ + "/repos/" + BAR_REPO + "/secrets";

        // Create project-level and repo-level secrets as admin
        final Secret projectSecret = new Secret("masked-secret", "super-secret-value",
                                                "A secret to test masking");
        assertThat(adminClient.prepare()
                              .post(projectBasePath)
                              .contentJson(projectSecret)
                              .asJson(secretTypeRef)
                              .execute().status()).isEqualTo(HttpStatus.CREATED);

        final Secret repoSecret = new Secret("masked-repo-secret", "repo-secret-value",
                                             "A repo secret to test masking");
        assertThat(adminClient.prepare()
                              .post(repoBasePath)
                              .contentJson(repoSecret)
                              .asJson(secretTypeRef)
                              .execute().status()).isEqualTo(HttpStatus.CREATED);

        // Admin can see the actual values
        final ResponseEntity<Secret> adminGetProject =
                adminClient.prepare()
                           .get(projectBasePath + "/masked-secret")
                           .asJson(Secret.class)
                           .execute();
        assertThat(adminGetProject.content().value()).isEqualTo("super-secret-value");

        final ResponseEntity<Secret> adminGetRepo =
                adminClient.prepare()
                           .get(repoBasePath + "/masked-repo-secret")
                           .asJson(Secret.class)
                           .execute();
        assertThat(adminGetRepo.content().value()).isEqualTo("repo-secret-value");

        // Non-admin sees masked values when getting individual secrets
        final ResponseEntity<Secret> nonAdminGetProject =
                memberClient.prepare()
                            .get(projectBasePath + "/masked-secret")
                            .asJson(Secret.class)
                            .execute();
        assertThat(nonAdminGetProject.content().value()).isEqualTo("****");
        assertThat(nonAdminGetProject.content().id()).isEqualTo("masked-secret");

        // Non-admin sees masked values when listing repo-level secrets
        final ResponseEntity<List<Secret>> nonAdminRepoList =
                memberClient.prepare()
                            .get(repoBasePath)
                            .asJson(new TypeReference<List<Secret>>() {})
                            .execute();
        assertThat(nonAdminRepoList.content())
                .allSatisfy(secret -> assertThat(secret.value()).isEqualTo("****"));
    }

    // --- Project-level permission tests ---

    @Test
    void memberCanListAndGetProjectSecrets() {
        final BlockingWebClient adminClient = dogma.blockingHttpClient();
        final String basePath = "/api/v1/projects/" + FOO_PROJ + "/secrets";

        // Admin creates a secret
        final Secret secret = new Secret("perm-proj-read", "secret-value", "Permission test");
        assertThat(adminClient.prepare()
                              .post(basePath)
                              .contentJson(secret)
                              .asJson(secretTypeRef)
                              .execute().status()).isEqualTo(HttpStatus.CREATED);

        // Member can list project secrets
        assertThat(memberClient.get(basePath).status()).isEqualTo(HttpStatus.OK);

        // Member can get a specific project secret
        assertThat(memberClient.get(basePath + "/perm-proj-read").status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void memberCannotCreateProjectSecret() {
        final String basePath = "/api/v1/projects/" + FOO_PROJ + "/secrets";
        final Secret secret = new Secret("perm-proj-create", "value", "Permission test");
        final AggregatedHttpResponse response = memberClient.prepare()
                                                            .post(basePath)
                                                            .contentJson(secret)
                                                            .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void memberCannotUpdateProjectSecret() {
        final BlockingWebClient adminClient = dogma.blockingHttpClient();
        final String basePath = "/api/v1/projects/" + FOO_PROJ + "/secrets";

        // Admin creates a secret
        final Secret secret = new Secret("perm-proj-update", "value", "Permission test");
        assertThat(adminClient.prepare()
                              .post(basePath)
                              .contentJson(secret)
                              .asJson(secretTypeRef)
                              .execute().status()).isEqualTo(HttpStatus.CREATED);

        // Member cannot update it
        final Secret updated = new Secret("perm-proj-update", "new-value", "Permission test");
        final AggregatedHttpResponse response = memberClient.prepare()
                                                            .put(basePath + "/perm-proj-update")
                                                            .contentJson(updated)
                                                            .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void memberCannotDeleteProjectSecret() {
        final BlockingWebClient adminClient = dogma.blockingHttpClient();
        final String basePath = "/api/v1/projects/" + FOO_PROJ + "/secrets";

        // Admin creates a secret
        final Secret secret = new Secret("perm-proj-delete", "value", "Permission test");
        assertThat(adminClient.prepare()
                              .post(basePath)
                              .contentJson(secret)
                              .asJson(secretTypeRef)
                              .execute().status()).isEqualTo(HttpStatus.CREATED);

        // Member cannot delete it
        final AggregatedHttpResponse response = memberClient.delete(basePath + "/perm-proj-delete");
        assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- Repo-level permission tests ---

    @Test
    void memberCanListAndGetRepoSecrets() {
        final BlockingWebClient adminClient = dogma.blockingHttpClient();
        final String basePath = "/api/v1/projects/" + FOO_PROJ + "/repos/" + BAR_REPO + "/secrets";

        // Admin creates a repo secret
        final Secret secret = new Secret("perm-repo-read", "secret-value", "Permission test");
        assertThat(adminClient.prepare()
                              .post(basePath)
                              .contentJson(secret)
                              .asJson(secretTypeRef)
                              .execute().status()).isEqualTo(HttpStatus.CREATED);

        // Member (who has READ on repo by default) can list repo secrets
        assertThat(memberClient.get(basePath).status()).isEqualTo(HttpStatus.OK);

        // Member can get a specific repo secret
        assertThat(memberClient.get(basePath + "/perm-repo-read").status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void memberCannotCreateRepoSecret() {
        final String basePath = "/api/v1/projects/" + FOO_PROJ + "/repos/" + BAR_REPO + "/secrets";
        final Secret secret = new Secret("perm-repo-create", "value", "Permission test");
        final AggregatedHttpResponse response = memberClient.prepare()
                                                            .post(basePath)
                                                            .contentJson(secret)
                                                            .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void memberCannotUpdateRepoSecret() {
        final BlockingWebClient adminClient = dogma.blockingHttpClient();
        final String basePath = "/api/v1/projects/" + FOO_PROJ + "/repos/" + BAR_REPO + "/secrets";

        // Admin creates a repo secret
        final Secret secret = new Secret("perm-repo-update", "value", "Permission test");
        assertThat(adminClient.prepare()
                              .post(basePath)
                              .contentJson(secret)
                              .asJson(secretTypeRef)
                              .execute().status()).isEqualTo(HttpStatus.CREATED);

        // Member cannot update it
        final Secret updated = new Secret("perm-repo-update", "new-value", "Permission test");
        final AggregatedHttpResponse response = memberClient.prepare()
                                                            .put(basePath + "/perm-repo-update")
                                                            .contentJson(updated)
                                                            .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void memberCannotDeleteRepoSecret() {
        final BlockingWebClient adminClient = dogma.blockingHttpClient();
        final String basePath = "/api/v1/projects/" + FOO_PROJ + "/repos/" + BAR_REPO + "/secrets";

        // Admin creates a repo secret
        final Secret secret = new Secret("perm-repo-delete", "value", "Permission test");
        assertThat(adminClient.prepare()
                              .post(basePath)
                              .contentJson(secret)
                              .asJson(secretTypeRef)
                              .execute().status()).isEqualTo(HttpStatus.CREATED);

        // Member cannot delete it
        final AggregatedHttpResponse response = memberClient.delete(basePath + "/perm-repo-delete");
        assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- Outsider (no project membership) tests ---

    @Test
    void outsiderCannotAccessProjectSecrets() {
        final String basePath = "/api/v1/projects/" + FOO_PROJ + "/secrets";

        // Outsider cannot list project secrets
        assertThat(outsiderClient.get(basePath).status()).isEqualTo(HttpStatus.FORBIDDEN);

        // Outsider cannot get a specific project secret
        assertThat(outsiderClient.get(basePath + "/any-secret").status()).isEqualTo(HttpStatus.FORBIDDEN);

        // Outsider cannot create a project secret
        final Secret secret = new Secret("outsider-secret", "value", "test");
        assertThat(outsiderClient.prepare()
                                 .post(basePath)
                                 .contentJson(secret)
                                 .execute().status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void outsiderCannotAccessRepoSecrets() {
        final String basePath = "/api/v1/projects/" + FOO_PROJ + "/repos/" + BAR_REPO + "/secrets";

        // Outsider cannot list repo secrets
        assertThat(outsiderClient.get(basePath).status()).isEqualTo(HttpStatus.FORBIDDEN);

        // Outsider cannot get a specific repo secret
        assertThat(outsiderClient.get(basePath + "/any-secret").status()).isEqualTo(HttpStatus.FORBIDDEN);

        // Outsider cannot create a repo secret
        final Secret secret = new Secret("outsider-repo-secret", "value", "test");
        assertThat(outsiderClient.prepare()
                                 .post(basePath)
                                 .contentJson(secret)
                                 .execute().status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static String apiPrefix(boolean projectLevel) {
        return projectLevel ? "/api/v1/projects/" + FOO_PROJ + "/secrets"
                            : "/api/v1/projects/" + FOO_PROJ + "/repos/" + BAR_REPO + "/secrets";
    }

    private static String namePrefix(boolean projectLevel) {
        return projectLevel ? "projects/" + FOO_PROJ + "/secrets/"
                            : "projects/" + FOO_PROJ + "/repos/" + BAR_REPO + "/secrets/";
    }
}
