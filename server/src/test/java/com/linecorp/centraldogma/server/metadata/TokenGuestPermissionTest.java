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

package com.linecorp.centraldogma.server.metadata;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PermissionException;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService.IdAndProjectRole;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class TokenGuestPermissionTest {

    private static final String FOO_PROJ = "foo";
    private static final String BAR_REPO = "bar";
    private static final String PRIVATE_REPO = "qux";
    private static final String PUBLIC_WITHOUT_MEMBER_ROLE = "no-member-role";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }

        @Override
        protected String accessToken() {
            return getAccessToken(
                    WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                    TestAuthMessageUtil.USERNAME,
                    TestAuthMessageUtil.PASSWORD, true);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject(FOO_PROJ).join();
            final CentralDogmaRepository repo = client.createRepository(FOO_PROJ, BAR_REPO).join();
            repo.commit("test", Change.ofTextUpsert("/a.txt", "foo")).push().join();
            final CentralDogmaRepository privateRepo =
                    client.createRepository(FOO_PROJ, PRIVATE_REPO).join();
            privateRepo.commit("test", Change.ofTextUpsert("/a.txt", "secret")).push().join();
            final CentralDogmaRepository publicRepo =
                    client.createRepository(FOO_PROJ, PUBLIC_WITHOUT_MEMBER_ROLE).join();
            publicRepo.commit("test", Change.ofTextUpsert("/a.txt", "public")).push().join();
        }
    };

    @BeforeAll
    static void beforeAll() {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final ResponseEntity<Revision> response =
                client.prepare()
                      .post("/api/v1/metadata/{proj}/repos/{repo}/roles/projects")
                      .pathParam("proj", FOO_PROJ)
                      .pathParam("repo", BAR_REPO)
                      .contentJson(new ProjectRoles(RepositoryRole.WRITE, RepositoryRole.READ))
                      .asJson(Revision.class)
                      .execute();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().major()).isGreaterThan(1);

        assertThat(client.prepare()
                         .post("/api/v1/metadata/{proj}/repos/{repo}/roles/projects")
                         .pathParam("proj", FOO_PROJ)
                         .pathParam("repo", PUBLIC_WITHOUT_MEMBER_ROLE)
                         .contentJson(ProjectRoles.of(null, RepositoryRole.READ))
                         .execute().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testNormalToken() throws UnknownHostException {
        final BlockingWebClient client = dogma.blockingHttpClient();

        final String appId = "test";
        final ResponseEntity<Token> response =
                client.prepare()
                      .post("/api/v1/appIdentities")
                      .content(MediaType.FORM_DATA, QueryParams.of("appId", appId, "type", "TOKEN")
                                                               .toQueryString())
                      .asJson(Token.class, new ObjectMapper())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        final Token token = response.content();
        assertThat(token.allowGuestAccess()).isFalse();

        // Try to access the repository with the new token.
        final CentralDogma dogmaClient = new ArmeriaCentralDogmaBuilder()
                .host(dogma.serverAddress().getHostString(), dogma.serverAddress().getPort())
                .accessToken(token.secret())
                .build();
        assertThatThrownBy(() -> {
            dogmaClient.forRepo(FOO_PROJ, BAR_REPO)
                       .file("/a.txt")
                       .get().join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(PermissionException.class)
          .hasMessageContaining("You must have the READ repository role to access the 'foo/bar'");

        // Register the token with the member role.
        registerAppIdentityAsMember(client, appId);

        // The token has been registered as a member role so the access should be allowed now.
        final Entry<?> entry = dogmaClient.forRepo(FOO_PROJ, BAR_REPO)
                                          .file("/a.txt")
                                          .get().join();
        assertThat(entry.contentAsText().trim()).isEqualTo("foo");

        assertThatThrownBy(() -> dogmaClient.forRepo(FOO_PROJ, PUBLIC_WITHOUT_MEMBER_ROLE)
                                            .file("/a.txt").get().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(PermissionException.class);
    }

    @Test
    void testGuestAccessToken() throws UnknownHostException {
        final BlockingWebClient client = dogma.blockingHttpClient();

        final String appId = "guest-access-test";
        final ResponseEntity<Token> response =
                client.prepare()
                      .post("/api/v1/appIdentities")
                      .content(MediaType.FORM_DATA,
                               QueryParams.of("appId", appId, "type", "TOKEN",
                                              "allowGuestAccess", true)
                                          .toQueryString())
                      .asJson(Token.class, new ObjectMapper())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        final Token token = response.content();
        assertThat(token.isSystemAdmin()).isFalse();
        assertThat(token.allowGuestAccess()).isTrue();

        final CentralDogma dogmaClient = new ArmeriaCentralDogmaBuilder()
                .host(dogma.serverAddress().getHostString(), dogma.serverAddress().getPort())
                .accessToken(token.secret())
                .build();

        // Reads the public (guest READ) repository without any registration.
        final Entry<?> entry = dogmaClient.forRepo(FOO_PROJ, BAR_REPO)
                                          .file("/a.txt")
                                          .get().join();
        assertThat(entry.contentAsText().trim()).isEqualTo("foo");

        // Guests can never write.
        assertThatThrownBy(() -> {
            dogmaClient.forRepo(FOO_PROJ, BAR_REPO)
                       .commit("write", Change.ofTextUpsert("/b.txt", "bar"))
                       .push().join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(PermissionException.class)
          .hasMessageContaining("You must have the WRITE repository role to access the 'foo/bar'");

        // A private repository is still inaccessible.
        assertThatThrownBy(() -> {
            dogmaClient.forRepo(FOO_PROJ, PRIVATE_REPO)
                       .file("/a.txt")
                       .get().join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(PermissionException.class)
          .hasMessageContaining("You must have the READ repository role to access the 'foo/qux'");

        registerAppIdentityAsMember(client, appId);
        final Entry<?> publicEntry = dogmaClient.forRepo(FOO_PROJ, PUBLIC_WITHOUT_MEMBER_ROLE)
                                                .file("/a.txt").get().join();
        assertThat(publicEntry.contentAsText().trim()).isEqualTo("public");
    }

    private static void registerAppIdentityAsMember(BlockingWebClient client, String appId) {
        assertThat(client.prepare()
                         .post("/api/v1/metadata/{proj}/appIdentities")
                         .pathParam("proj", FOO_PROJ)
                         .contentJson(new IdAndProjectRole(appId, ProjectRole.MEMBER))
                         .execute().status()).isEqualTo(HttpStatus.OK);
        await().untilAsserted(() -> assertThat(projectMetadata().appIds()).containsKey(appId));
    }

    private static ProjectMetadata projectMetadata() {
        return dogma.blockingHttpClient().prepare()
                    .get("/api/v1/projects/" + FOO_PROJ)
                    .asJson(ProjectMetadata.class, new ObjectMapper())
                    .execute().content();
    }

    @Test
    void signedInUserRetainsPublicReadAfterJoiningProject() throws Exception {
        // A signed-in user who is not a project member reads the public repository, but not the
        // private one.
        final WebClient client = WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort());
        final AggregatedHttpResponse loginRes = TestAuthMessageUtil.login(
                client, TestAuthMessageUtil.USERNAME2, TestAuthMessageUtil.PASSWORD2);
        assertThat(loginRes.status()).isEqualTo(HttpStatus.OK);
        final Cookie sessionCookie = TestAuthMessageUtil.getSessionCookie(loginRes);
        final String csrfToken = Jackson.readTree(loginRes.contentUtf8()).get("csrf_token").asText();
        final BlockingWebClient userClient =
                WebClient.builder(client.uri())
                         .addHeader(HttpHeaderNames.COOKIE, sessionCookie.toCookieHeader())
                         .addHeader(SessionUtil.X_CSRF_TOKEN, csrfToken)
                         .build()
                         .blocking();

        assertThat(userClient.get("/api/v1/projects/foo/repos/bar/list/a.txt").status())
                .isEqualTo(HttpStatus.OK);
        assertThat(userClient.get("/api/v1/projects/foo/repos/qux/list/a.txt").status())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // A signed-in non-member can never write to the public repository.
        final AggregatedHttpResponse writeRes =
                userClient.prepare()
                          .post("/api/v1/projects/foo/repos/bar/contents")
                          .content(MediaType.JSON,
                                   "{\"commitMessage\":{\"summary\":\"write\"}," +
                                   "\"changes\":[{\"path\":\"/b.txt\",\"type\":\"UPSERT_TEXT\"," +
                                   "\"content\":\"b\"}]}")
                          .execute();
        assertThat(writeRes.status()).isEqualTo(HttpStatus.FORBIDDEN);

        // Public does not mean anonymous: an unauthenticated client is rejected.
        final AggregatedHttpResponse unauthenticated =
                WebClient.of(client.uri()).blocking().get("/api/v1/projects/foo/repos/bar/list/a.txt");
        assertThat(unauthenticated.status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        final String publicFile = "/api/v1/projects/foo/repos/" + PUBLIC_WITHOUT_MEMBER_ROLE + "/list/a.txt";
        assertThat(userClient.get(publicFile).status()).isEqualTo(HttpStatus.OK);

        final AggregatedHttpResponse currentUser = userClient.get("/api/v0/users/me");
        assertThat(currentUser.status()).isEqualTo(HttpStatus.OK);
        final String memberId = Jackson.readTree(currentUser.contentUtf8()).get("email").asText();
        assertThat(dogma.blockingHttpClient().prepare()
                        .post("/api/v1/metadata/{proj}/members")
                        .pathParam("proj", FOO_PROJ)
                        .contentJson(new IdAndProjectRole(memberId, ProjectRole.MEMBER))
                        .execute().status()).isEqualTo(HttpStatus.OK);
        await().untilAsserted(() -> assertThat(projectMetadata().members()).containsKey(memberId));
        assertThat(userClient.get(publicFile).status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testSystemAdminToken() throws UnknownHostException {
        final BlockingWebClient client = dogma.blockingHttpClient();

        final String appId = "admin-test";
        final ResponseEntity<Token> response =
                client.prepare()
                      .post("/api/v1/appIdentities")
                      .content(MediaType.FORM_DATA,
                               QueryParams.of("appId", appId, "isSystemAdmin", true, "type", "TOKEN")
                                          .toQueryString())
                      .asJson(Token.class, new ObjectMapper())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        final Token token = response.content();
        assertThat(token.allowGuestAccess()).isTrue();

        final CentralDogma dogmaClient = new ArmeriaCentralDogmaBuilder()
                .host(dogma.serverAddress().getHostString(), dogma.serverAddress().getPort())
                .accessToken(token.secret())
                .build();

        final Entry<?> entry = dogmaClient.forRepo(FOO_PROJ, BAR_REPO)
                                          .file("/a.txt")
                                          .get().join();
        assertThat(entry.contentAsText().trim()).isEqualTo("foo");
    }
}
