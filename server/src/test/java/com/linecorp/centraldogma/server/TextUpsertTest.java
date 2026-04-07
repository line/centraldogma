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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class TextUpsertTest {

    @RegisterExtension
    final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("testProject").join();
            client.createRepository("testProject", "testRepo").join();
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private CentralDogmaRepository repo;

    @BeforeEach
    void beforeEach() {
        repo = dogma.client().forRepo("testProject", "testRepo");
    }

    @Test
    void pushJsonFileWithTextUpsert() {
        //language=JSON
        final String jsonText = "{\n" +
                                "  \"name\": \"John Doe\",\n" +
                                "  \"age\": 30\n" +
                                "}";

        final Change<String> change = Change.ofTextUpsert("/config.json", jsonText);
        final PushResult result = repo.commit("Add JSON file via text upsert", change).push().join();
        assertThat(result.revision().major()).isPositive();

        // Should be readable as JSON.
        final Entry<JsonNode> jsonEntry = repo.file(Query.ofJson("/config.json"))
                                              .get()
                                              .join();
        assertThatJson(jsonEntry.content()).node("name").isEqualTo("John Doe");
        assertThatJson(jsonEntry.content()).node("age").isEqualTo(30);
    }

    @Test
    void pushJsonFileWithTextUpsertAndReadAsText() {
        //language=JSON
        final String jsonText = "{\"key\":\"value\"}";

        final Change<String> change = Change.ofTextUpsert("/simple.json", jsonText);
        repo.commit("Add JSON file", change).push().join();

        final Entry<String> textEntry = repo.file(Query.ofText("/simple.json"))
                                            .get()
                                            .join();
        // Trailing newline is added by the git storage.
        assertThat(textEntry.content()).isEqualTo("{\"key\":\"value\"}\n");
    }

    @Test
    void pushJsonFileWithTextUpsertAndQueryJsonPath() {
        //language=JSON
        final String jsonText = "{\n" +
                                "  \"users\": [\n" +
                                "    {\"name\": \"Alice\", \"role\": \"admin\"},\n" +
                                "    {\"name\": \"Bob\", \"role\": \"user\"}\n" +
                                "  ]\n" +
                                "}";

        final Change<String> change = Change.ofTextUpsert("/users.json", jsonText);
        repo.commit("Add users", change).push().join();

        final Entry<JsonNode> usersEntry = repo.file(Query.ofJsonPath("/users.json", "$.users"))
                                               .get()
                                               .join();
        assertThatJson(usersEntry.content()).isArray().ofLength(2);
        assertThatJson(usersEntry.content()).node("[0].name").isEqualTo("Alice");
    }

    @Test
    void pushJson5FileWithTextUpsert() {
        //language=JSON5
        final String json5Text = "{\n" +
                                 "  // This is a comment\n" +
                                 "  name: 'John Doe',\n" +
                                 "  age: 30,\n" +
                                 "}";

        final Change<String> change = Change.ofTextUpsert("/config.json5", json5Text);
        final PushResult result = repo.commit("Add JSON5 file via text upsert", change).push().join();
        assertThat(result.revision().major()).isPositive();

        // Should be readable as JSON.
        final Entry<JsonNode> jsonEntry = repo.file(Query.ofJson("/config.json5"))
                                              .get()
                                              .join();
        assertThatJson(jsonEntry.content()).node("name").isEqualTo("John Doe");
        assertThatJson(jsonEntry.content()).node("age").isEqualTo(30);

        // Raw content should preserve the original JSON5 text.
        @SuppressWarnings("unchecked")
        final Entry<JsonNode> rawEntry = (Entry<JsonNode>) repo.file("/config.json5")
                                                               .viewRaw(true)
                                                               .get()
                                                               .join();
        assertThat(rawEntry.rawContent()).isEqualTo(json5Text + '\n');
    }

    @Test
    void pushJson5FileWithTextUpsertAndQueryJsonPath() {
        //language=JSON5
        final String json5Text = "{\n" +
                                 "  // Settings\n" +
                                 "  settings: {\n" +
                                 "    theme: 'dark',\n" +
                                 "    language: 'en',\n" +
                                 "  },\n" +
                                 "}";

        final Change<String> change = Change.ofTextUpsert("/settings.json5", json5Text);
        repo.commit("Add settings", change).push().join();

        final Entry<JsonNode> themeEntry =
                repo.file(Query.ofJsonPath("/settings.json5", "$.settings.theme"))
                    .get()
                    .join();
        assertThatJson(themeEntry.content()).isEqualTo("\"dark\"");
    }

    @Test
    void updateJsonFileWithTextUpsert() {
        //language=JSON
        final String initialJson = "{\"version\": 1}";
        repo.commit("Initial", Change.ofTextUpsert("/version.json", initialJson)).push().join();

        //language=JSON
        final String updatedJson = "{\"version\": 2}";
        repo.commit("Update", Change.ofTextUpsert("/version.json", updatedJson)).push().join();

        final Entry<JsonNode> entry = repo.file(Query.ofJson("/version.json"))
                                          .get()
                                          .join();
        assertThatJson(entry.content()).node("version").isEqualTo(2);
    }

    @Test
    void pushYamlFileWithTextUpsert() {
        //language=yaml
        final String yamlText = "# This is a comment\n" +
                                "name: John Doe\n" +
                                "age: 30\n" +
                                "active: true\n";

        final Change<String> change = Change.ofTextUpsert("/config.yaml", yamlText);
        final PushResult result = repo.commit("Add YAML file via text upsert", change).push().join();
        assertThat(result.revision().major()).isPositive();

        // Should be readable as YAML.
        final Entry<JsonNode> yamlEntry = repo.file(Query.ofYaml("/config.yaml"))
                                              .get()
                                              .join();
        assertThatJson(yamlEntry.content()).node("name").isEqualTo("John Doe");
        assertThatJson(yamlEntry.content()).node("age").isEqualTo(30);
        assertThatJson(yamlEntry.content()).node("active").isEqualTo(true);

        // Raw content should preserve the original YAML text including comments.
        @SuppressWarnings("unchecked")
        final Entry<JsonNode> rawEntry = (Entry<JsonNode>) repo.file("/config.yaml")
                                                               .viewRaw(true)
                                                               .get()
                                                               .join();
        assertThat(rawEntry.rawContent()).isEqualTo(yamlText);
    }

    @Test
    void pushYamlFileWithTextUpsertAndReadAsText() {
        //language=yaml
        final String yamlText = "key: value\n" +
                                "number: 123\n";

        final Change<String> change = Change.ofTextUpsert("/simple.yaml", yamlText);
        repo.commit("Add YAML file", change).push().join();

        final Entry<String> textEntry = repo.file(Query.ofText("/simple.yaml"))
                                            .get()
                                            .join();
        assertThat(textEntry.content()).isEqualTo(yamlText);
    }

    @Test
    void pushYamlFileWithTextUpsertAndQueryJsonPath() {
        //language=yaml
        final String yamlText = "users:\n" +
                                "  - name: Alice\n" +
                                "    role: admin\n" +
                                "  - name: Bob\n" +
                                "    role: user\n";

        final Change<String> change = Change.ofTextUpsert("/users.yaml", yamlText);
        repo.commit("Add users", change).push().join();

        final Entry<JsonNode> usersEntry = repo.file(Query.ofJsonPath("/users.yaml", "$.users"))
                                               .get()
                                               .join();
        assertThatJson(usersEntry.content()).isArray().ofLength(2);
        assertThatJson(usersEntry.content()).node("[0].name").isEqualTo("Alice");
    }

    @Test
    void pushYmlFileWithTextUpsert() {
        //language=yaml
        final String ymlText = "database:\n" +
                               "  host: localhost\n" +
                               "  port: 5432\n";

        final Change<String> change = Change.ofTextUpsert("/db.yml", ymlText);
        repo.commit("Add YML file via text upsert", change).push().join();

        final Entry<JsonNode> entry = repo.file(Query.ofYaml("/db.yml"))
                                          .get()
                                          .join();
        assertThatJson(entry.content()).node("database.host").isEqualTo("localhost");
        assertThatJson(entry.content()).node("database.port").isEqualTo(5432);
    }

    @Test
    void updateYamlFileWithTextUpsert() {
        //language=yaml
        final String initialYaml = "version: 1\n";
        repo.commit("Initial", Change.ofTextUpsert("/version.yaml", initialYaml)).push().join();

        //language=yaml
        final String updatedYaml = "version: 2\n" +
                                   "updated: true\n";
        repo.commit("Update", Change.ofTextUpsert("/version.yaml", updatedYaml)).push().join();

        final Entry<JsonNode> entry = repo.file(Query.ofYaml("/version.yaml"))
                                          .get()
                                          .join();
        assertThatJson(entry.content()).node("version").isEqualTo(2);
        assertThatJson(entry.content()).node("updated").isEqualTo(true);
    }
}
