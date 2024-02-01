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
package com.linecorp.centraldogma.server.internal.api;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.server.internal.api.ProjectServiceV1Test.createProject;
import static com.linecorp.centraldogma.server.internal.api.ProjectServiceV1Test.sessionId;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ProjectServiceV1ListProjectTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.administrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }
    };

    private WebClient adminClient;
    private WebClient normalClient;

    @BeforeEach
    void setUp() throws JsonProcessingException, UnknownHostException {
        final URI uri = dogma.httpClient().uri();
        adminClient = WebClient.builder(uri)
                               .auth(AuthToken.ofOAuth2(sessionId(dogma.httpClient(),
                                                                  TestAuthMessageUtil.USERNAME,
                                                                  TestAuthMessageUtil.PASSWORD)))
                               .build();
        normalClient = WebClient.builder(uri)
                                .auth(AuthToken.ofOAuth2(sessionId(dogma.httpClient(),
                                                                   TestAuthMessageUtil.USERNAME2,
                                                                   TestAuthMessageUtil.PASSWORD2)))
                                .build();
    }

    @Test
    void listProjects() {
        createProject(normalClient, "trustin");
        createProject(normalClient, "hyangtack");
        createProject(normalClient, "minwoox");

        AggregatedHttpResponse aRes = normalClient.get(PROJECTS_PREFIX).aggregate().join();
        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.OK);
        final String withoutDogma =
                '[' +
                "   %s" + // dogma project is inserted here.
                "   {" +
                "       \"name\": \"hyangtack\"," +
                "       \"creator\": {" +
                "           \"name\": \"" + TestAuthMessageUtil.USERNAME2 + "\"," +
                "           \"email\": \"" + TestAuthMessageUtil.USERNAME2 + "@localhost.localdomain\"" +
                "       }," +
                "       \"url\": \"/api/v1/projects/hyangtack\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"name\": \"minwoox\"," +
                "       \"creator\": {" +
                "           \"name\": \"" + TestAuthMessageUtil.USERNAME2 + "\"," +
                "           \"email\": \"" + TestAuthMessageUtil.USERNAME2 + "@localhost.localdomain\"" +
                "       }," +
                "       \"url\": \"/api/v1/projects/minwoox\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"name\": \"trustin\"," +
                "       \"creator\": {" +
                "           \"name\": \"" + TestAuthMessageUtil.USERNAME2 + "\"," +
                "           \"email\": \"" + TestAuthMessageUtil.USERNAME2 + "@localhost.localdomain\"" +
                "       }," +
                "       \"url\": \"/api/v1/projects/trustin\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }" +
                ']';
        assertThatJson(aRes.contentUtf8()).isEqualTo(String.format(withoutDogma, ""));

        // Admin fetches internal project "dogma" as well.
        aRes = adminClient.get(PROJECTS_PREFIX).aggregate().join();
        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.OK);
        assertThatJson(aRes.contentUtf8()).isEqualTo(
                String.format(withoutDogma,
                              "   {" +
                              "       \"name\": \"dogma\"," +
                              "       \"creator\": {" +
                              "           \"name\": \"System\"," +
                              "           \"email\": \"system@localhost.localdomain\"" +
                              "        }," +
                              "        \"url\": \"/api/v1/projects/dogma\"," +
                              "        \"createdAt\": \"${json-unit.ignore}\"" +
                              "   },"));
    }

    @Test
    void listRemovedProjects() throws IOException {
        createProject(normalClient, "trustin");
        createProject(normalClient, "hyangtack");
        createProject(normalClient, "minwoox");
        normalClient.delete(PROJECTS_PREFIX + "/hyangtack").aggregate().join();
        normalClient.delete(PROJECTS_PREFIX + "/minwoox").aggregate().join();

        final AggregatedHttpResponse removedRes = normalClient.get(PROJECTS_PREFIX + "?status=removed")
                                                              .aggregate().join();
        assertThat(removedRes.headers().status()).isEqualTo(HttpStatus.OK);
        final String expectedJson =
                '[' +
                "   {" +
                "       \"name\": \"hyangtack\"" +
                "   }," +
                "   {" +
                "       \"name\": \"minwoox\"" +
                "   }" +
                ']';
        assertThatJson(removedRes.contentUtf8()).isEqualTo(expectedJson);

        final AggregatedHttpResponse remainedRes = normalClient.get(PROJECTS_PREFIX).aggregate().join();
        final String remains = remainedRes.contentUtf8();
        final JsonNode jsonNode = Jackson.readTree(remains);

        // Only trustin project is left
        assertThat(jsonNode.size()).isOne();
    }
}
