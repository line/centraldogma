/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.centraldogma.server.internal.api.variable;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.core.type.TypeReference;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.storage.repository.HasRevision;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

@SuppressWarnings("NullableProblems")
class VariableServiceV1Test {

    private static final String FOO_PROJ = "foo-proj";
    private static final String BAR_REPO = "bar-repo";
    private static final TypeReference<HasRevision<Variable>> variableTypeRef = new TypeReference<>() {};

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

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void createAndReadVariable(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);
        final String namePrefix = namePrefix(projectLevel);

        // Create a STRING variable
        final Variable stringVar = new Variable("string-var", VariableType.STRING, "hello world");
        final ResponseEntity<HasRevision<Variable>> createStringResponse = client.prepare()
                                                                                 .post(basePath)
                                                                                 .contentJson(stringVar)
                                                                                 .asJson(variableTypeRef)
                                                                                 .execute();
        assertThat(createStringResponse.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(createStringResponse.content().revision()).isNotNull();
        final Variable stringVarStored = new Variable("string-var", VariableType.STRING,
                                                      namePrefix + "string-var", "hello world");
        assertThat(createStringResponse.content().object())
                .isEqualTo(stringVarStored);

        // Create a JSON variable
        final Variable jsonVar = new Variable("json-var", VariableType.JSON,
                                              "{\"key\":\"value\",\"number\":42}");
        final ResponseEntity<HasRevision<Variable>> createJsonResponse =
                client.prepare()
                      .post(basePath)
                      .contentJson(jsonVar)
                      .asJson(variableTypeRef)
                      .execute();
        assertThat(createJsonResponse.status()).isEqualTo(HttpStatus.CREATED);
        final Variable jsonVarStored =
                new Variable("json-var", VariableType.JSON,
                             namePrefix + "json-var", "{\"key\":\"value\",\"number\":42}");
        assertThat(createJsonResponse.content().object()).isEqualTo(jsonVarStored);

        // List variables
        final ResponseEntity<List<Variable>> listResponse =
                client.prepare()
                      .get(basePath)
                      .asJson(new TypeReference<List<Variable>>() {})
                      .execute();
        assertThat(listResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.content()).hasSize(2);
        assertThat(listResponse.content()).containsExactlyInAnyOrder(stringVarStored, jsonVarStored);

        // Get specific STRING variable
        final ResponseEntity<Variable> getStringResponse =
                client.prepare()
                      .get(basePath + "/string-var")
                      .asJson(Variable.class)
                      .execute();
        assertThat(getStringResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(getStringResponse.content()).isEqualTo(stringVarStored);

        // Get specific JSON variable
        final ResponseEntity<Variable> getJsonResponse = client.prepare()
                                                               .get(basePath + "/json-var")
                                                               .asJson(Variable.class)
                                                               .execute();
        assertThat(getJsonResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(getJsonResponse.content()).isEqualTo(jsonVarStored);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void updateVariable(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);
        final String namePrefix = namePrefix(projectLevel);

        // Create a variable
        final Variable createVar = new Variable("update-var", VariableType.STRING, "\"initial\"");
        final ResponseEntity<HasRevision<Variable>> createResponse = client.prepare()
                                                                           .post(basePath)
                                                                           .contentJson(createVar)
                                                                           .asJson(variableTypeRef)
                                                                           .execute();
        assertThat(createResponse.status()).isEqualTo(HttpStatus.CREATED);

        // Update the variable
        final Variable updateVar = new Variable("update-var", VariableType.STRING, "\"updated\"");
        final ResponseEntity<HasRevision<Variable>> updateResponse =
                client.prepare()
                      .put(basePath + "/update-var")
                      .contentJson(updateVar)
                      .asJson(variableTypeRef)
                      .execute();
        assertThat(updateResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.content().object()).isEqualTo(
                new Variable("update-var", VariableType.STRING, namePrefix + "update-var", "\"updated\""));

        // Verify the update
        final ResponseEntity<Variable> getResponse = client.prepare()
                                                           .get(basePath + "/update-var")
                                                           .asJson(Variable.class)
                                                           .execute();
        assertThat(getResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.content().value()).isEqualTo("\"updated\"");
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void updateVariableType(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);

        // Create a STRING variable
        final Variable createVar = new Variable("type-change-var", VariableType.STRING, "123");
        client.prepare()
              .post(basePath)
              .contentJson(createVar)
              .execute();

        // Update to JSON type
        final Variable updateVar = new Variable("type-change-var", VariableType.JSON, "{ \"key\": 123 }");
        final ResponseEntity<HasRevision<Variable>> updateResponse = client.prepare()
                                                                           .put(basePath + "/type-change-var")
                                                                           .contentJson(updateVar)
                                                                           .asJson(variableTypeRef)
                                                                           .execute();
        assertThat(updateResponse.status()).isEqualTo(HttpStatus.OK);

        // Verify the type change
        final ResponseEntity<Variable> getResponse = client.prepare()
                                                           .get(basePath + "/type-change-var")
                                                           .asJson(Variable.class)
                                                           .execute();
        assertThat(getResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.content().type()).isEqualTo(VariableType.JSON);
        assertThat(getResponse.content().value()).isEqualTo("{ \"key\": 123 }");
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void deleteVariable(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);

        // Create a variable
        final Variable createVar = new Variable("delete-var", VariableType.STRING, "\"to-delete\"");
        final ResponseEntity<HasRevision<Variable>> createResponse = client.prepare()
                                                                           .post(basePath)
                                                                           .contentJson(createVar)
                                                                           .asJson(variableTypeRef)
                                                                           .execute();
        assertThat(createResponse.status()).isEqualTo(HttpStatus.CREATED);

        // Delete the variable
        final AggregatedHttpResponse deleteResponse = client.prepare()
                                                            .delete(basePath + "/delete-var")
                                                            .execute();
        assertThat(deleteResponse.status()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify it's deleted
        final AggregatedHttpResponse getResponse = client.prepare()
                                                         .get(basePath + "/delete-var")
                                                         .execute();
        assertThat(getResponse.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void createVariableWithInvalidJson(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);

        // Try to create a JSON variable with invalid JSON value
        final Variable invalidVar = new Variable("invalid-json", VariableType.JSON, "not valid json");
        final AggregatedHttpResponse response = client.prepare()
                                                      .post(basePath)
                                                      .contentJson(invalidVar)
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void getNonExistentVariable(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);

        final AggregatedHttpResponse response = client.prepare()
                                                      .get(basePath + "/non-existent")
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void updateNonExistentVariable(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);

        final Variable updateVar = new Variable("non-existent", VariableType.STRING, "\"value\"");
        final AggregatedHttpResponse response = client.prepare()
                                                      .put(basePath + "/non-existent")
                                                      .contentJson(updateVar)
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void updateVariableWithMismatchedId(boolean projectLevel) {
        final BlockingWebClient client = dogma.blockingHttpClient();
        final String basePath = apiPrefix(projectLevel);

        // Create a variable
        final Variable createVar = new Variable("mismatch-var", VariableType.STRING, "\"value\"");
        client.prepare()
              .post(basePath)
              .contentJson(createVar)
              .asJson(variableTypeRef)
              .execute();

        // Try to update with mismatched ID
        final Variable updateVar = new Variable("different-id", VariableType.STRING, "\"new-value\"");
        final AggregatedHttpResponse response = client.prepare()
                                                      .put(basePath + "/mismatch-var")
                                                      .contentJson(updateVar)
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static String apiPrefix(boolean projectLevel) {
        return projectLevel ? "/api/v1/projects/" + FOO_PROJ + "/variables"
                            : "/api/v1/projects/" + FOO_PROJ + "/repos/" + BAR_REPO + "/variables";
    }

    private static String namePrefix(boolean projectLevel) {
        return projectLevel ? "projects/" + FOO_PROJ + "/variables/"
                            : "projects/" + FOO_PROJ + "/repos/" + BAR_REPO + "/variables/";
    }
}
