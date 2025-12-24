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

import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
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
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService.IdAndProjectRole;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class TokenGuestPermissionTest {

    private static final String FOO_PROJ = "foo";
    private static final String BAR_REPO = "bar";

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
    }

    @Test
    void testNormalToken() throws UnknownHostException {
        final BlockingWebClient client = dogma.blockingHttpClient();

        final String appId = "test";
        final ResponseEntity<Token> response =
                client.prepare()
                      .post("/api/v1/appIdentities")
                      .content(MediaType.FORM_DATA, QueryParams.of("appId", appId, "type", "token")
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
        final ResponseEntity<Revision> res =
                client.prepare()
                      .post("/api/v1/metadata/{proj}/appIdentities")
                      .pathParam("proj", FOO_PROJ)
                      .contentJson(
                              new IdAndProjectRole(appId, ProjectRole.MEMBER))
                      .asJson(Revision.class)
                      .execute();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        // The token has been registered as a member role so the access should be allowed now.
        final Entry<?> entry = dogmaClient.forRepo(FOO_PROJ, BAR_REPO)
                                          .file("/a.txt")
                                          .get().join();
        assertThat(entry.contentAsText().trim()).isEqualTo("foo");
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
