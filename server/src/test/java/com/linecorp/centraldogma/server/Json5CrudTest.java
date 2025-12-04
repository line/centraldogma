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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class Json5CrudTest {

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
    void pushAndReadRawJson5File() {
        //language=JSON5
        final String json5Text = "{\n" +
                                 "  // This is a comment\n" +
                                 "  name: 'John Doe',\n" +
                                 "  age: 30,\n" +
                                 "  active: true,\n" +
                                 "  tags: ['developer', 'java'],\n" +
                                 "  /* Multi-line\n" +
                                 "     comment */\n" +
                                 "  metadata: {\n" +
                                 "    created: '2025-01-01',\n" +
                                 "    updated: '2025-01-15',\n" +
                                 "  },\n" +
                                 '}';

        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json5", json5Text);
        final PushResult result = repo.commit("Add JSON5 file", change).push().join();
        assertThat(result.revision().major()).isPositive();

        @SuppressWarnings("unchecked")
        final Entry<JsonNode> rawEntry = (Entry<JsonNode>) repo.file("/config.json5")
                                                               .viewRaw(true)
                                                               .get()
                                                               .join();

        assertThat(rawEntry.rawContent()).isEqualTo(json5Text);
        assertThat(rawEntry.content()).isNotNull();

        assertThatJson(rawEntry.content()).node("name").isEqualTo("John Doe");
        assertThatJson(rawEntry.content()).node("age").isEqualTo(30);
        assertThatJson(rawEntry.content()).node("active").isEqualTo(true);
        assertThatJson(rawEntry.content()).node("tags").isArray().ofLength(2);
        assertThatJson(rawEntry.content()).node("metadata.created").isEqualTo("2025-01-01");
        assertThatJson(rawEntry.content()).node("metadata.updated").isEqualTo("2025-01-15");
    }

    @Test
    void readJson5FileWithoutViewRaw() {
        //language=JSON5
        final String json5Text = "{\n" +
                                 "  // Configuration\n" +
                                 "  server: {\n" +
                                 "    host: 'localhost',\n" +
                                 "    port: 8080,\n" +
                                 "  },\n" +
                                 '}';

        final Change<JsonNode> change = Change.ofJsonUpsert("/server.json5", json5Text);
        repo.commit("Add server config", change).push().join();

        @SuppressWarnings("unchecked")
        final Entry<JsonNode> entry = (Entry<JsonNode>) repo.file("/server.json5").get().join();

        assertThat(entry.rawContent()).isNull();
        assertThat(entry.content()).isNotNull();

        //language=JSON
        assertThatJson(entry.content()).isEqualTo("{\n" +
                                                  "  \"server\": {\n" +
                                                  "    \"host\": \"localhost\",\n" +
                                                  "    \"port\": 8080\n" +
                                                  "  }\n" +
                                                  '}');
    }

    @Test
    void readJson5FileAsJson() {
        //language=JSON5
        final String json5Text = "{\n" +
                                 "  /* a comment */\n" +
                                 "  unquoted: 'value',\n" +
                                 "  singleQuotes: 'string',\n" +
                                 "  \"doubleQuotes\": \"json5\"," +
                                 " multlineString: 'This is a \\" +
                                 "    multiline',\n" +
                                 "lineBreaks: \"Look, Mom! \\" +
                                 "No \\n's!\"" +
                                 '}';

        final Change<JsonNode> change = Change.ofJsonUpsert("/data.json5", json5Text);
        repo.commit("Add data", change).push().join();

        final Entry<JsonNode> jsonEntry = repo.file(Query.ofJson("/data.json5"))
                                              .get()
                                              .join();

        assertThat(jsonEntry.rawContent()).isNull();
        assertThat(jsonEntry.content()).isNotNull();
        assertThat(jsonEntry.content().get("lineBreaks").asText()).isEqualTo("Look, Mom! No \n's!");
        //language=JSON
        assertThatJson(jsonEntry.content()).isEqualTo("{\n" +
                                                      "  \"unquoted\": \"value\",\n" +
                                                      "  \"singleQuotes\": \"string\",\n" +
                                                      "  \"doubleQuotes\": \"json5\",\n" +
                                                      "  \"multlineString\": \"This is a     multiline\",\n" +
                                                      "  \"lineBreaks\": \"Look, Mom! No \\n's!\"\n" +
                                                      '}');
    }

    @Test
    void readJson5FileAsText() {
        //language=JSON5
        final String json5Text = "{ key: 'value', number: 123 }";

        final Change<JsonNode> change = Change.ofJsonUpsert("/simple.json5", json5Text);
        repo.commit("Add simple data", change).push().join();

        final Entry<String> textEntry = repo.file(Query.ofText("/simple.json5"))
                                            .get()
                                            .join();

        assertThat(textEntry.content()).isNotNull();
        assertThat(textEntry.content()).isEqualTo("{\"key\":\"value\",\"number\":123}");
    }

    @Test
    void readJson5FileAsTextWithViewRaw() throws JsonParseException {
        //language=JSON5
        final String json5Text = "{ key: 'value',  number: 123, }";

        final Change<JsonNode> change = Change.ofJsonUpsert("/simple.json5", json5Text);
        repo.commit("Add simple data", change).push().join();

        final Entry<String> textEntry = repo.file(Query.ofText("/simple.json5"))
                                            .viewRaw(true)
                                            .get()
                                            .join();

        assertThat(textEntry.content()).isNotNull();
        assertThat(textEntry.rawContent()).isEqualTo(json5Text);
        assertThatJson(textEntry.contentAsJson()).isEqualTo("{\"key\":\"value\",\"number\":123}");
    }

    @Test
    void jsonPathQueryOnJson5File() {
        //language=JSON5
        final String json5Text = "{\n" +
                                 "  // User data\n" +
                                 "  users: [\n" +
                                 "    { id: 1, name: 'Alice', role: 'admin' },\n" +
                                 "    { id: 2, name: 'Bob', role: 'user' },\n" +
                                 "    { id: 3, name: 'Charlie', role: 'user' },\n" +
                                 "  ],\n" +
                                 "  settings: {\n" +
                                 "    theme: 'dark',\n" +
                                 "    language: 'en',\n" +
                                 "  },\n" +
                                 '}';

        final Change<JsonNode> change = Change.ofJsonUpsert("/users.json5", json5Text);
        repo.commit("Add users", change).push().join();

        final Entry<JsonNode> usersEntry = repo.file(Query.ofJsonPath("/users.json5", "$.users"))
                                               .get()
                                               .join();

        assertThat(usersEntry.content()).isNotNull();
        assertThatJson(usersEntry.content()).isArray().ofLength(3);
        assertThatJson(usersEntry.content()).node("[0].name").isEqualTo("Alice");
        assertThatJson(usersEntry.content()).node("[1].name").isEqualTo("Bob");
        assertThatJson(usersEntry.content()).node("[2].name").isEqualTo("Charlie");

        // 다른 JSON Path 쿼리
        final Entry<JsonNode> themeEntry = repo.file(Query.ofJsonPath("/users.json5", "$.settings.theme"))
                                               .get()
                                               .join();

        assertThatJson(themeEntry.content()).isEqualTo("\"dark\"");

        // 배열 필터링
        final Entry<JsonNode> adminEntry = repo.file(Query.ofJsonPath("/users.json5",
                                                                      "$.users[?(@.role == 'admin')]"))
                                               .get()
                                               .join();

        assertThatJson(adminEntry.content()).isArray().ofLength(1);
        assertThatJson(adminEntry.content()).node("[0].name").isEqualTo("Alice");
    }

    @Test
    void jsonPathQueryCannotBeUsedWithViewRaw() {
        //language=JSON5
        final String json5Text = "{ key: 'value', number: 42 }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/test.json5", json5Text);
        repo.commit("Add test file", change).push().join();

        // JSON_PATH 쿼리는 viewRaw=true와 함께 사용할 수 없음
        final Query<JsonNode> jsonPathQuery = Query.ofJsonPath("/test.json5", "$.key");
        assertThatThrownBy(() -> repo.file(jsonPathQuery).viewRaw(true).get().join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON_PATH query cannot be used with raw view");

        // viewRaw=false (기본값)는 정상 작동
        final Entry<JsonNode> entry = repo.file(jsonPathQuery).get().join();
        assertThatJson(entry.content()).isEqualTo("\"value\"");
    }

    @Test
    void getMultipleJson5FilesWithViewRaw() {
        //language=JSON5
        final String json5Text1 = "{\n" +
                                  "  // Config 1\n" +
                                  "  name: 'config1',\n" +
                                  "  value: 100,\n" +
                                  '}';
        //language=JSON5
        final String json5Text2 = "{\n" +
                                  "  // Config 2\n" +
                                  "  name: 'config2',\n" +
                                  "  value: 200,\n" +
                                  '}';

        final Change<JsonNode> change1 = Change.ofJsonUpsert("/configs/config1.json5", json5Text1);
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/configs/config2.json5", json5Text2);
        repo.commit("Add configs", change1, change2).push().join();

        // viewRaw=true로 여러 파일 읽기
        final Map<String, Entry<?>> entriesWithRaw = repo.file(PathPattern.of("/configs/*.json5"))
                                                         .viewRaw(true)
                                                         .get()
                                                         .join();

        assertThat(entriesWithRaw).hasSize(2);

        final Entry<?> entry1 = entriesWithRaw.get("/configs/config1.json5");
        assertThat(entry1.rawContent()).isEqualTo(json5Text1);
        assertThatJson(entry1.content()).node("name").isEqualTo("config1");
        assertThatJson(entry1.content()).node("value").isEqualTo(100);

        final Entry<?> entry2 = entriesWithRaw.get("/configs/config2.json5");
        assertThat(entry2.rawContent()).isEqualTo(json5Text2);
        assertThatJson(entry2.content()).node("name").isEqualTo("config2");
        assertThatJson(entry2.content()).node("value").isEqualTo(200);
    }

    @Test
    void getMultipleJson5FilesWithoutViewRaw() {
        //language=JSON5
        final String json5Text1 = "{ key1: 'value1' }";
        //language=JSON5
        final String json5Text2 = "{ key2: 'value2' }";

        final Change<JsonNode> change1 = Change.ofJsonUpsert("/data/file1.json5", json5Text1);
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/data/file2.json5", json5Text2);
        repo.commit("Add data files", change1, change2).push().join();

        // viewRaw=false (기본값)로 여러 파일 읽기
        final Map<String, Entry<?>> entries = repo.file(PathPattern.of("/data/*.json5"))
                                                  .get()
                                                  .join();

        assertThat(entries).hasSize(2);

        final Entry<?> entry1 = entries.get("/data/file1.json5");
        assertThat(entry1.rawContent()).isNull();
        assertThatJson(entry1.content()).node("key1").isEqualTo("value1");

        final Entry<?> entry2 = entries.get("/data/file2.json5");
        assertThat(entry2.rawContent()).isNull();
        assertThatJson(entry2.content()).node("key2").isEqualTo("value2");
    }

    @Test
    void watchJson5FileWithViewRaw() {
        //language=JSON5
        final String initialJson5Text = "{\n" +
                                        "  // Version 1\n" +
                                        "  version: 1,\n" +
                                        "  status: 'active',\n" +
                                        '}';
        final Change<JsonNode> initialChange = Change.ofJsonUpsert("/watch.json5", initialJson5Text);
        final PushResult initialResult = repo.commit("Initial version", initialChange).push().join();

        //language=JSON5
        final String updatedJson5Text = "{\n" +
                                        "  // Version 2\n" +
                                        "  version: 2,\n" +
                                        "  status: 'updated',\n" +
                                        "  timestamp: '2025-01-15',\n" +
                                        '}';
        final Change<JsonNode> updateChange = Change.ofJsonUpsert("/watch.json5", updatedJson5Text);
        repo.commit("Update version", updateChange).push().join();

        final Entry<JsonNode> entryWithRaw = repo.watch(Query.ofJson("/watch.json5"))
                                                 .viewRaw(true)
                                                 .start(initialResult.revision())
                                                 .join();

        assertThat(entryWithRaw.rawContent()).isEqualTo(updatedJson5Text);
        assertThatJson(entryWithRaw.content()).node("version").isEqualTo(2);
        assertThatJson(entryWithRaw.content()).node("status").isEqualTo("updated");
        assertThatJson(entryWithRaw.content()).node("timestamp").isEqualTo("2025-01-15");
    }

    @Test
    void watchJson5FileWithoutViewRaw() {
        //language=JSON5
        final String initialJson5Text = "{ counter: 1 }";
        final Change<JsonNode> initialChange = Change.ofJsonUpsert("/counter.json5", initialJson5Text);
        final PushResult initialResult = repo.commit("Initial counter", initialChange).push().join();

        //language=JSON5
        final String updatedJson5Text = "{ counter: 2, updated: true }";
        final Change<JsonNode> updateChange = Change.ofJsonUpsert("/counter.json5", updatedJson5Text);
        repo.commit("Increment counter", updateChange).push().join();

        final Entry<JsonNode> entry = repo.watch(Query.ofJson("/counter.json5"))
                                          .start(initialResult.revision())
                                          .join();

        assertThat(entry.rawContent()).isNull();
        assertThatJson(entry.content()).node("counter").isEqualTo(2);
        assertThatJson(entry.content()).node("updated").isEqualTo(true);
    }

    @Test
    void watchJson5FileWithJsonPath() {
        //language=JSON5
        final String initialJson5Text = "{\n" +
                                        "  config: {\n" +
                                        "    enabled: false,\n" +
                                        "    timeout: 30,\n" +
                                        "  },\n" +
                                        '}';
        final Change<JsonNode> initialChange = Change.ofJsonUpsert("/config.json5", initialJson5Text);
        final PushResult initialResult = repo.commit("Initial config", initialChange).push().join();

        //language=JSON5
        final String updatedJson5Text = "{\n" +
                                        "  config: {\n" +
                                        "    enabled: true,\n" +
                                        "    timeout: 60,\n" +
                                        "  },\n" +
                                        '}';
        final Change<JsonNode> updateChange = Change.ofJsonUpsert("/config.json5", updatedJson5Text);
        repo.commit("Update config", updateChange).push().join();

        // JSON Path로 특정 필드만 watch
        final Entry<JsonNode> enabledEntry = repo.watch(Query.ofJsonPath("/config.json5", "$.config.enabled"))
                                                 .start(initialResult.revision())
                                                 .join();

        assertThatJson(enabledEntry.content()).isEqualTo("true");

        final Entry<JsonNode> timeoutEntry = repo.watch(Query.ofJsonPath("/config.json5", "$.config.timeout"))
                                                 .start(initialResult.revision())
                                                 .join();

        assertThatJson(timeoutEntry.content()).isEqualTo("60");
    }

    @Test
    void watchJson5FileWithJsonPathCannotUseViewRaw() {
        //language=JSON5
        final String json5Text = "{ key: 'value', number: 42 }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/test.json5", json5Text);
        repo.commit("Add test file", change).push().join();

        // JSON_PATH 쿼리는 viewRaw=true와 함께 사용할 수 없음 (watch)
        final Query<JsonNode> jsonPathQuery = Query.ofJsonPath("/test.json5", "$.key");
        assertThatThrownBy(() -> repo.watch(jsonPathQuery).viewRaw(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON_PATH query cannot be used with raw view");

        // viewRaw=false (default) works fine
        assertThat(repo.watch(jsonPathQuery).viewRaw(false)).isNotNull();
        assertThat(repo.watch(jsonPathQuery)).isNotNull();
    }
}
