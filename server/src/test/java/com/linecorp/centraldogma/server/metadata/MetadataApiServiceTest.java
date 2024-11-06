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
package com.linecorp.centraldogma.server.metadata;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.login;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MetadataApiServiceTest {

    @RegisterExtension
    static CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }
    };

    @Test
    void grantPermissionToMemberForMetaRepository() throws Exception {
        final String projectName = "foo_proj";
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse response = login(client,
                                                      TestAuthMessageUtil.USERNAME,
                                                      TestAuthMessageUtil.PASSWORD);

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        final String sessionId = Jackson.readValue(response.content().array(), AccessToken.class)
                                        .accessToken();
        final WebClient systemAdminClient = WebClient.builder(client.uri())
                                                     .auth(AuthToken.ofOAuth2(sessionId)).build();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, PROJECTS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final String body = "{\"name\": \"" + projectName + "\"}";
        // Create a project.
        assertThat(systemAdminClient.execute(headers, body).aggregate().join().status())
                .isSameAs(HttpStatus.CREATED);

        final String memberToken = "appToken-secret-member";
        // Create a token with a non-random secret.
        HttpRequest request = HttpRequest.builder()
                                         .post("/api/v1/tokens")
                                         .content(MediaType.FORM_DATA,
                                                  "secret=" + memberToken + "&isSystemAdmin=false&appId=foo")
                                         .build();
        AggregatedHttpResponse res = systemAdminClient.execute(request).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);
        res = systemAdminClient.get("/api/v1/tokens").aggregate().join();
        assertThat(res.contentUtf8()).contains("\"secret\":\"" + memberToken + '"');

        // Add as a member to the project
        request = HttpRequest.builder()
                             .post("/api/v1/metadata/" + projectName + "/tokens")
                             .content(MediaType.JSON,
                                      '{' +
                                      "\"id\":\"foo\"," +
                                      "\"role\":\"MEMBER\"" +
                                      '}')
                             .build();
        res = systemAdminClient.execute(request).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final WebClient memberClient = WebClient.builder(client.uri())
                                                .auth(AuthToken.ofOAuth2(memberToken)).build();
        res = memberClient.get("/api/v1/projects/" + projectName + "/repos/meta/list").aggregate().join();
        // A member isn't allowed to access the meta repository yet.
        assertThat(res.status()).isSameAs(HttpStatus.FORBIDDEN);
        assertThat(res.contentUtf8()).contains("You must have READ permission for repository");

        // Grant a READ permission to the member.
        request = HttpRequest.builder()
                             .post("/api/v1/metadata/" + projectName + "/repos/meta/perm/role")
                             .content(MediaType.JSON,
                                      "{\n" +
                                      "  \"owner\": [ \"READ\", \"WRITE\" ],\n" +
                                      "  \"member\": [ \"READ\" ],\n" +
                                      "  \"guest\": [ ],\n" +
                                      "  \"anonymous\": [ ]\n" +
                                      '}')
                             .build();
        systemAdminClient.execute(request).aggregate().join();

        // Now the member can access the meta repository.
        res = memberClient.get("/api/v1/projects/" + projectName + "/repos/meta/list").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.NO_CONTENT);
    }
}
