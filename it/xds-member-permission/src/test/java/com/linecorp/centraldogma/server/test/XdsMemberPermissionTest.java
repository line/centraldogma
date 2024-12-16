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

package com.linecorp.centraldogma.server.test;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;

import org.apache.shiro.config.Ini;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.auth.shiro.ShiroAuthProviderFactory;
import com.linecorp.centraldogma.server.internal.credential.NoneCredential;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class XdsMemberPermissionTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(USERNAME)
                   .cors("*")
                   .authProviderFactory(new ShiroAuthProviderFactory(unused -> {
                       final Ini iniConfig = new Ini();
                       final Ini.Section users = iniConfig.addSection("users");
                       users.put(USERNAME, PASSWORD);
                       users.put(USERNAME2, PASSWORD2);
                       return iniConfig;
                   }));
        }

        @Override
        protected void scaffold(CentralDogma client) {
        }
    };

    @Test
    void shouldAllowMembersToAccessInternalProjects() throws Exception {
        final String adminToken = TestAuthMessageUtil.getAccessToken(dogma.httpClient(), USERNAME, PASSWORD);
        final String userToken = TestAuthMessageUtil.getAccessToken(dogma.httpClient(), USERNAME2, PASSWORD2);

        final CentralDogma adminClient = new ArmeriaCentralDogmaBuilder()
                .host("127.0.0.1", dogma.serverAddress().getPort())
                .accessToken(adminToken)
                .build();
        adminClient.createProject("foo").join();
        final BlockingWebClient adminWebClient =
                WebClient.builder("http://127.0.0.1:" + dogma.serverAddress().getPort())
                         .decorator(LoggingClient.newDecorator())
                         .auth(AuthToken.ofOAuth2(adminToken))
                         .build()
                         .blocking();

        final CentralDogma userClient = new ArmeriaCentralDogmaBuilder()
                .host("127.0.0.1", dogma.serverAddress().getPort())
                .accessToken(userToken)
                .build();

        final BlockingWebClient userWebClient =
                WebClient.builder("http://127.0.0.1:" + dogma.serverAddress().getPort())
                         .decorator(LoggingClient.newDecorator())
                         .auth(AuthToken.ofOAuth2(userToken))
                         .build()
                         .blocking();

        assertThat(adminClient.listProjects().join()).containsOnly("dogma", "foo", "@xds");
        // Internal projects are not visible to the user by default.
        assertThat(userClient.listProjects().join()).containsOnly("foo");

        final CentralDogmaRepository adminRepo = adminClient.createRepository("@xds", "test").join();
        adminRepo.commit("Add test.txt", Change.ofTextUpsert("/text.txt", "foo"))
                 .push()
                 .join();

        final AggregatedHttpResponse credentialResponse =
                adminWebClient.prepare()
                              .post("/api/v1/projects/@xds/credentials")
                              .contentJson(new NoneCredential("test", true))
                              .execute();
        assertThat(credentialResponse.status()).isEqualTo(HttpStatus.CREATED);

        // All CRUD operations should be blocked.
        assertThatThrownBy(() -> {
            userClient.createRepository("@xds", "test2").join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(CentralDogmaException.class)
          .hasMessageContaining(":status=403");

        final CentralDogmaRepository userRepo = userClient.forRepo("@xds", "test");
        assertThatThrownBy(() -> {
            userRepo.commit("Update test.txt", Change.ofTextUpsert("/text.txt", "bar"))
                    .push()
                    .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(CentralDogmaException.class)
          .hasMessageContaining(":status=403");

        assertThatThrownBy(() -> {
            userRepo.file("/text.txt")
                    .get()
                    .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(CentralDogmaException.class)
          .hasMessageContaining(":status=403");

        assertThat(userWebClient.prepare()
                                .get("/api/v1/projects/@xds/credentials/test")
                                .execute().status())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Grant the user role to access the internal project.
        final AggregatedHttpResponse res =
                adminWebClient.prepare()
                              .post("/api/v1/metadata/@xds/members")
                              .content(MediaType.JSON, "{\"id\":\"" + USERNAME2 + "\",\"role\":\"MEMBER\"}")
                              .execute();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        // @xds project should be visible to member users.
        assertThat(userClient.listProjects().join()).containsOnly("foo", "@xds");
        // Read and write should be granted as well.
        userRepo.commit("Update test.txt", Change.ofTextUpsert("/text.txt", "bar"))
                .push()
                .join();
        assertThat(userRepo.file("/text.txt").get().join().contentAsText())
                .isEqualTo("bar\n");

        // But the user should not be able to access the credentials.
        assertThat(userWebClient.prepare()
                                .get("/api/v1/projects/@xds/credentials/test")
                                .execute().status())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
