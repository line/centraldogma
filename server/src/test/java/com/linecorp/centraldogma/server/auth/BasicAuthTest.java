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

package com.linecorp.centraldogma.server.auth;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.type.TypeReference;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.internal.api.v1.ProjectDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class BasicAuthTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
        }

        @Override
        protected String accessToken() {
            return getAccessToken(WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                                  TestAuthMessageUtil.USERNAME,
                                  TestAuthMessageUtil.PASSWORD, true);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
        }
    };

    @Test
    void shouldReturnWwwAuthenticateHeadersForGitCloneRequest() {
        final BlockingWebClient client = WebClient.builder(dogma.httpClient().uri())
                                                  .build()
                                                  .blocking();
        final AggregatedHttpResponse response = client.get("/foo/bar/info/refs");
        assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.headers().getAll(HttpHeaderNames.WWW_AUTHENTICATE))
                .containsExactly("Bearer realm=\"Central Dogma\", charset=\"UTF-8\"",
                                 "Basic realm=\"Central Dogma\", charset=\"UTF-8\"");
    }

    @Test
    void useApplicationTokenToAccessResources() {
        final String accessToken = getAccessToken(dogma.httpClient(),
                                                  TestAuthMessageUtil.USERNAME,
                                                  TestAuthMessageUtil.PASSWORD, "appId1", true);
        final BlockingWebClient client = WebClient.builder(dogma.httpClient().uri())
                                                  .auth(AuthToken.ofBasic("dogma", accessToken))
                                                  .build()
                                                  .blocking();
        final ResponseEntity<List<ProjectDto>> response =
                client.prepare()
                      .get("/api/v1/projects")
                      .asJson(new TypeReference<List<ProjectDto>>() {})
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content()).anyMatch(project -> project.name().equals("dogma"));
    }
}
