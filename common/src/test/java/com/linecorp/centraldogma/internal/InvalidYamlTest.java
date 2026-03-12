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

package com.linecorp.centraldogma.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class InvalidYamlTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("invalid-yaml-test").join();
            client.createRepository("invalid-yaml-test", "repo").join();
        }
    };

    @Test
    void shouldReturnInvalidYamlAsText() throws JsonProcessingException {
        final CentralDogmaRepository repo = dogma.client().forRepo("invalid-yaml-test", "repo");

        // Try to add an invalid YAML file
        final String invalidYamlContent = "key: value: another"; // Invalid YAML syntax
        // Make sure that parsing the invalid YAML throws an exception
        assertThatThrownBy(() -> Yaml.readTree(invalidYamlContent))
                .isInstanceOf(JsonParseException.class);

        repo.commit("test", Change.ofTextUpsert("/invalid.yaml", invalidYamlContent))
            .push()
            .join();
        final BlockingWebClient webClient = dogma.blockingHttpClient();
        final AggregatedHttpResponse response = webClient.get(
                "/api/v1/projects/invalid-yaml-test/repos/repo/contents/invalid.yaml");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        final JsonNode jsonNode = Jackson.readTree(response.contentUtf8());
        assertThat(jsonNode.get("revision").asInt()).isEqualTo(2);
        assertThat(jsonNode.get("type").asText()).isEqualTo("TEXT");
        assertThat(jsonNode.get("content").asText()).isEqualTo(invalidYamlContent + '\n');
    }
}
