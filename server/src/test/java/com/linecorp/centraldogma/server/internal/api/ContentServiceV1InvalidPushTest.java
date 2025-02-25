/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.api;

import static com.linecorp.centraldogma.server.internal.api.ProjectServiceV1Test.sessionId;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.InvalidPushException;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ContentServiceV1InvalidPushTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }

        @Override
        protected void configureClient(ArmeriaCentralDogmaBuilder builder) {
            final String accessToken = getAccessToken(
                    WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                    TestAuthMessageUtil.USERNAME, TestAuthMessageUtil.PASSWORD);
            builder.accessToken(accessToken);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
        }
    };

    private static BlockingWebClient systemAdminClient;

    @BeforeAll
    static void setUp() throws JsonMappingException, JsonParseException {
        systemAdminClient = WebClient.builder(dogma.httpClient().uri())
                                     .auth(AuthToken.ofOAuth2(sessionId(dogma.httpClient(),
                                                                        TestAuthMessageUtil.USERNAME,
                                                                        TestAuthMessageUtil.PASSWORD)))
                                     .build()
                                     .blocking();
    }

    @CsvSource({
            "dogma, dogma",
            "foo, dogma",
            "foo, meta",
            "foo, bar",
    })
    @ParameterizedTest
    void pushFileToInternalShouldFail(String projectName, String repoName) {
        final String body =
                '{' +
                "   \"path\" : \"/foo.json\"," +
                "   \"type\" : \"UPSERT_JSON\"," +
                "   \"content\" : {\"a\": \"bar\"}," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Add foo.json\"," +
                "       \"detail\": \"Add because we need it.\"," +
                "       \"markup\": \"PLAINTEXT\"" +
                "   }" +
                '}';
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/api/v1/projects/" + projectName + "/repos/" +
                                                   repoName + "/contents",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final AggregatedHttpResponse res = systemAdminClient.execute(headers, body);
        if ("bar".equals(repoName)) {
            assertThat(res.status()).isSameAs(HttpStatus.OK);
        } else {
            assertThat(res.status()).isSameAs(HttpStatus.BAD_REQUEST);
            assertThat(res.contentUtf8()).contains(InvalidPushException.class.getName());
        }
    }
}
