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

package com.linecorp.centraldogma.server;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getSessionCookie;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.login;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.TemplateProcessingException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService.IdAndProjectRole;
import com.linecorp.centraldogma.server.internal.api.template.Secret;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class SecretTemplateCrudTest {

    private static final String TEST_PROJECT = "testProject";
    private static final String TEST_REPO_1 = "testRepo1";
    private static final String TEST_REPO_2 = "testRepo2";

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
    };

    private CentralDogmaRepository testRepo1;
    private CentralDogmaRepository testRepo2;
    private BlockingWebClient httpClient;

    @BeforeEach
    void beforeEach() {
        final CentralDogma client = dogma.client();
        try {
            client.removeProject(TEST_PROJECT).join();
            client.purgeProject(TEST_PROJECT).join();
        } catch (Exception e) {
            // Ignore
        }

        client.createProject(TEST_PROJECT).join();
        testRepo1 = client.createRepository(TEST_PROJECT, TEST_REPO_1).join();
        testRepo2 = client.createRepository(TEST_PROJECT, TEST_REPO_2).join();
        httpClient = dogma.blockingHttpClient();

        // Add non-admin user as a project member so they can access the repos
        final String memberId = USERNAME2 + "@localhost.localdomain";
        final HttpRequest addMemberRequest =
                HttpRequest.builder()
                           .post("/api/v1/metadata/" + TEST_PROJECT + "/members")
                           .contentJson(new IdAndProjectRole(memberId, ProjectRole.MEMBER))
                           .build();
        assertThat(httpClient.execute(addMemberRequest).status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void renderTemplateWithProjectLevelSecret() {
        createSecret(TEST_PROJECT, null, "apiKey", "my-api-key-123");

        final String templateContent = "{ \"key\": \"${secrets.apiKey}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add config template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/config.json"))
                                               .renderTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{ \"key\": \"my-api-key-123\" }");
    }

    @Test
    void renderTemplateWithRepoLevelSecret() {
        createSecret(TEST_PROJECT, TEST_REPO_1, "dbPassword", "repo-db-pass");

        final String templateContent = "{ \"password\": \"${secrets.dbPassword}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/db.json", templateContent);
        testRepo1.commit("Add db config template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/db.json"))
                                               .renderTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).isEqualTo("{ \"password\": \"repo-db-pass\" }");
    }

    @Test
    void repoLevelSecretOverridesProjectLevel() {
        createSecret(TEST_PROJECT, null, "token", "project-token");
        createSecret(TEST_PROJECT, TEST_REPO_1, "token", "repo-token");

        final String templateContent = "{ \"token\": \"${secrets.token}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/auth.json", templateContent);
        testRepo1.commit("Add auth template", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/auth.json"))
                                               .renderTemplate(true)
                                               .get()
                                               .join();

        // Repo-level secret should override project-level
        assertThatJson(entry.content()).isEqualTo("{ \"token\": \"repo-token\" }");
    }

    @Test
    void repoLevelSecretIsScopedToRepo() {
        createSecret(TEST_PROJECT, TEST_REPO_1, "repoSecret", "repo1-value");

        final String templateContent = "{ \"secret\": \"${secrets.repoSecret}\" }";
        // Push the same template to both repos
        final Change<JsonNode> change1 = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo1.commit("Add template", change1).push().join();
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/config.json", templateContent);
        testRepo2.commit("Add template", change2).push().join();

        // testRepo1 should render successfully
        final Entry<JsonNode> entry1 = testRepo1.file(Query.ofJson("/config.json"))
                                                 .renderTemplate(true)
                                                 .get()
                                                 .join();
        assertThatJson(entry1.content()).isEqualTo("{ \"secret\": \"repo1-value\" }");

        // testRepo2 should fail because the secret is not defined there
        assertThatThrownBy(() -> testRepo2.file(Query.ofJson("/config.json"))
                                          .renderTemplate(true)
                                          .get()
                                          .join())
                .hasCauseInstanceOf(TemplateProcessingException.class);
    }

    @Test
    void renderTemplateWithMultipleSecrets() {
        createSecret(TEST_PROJECT, null, "host", "db.example.com");
        createSecret(TEST_PROJECT, null, "port", "5432");
        createSecret(TEST_PROJECT, TEST_REPO_1, "password", "s3cret");

        final String templateContent =
                "{ \"url\": \"jdbc:postgresql://${secrets.host}:${secrets.port}\", " +
                "\"password\": \"${secrets.password}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/db-config.json", templateContent);
        testRepo1.commit("Add db config", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/db-config.json"))
                                               .renderTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content())
                .node("url").isEqualTo("jdbc:postgresql://db.example.com:5432");
        assertThatJson(entry.content())
                .node("password").isEqualTo("s3cret");
    }

    @Test
    void renderTemplateWithSecretsAndVariables() {
        createSecret(TEST_PROJECT, null, "apiKey", "secret-key-456");
        // Create a variable via the variable API
        createVariable(TEST_PROJECT, null, "env", "production");

        final String templateContent =
                "{ \"environment\": \"${vars.env}\", \"apiKey\": \"${secrets.apiKey}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/app.json", templateContent);
        testRepo1.commit("Add app config", change).push().join();

        final Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/app.json"))
                                               .renderTemplate(true)
                                               .get()
                                               .join();

        assertThatJson(entry.content()).node("environment").isEqualTo("production");
        assertThatJson(entry.content()).node("apiKey").isEqualTo("secret-key-456");
    }

    @Test
    void renderTemplateWithSecretInTextFile() {
        createSecret(TEST_PROJECT, null, "token", "bearer-abc-123");

        final String templateContent = "Authorization: ${secrets.token}";
        final Change<String> change = Change.ofTextUpsert("/auth.txt", templateContent);
        testRepo1.commit("Add auth template", change).push().join();

        final Entry<String> entry = testRepo1.file(Query.ofText("/auth.txt"))
                                             .renderTemplate(true)
                                             .get()
                                             .join();

        assertThat(entry.content()).isEqualTo("Authorization: bearer-abc-123\n");
    }

    @Test
    void updateSecretAndRenderTemplate() {
        createSecret(TEST_PROJECT, null, "password", "old-password");

        final String templateContent = "{ \"password\": \"${secrets.password}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/creds.json", templateContent);
        testRepo1.commit("Add creds template", change).push().join();

        Entry<JsonNode> entry = testRepo1.file(Query.ofJson("/creds.json"))
                                         .renderTemplate(true)
                                         .get()
                                         .join();
        assertThatJson(entry.content()).isEqualTo("{ \"password\": \"old-password\" }");

        // Update the secret
        updateSecret(TEST_PROJECT, null, "password", "new-password");

        entry = testRepo1.file(Query.ofJson("/creds.json"))
                         .renderTemplate(true)
                         .get()
                         .join();
        assertThatJson(entry.content()).isEqualTo("{ \"password\": \"new-password\" }");
    }

    @Test
    void deleteSecretAndRenderTemplateShouldFail() {
        createSecret(TEST_PROJECT, null, "tempSecret", "temp-value");

        final String templateContent = "Secret: ${secrets.tempSecret}";
        final Change<String> change = Change.ofTextUpsert("/temp.txt", templateContent);
        testRepo1.commit("Add temp template", change).push().join();

        final Entry<String> entry = testRepo1.file(Query.ofText("/temp.txt"))
                                             .renderTemplate(true)
                                             .get()
                                             .join();
        assertThat(entry.content()).isEqualTo("Secret: temp-value\n");

        // Delete the secret
        deleteSecret(TEST_PROJECT, null, "tempSecret");

        // Rendering should now fail
        assertThatThrownBy(() -> testRepo1.file(Query.ofText("/temp.txt"))
                                          .renderTemplate(true)
                                          .get()
                                          .join())
                .hasCauseInstanceOf(TemplateProcessingException.class)
                .hasMessageContaining("Failed to process the template for /temp.txt.")
                .hasMessageContaining("The following has evaluated to null or missing:\n" +
                                      "==> secrets.tempSecret");
    }

    @Test
    void renderTemplateWithoutSecretsShouldWork() {
        final String templateContent = "Static content without secrets";
        final Change<String> change = Change.ofTextUpsert("/static.txt", templateContent);
        testRepo1.commit("Add static file", change).push().join();

        final Entry<String> entry = testRepo1.file(Query.ofText("/static.txt"))
                                             .renderTemplate(true)
                                             .get()
                                             .join();

        assertThat(entry.content()).isEqualTo("Static content without secrets\n");
    }

    @Test
    void renderTemplateWithUndefinedSecretShouldFail() {
        final String templateContent = "Value: ${secrets.nonExistentSecret}";
        final Change<String> change = Change.ofTextUpsert("/fail.txt", templateContent);
        testRepo1.commit("Add failing template", change).push().join();

        assertThatThrownBy(() -> testRepo1.file(Query.ofText("/fail.txt"))
                                          .renderTemplate(true)
                                          .get()
                                          .join())
                .hasCauseInstanceOf(TemplateProcessingException.class);
    }

    @Test
    void secretsMaskedWhenAccessedViaSessionCookie() throws JsonProcessingException {
        // Create secrets at project and repo level
        createSecret(TEST_PROJECT, null, "projSecret", "project-secret-value");
        createSecret(TEST_PROJECT, TEST_REPO_1, "repoSecret", "repo-secret-value");

        // Push a template that uses both secrets
        final String templateContent =
                "{ \"proj\": \"${secrets.projSecret}\", \"repo\": \"${secrets.repoSecret}\" }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/masked.json", templateContent);
        testRepo1.commit("Add template with secrets", change).push().join();

        // Verify token-based access can see real values
        final Entry<JsonNode> tokenEntry = testRepo1.file(Query.ofJson("/masked.json"))
                                                     .renderTemplate(true)
                                                     .get()
                                                     .join();
        assertThatJson(tokenEntry.content()).node("proj").isEqualTo("project-secret-value");
        assertThatJson(tokenEntry.content()).node("repo").isEqualTo("repo-secret-value");

        // Create a session-based client (simulating web UI access)
        final BlockingWebClient sessionClient = createSessionClient();

        // Access the rendered template via session cookie
        final String contentsPath = "/api/v1/projects/" + TEST_PROJECT +
                                    "/repos/" + TEST_REPO_1 +
                                    "/contents/masked.json?renderTemplate=true";
        final AggregatedHttpResponse sessionResponse = sessionClient.get(contentsPath);
        assertThat(sessionResponse.status()).isEqualTo(HttpStatus.OK);

        // Secrets should be masked as "****" for session-based access
        final JsonNode responseJson = Jackson.readTree(sessionResponse.contentUtf8());
        final String content = responseJson.get("content").toString();
        assertThatJson(content).node("proj").isEqualTo("****");
        assertThatJson(content).node("repo").isEqualTo("****");
    }

    /**
     * Creates a session-based client for a non-admin user (USERNAME2), simulating web UI access.
     * Unlike token-based access (UserWithAppIdentity), session-based users are plain User instances,
     * so secrets should be masked in rendered templates.
     */
    private static BlockingWebClient createSessionClient() throws JsonProcessingException {
        final AggregatedHttpResponse loginResponse = login(dogma.httpClient(), USERNAME2, PASSWORD2);
        assertThat(loginResponse.status()).isEqualTo(HttpStatus.OK);
        final Cookie sessionCookie = getSessionCookie(loginResponse);
        final String csrfToken = Jackson.readTree(loginResponse.contentUtf8())
                                        .get("csrf_token").asText();
        return WebClient.builder(dogma.httpClient().uri())
                        .addHeader(SessionUtil.X_CSRF_TOKEN, csrfToken)
                        .addHeader(HttpHeaderNames.COOKIE, sessionCookie.toCookieHeader())
                        .build()
                        .blocking();
    }

    private void createSecret(String projectName, @Nullable String repoName,
                               String id, String value) {
        final Secret secret = new Secret(id, value, null);
        final String path;
        if (repoName == null) {
            path = "/api/v1/projects/" + projectName + "/secrets";
        } else {
            path = "/api/v1/projects/" + projectName + "/repos/" + repoName + "/secrets";
        }

        final AggregatedHttpResponse response = httpClient.prepare()
                                                          .post(path)
                                                          .contentJson(secret)
                                                          .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
    }

    private void updateSecret(String projectName, @Nullable String repoName,
                               String id, String value) {
        final Secret secret = new Secret(id, value, null);
        final String path;
        if (repoName == null) {
            path = "/api/v1/projects/" + projectName + "/secrets/" + id;
        } else {
            path = "/api/v1/projects/" + projectName + "/repos/" + repoName + "/secrets/" + id;
        }

        final AggregatedHttpResponse response = httpClient.prepare()
                                                          .put(path)
                                                          .contentJson(secret)
                                                          .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    private void deleteSecret(String projectName, @Nullable String repoName, String id) {
        final String path;
        if (repoName == null) {
            path = "/api/v1/projects/" + projectName + "/secrets/" + id;
        } else {
            path = "/api/v1/projects/" + projectName + "/repos/" + repoName + "/secrets/" + id;
        }

        final AggregatedHttpResponse response = httpClient.prepare()
                                                          .delete(path)
                                                          .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private void createVariable(String projectName, @Nullable String repoName,
                                 String id, String value) {
        final String json = "{\"id\":\"" + id + "\",\"type\":\"STRING\",\"value\":\"" + value + "\"}";
        final String path;
        if (repoName == null) {
            path = "/api/v1/projects/" + projectName + "/variables";
        } else {
            path = "/api/v1/projects/" + projectName + "/repos/" + repoName + "/variables";
        }

        final AggregatedHttpResponse response = httpClient.prepare()
                                                          .post(path)
                                                          .content(
                                                                  com.linecorp.armeria.common.MediaType.JSON,
                                                                  json)
                                                          .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
    }
}
