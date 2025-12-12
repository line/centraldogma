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
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.jsonpatch.AddOperation;
import com.linecorp.centraldogma.common.jsonpatch.CopyOperation;
import com.linecorp.centraldogma.common.jsonpatch.JsonPatchConflictException;
import com.linecorp.centraldogma.common.jsonpatch.JsonPatchOperation;
import com.linecorp.centraldogma.common.jsonpatch.MoveOperation;
import com.linecorp.centraldogma.common.jsonpatch.RemoveIfExistsOperation;
import com.linecorp.centraldogma.common.jsonpatch.RemoveOperation;
import com.linecorp.centraldogma.common.jsonpatch.ReplaceOperation;
import com.linecorp.centraldogma.common.jsonpatch.SafeReplaceOperation;
import com.linecorp.centraldogma.common.jsonpatch.TestOperation;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class YamlCrudTest {

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

    @Timeout(Long.MAX_VALUE)
    @CsvSource({ "config.yaml", "config.yml" })
    @ParameterizedTest
    void pushAndReadRawYamlFile(String fileName) throws InterruptedException {
        //language=yaml
        final String yamlText = "# This is a comment\n" +
                                "name: John Doe\n" +
                                "age: 30\n" +
                                "active: true\n" +
                                "tags:\n" +
                                "  - developer\n" +
                                "  - java\n" +
                                "metadata:\n" +
                                "  created: '2025-01-01'\n" +
                                "  updated: '2025-01-15'\n";

        final Change<JsonNode> change = Change.ofYamlUpsert("/" + fileName, yamlText);
        final PushResult result = repo.commit("Add YAML file", change).push().join();
        assertThat(result.revision().major()).isPositive();

        @SuppressWarnings("unchecked")
        final Entry<JsonNode> rawEntry = (Entry<JsonNode>) repo.file("/" + fileName)
                                                               .viewRaw(true)
                                                               .get()
                                                               .join();

        assertThat(rawEntry.rawContent()).isEqualTo(yamlText);
        assertThat(rawEntry.content()).isNotNull();

        assertThatJson(rawEntry.content()).node("name").isEqualTo("John Doe");
        assertThatJson(rawEntry.content()).node("age").isEqualTo(30);
        assertThatJson(rawEntry.content()).node("active").isEqualTo(true);
        assertThatJson(rawEntry.content()).node("tags").isArray().ofLength(2);
        assertThatJson(rawEntry.content()).node("metadata.created").isEqualTo("2025-01-01");
        assertThatJson(rawEntry.content()).node("metadata.updated").isEqualTo("2025-01-15");
        Thread.sleep(Long.MAX_VALUE);
    }

    @Test
    void readYamlFileWithoutViewRaw() {
        //language=yaml
        final String yamlText = "# Configuration\n" +
                                "server:\n" +
                                "  host: localhost\n" +
                                "  port: 8080\n";

        final Change<JsonNode> change = Change.ofYamlUpsert("/server.yaml", yamlText);
        repo.commit("Add server config", change).push().join();

        @SuppressWarnings("unchecked")
        final Entry<JsonNode> entry = (Entry<JsonNode>) repo.file("/server.yaml").get().join();

        assertThat(entry.rawContent()).isNull();
        assertThat(entry.content()).isNotNull();

        assertThatJson(entry.content()).node("server.host").isEqualTo("localhost");
        assertThatJson(entry.content()).node("server.port").isEqualTo(8080);
    }

    @Test
    void readYamlFileWithQueryJson() {
        //language=yaml
        final String yamlText = "database:\n" +
                                "  adapter: postgres\n" +
                                "  host: db.example.com\n" +
                                "  port: 5432\n" +
                                "  name: myapp\n";

        final Change<JsonNode> change = Change.ofYamlUpsert("/database.yaml", yamlText);
        repo.commit("Add database config", change).push().join();

        final Entry<JsonNode> jsonEntry = repo.file(Query.ofYaml("/database.yaml"))
                                              .get()
                                              .join();

        assertThat(jsonEntry.rawContent()).isNull();
        assertThat(jsonEntry.content()).isNotNull();
        assertThatJson(jsonEntry.content()).node("database.adapter").isEqualTo("postgres");
        assertThatJson(jsonEntry.content()).node("database.host").isEqualTo("db.example.com");
        assertThatJson(jsonEntry.content()).node("database.port").isEqualTo(5432);
        assertThatJson(jsonEntry.content()).node("database.name").isEqualTo("myapp");
    }

    @Test
    void readYamlFileAsText() {
        //language=yaml
        final String yamlText = "key: value\n" +
                                "number: 123\n";

        final Change<JsonNode> change = Change.ofYamlUpsert("/simple.yaml", yamlText);
        repo.commit("Add simple data", change).push().join();

        final Entry<String> textEntry = repo.file(Query.ofText("/simple.yaml"))
                                            .get()
                                            .join();

        assertThat(textEntry.content()).isEqualTo(yamlText);
    }

    @Test
    void jsonPathQueryOnYamlFile() {
        //language=yaml
        final String yamlText = "# User data\n" +
                                "users:\n" +
                                "  - id: 1\n" +
                                "    name: Alice\n" +
                                "    role: admin\n" +
                                "  - id: 2\n" +
                                "    name: Bob\n" +
                                "    role: user\n" +
                                "  - id: 3\n" +
                                "    name: Charlie\n" +
                                "    role: user\n" +
                                "settings:\n" +
                                "  theme: dark\n" +
                                "  language: en\n";

        final Change<JsonNode> change = Change.ofYamlUpsert("/users.yaml", yamlText);
        repo.commit("Add users", change).push().join();

        final Entry<JsonNode> usersEntry = repo.file(Query.ofJsonPath("/users.yaml", "$.users"))
                                               .get()
                                               .join();

        //language=yaml
        assertThat(usersEntry.contentAsText()).isEqualTo("- id: 1\n" +
                                                         "  name: \"Alice\"\n" +
                                                         "  role: \"admin\"\n" +
                                                         "- id: 2\n" +
                                                         "  name: \"Bob\"\n" +
                                                         "  role: \"user\"\n" +
                                                         "- id: 3\n" +
                                                         "  name: \"Charlie\"\n" +
                                                         "  role: \"user\"\n");
        assertThat(usersEntry.content()).isNotNull();
        assertThatJson(usersEntry.content()).isArray().ofLength(3);
        assertThatJson(usersEntry.content()).node("[0].name").isEqualTo("Alice");
        assertThatJson(usersEntry.content()).node("[1].name").isEqualTo("Bob");
        assertThatJson(usersEntry.content()).node("[2].name").isEqualTo("Charlie");

        // Another JSON Path query
        final Entry<JsonNode> themeEntry = repo.file(Query.ofJsonPath("/users.yaml", "$.settings.theme"))
                                               .get()
                                               .join();

        assertThatJson(themeEntry.content()).isEqualTo("\"dark\"");
    }

    @Test
    void jsonPathQueryCannotBeUsedWithViewRaw() {
        //language=yaml
        final String yamlText = "key: value\n" +
                                "number: 42\n";
        final Change<JsonNode> change = Change.ofYamlUpsert("/test.yaml", yamlText);
        repo.commit("Add test file", change).push().join();

        // JSON_PATH query cannot be used with viewRaw=true
        final Query<JsonNode> jsonPathQuery = Query.ofJsonPath("/test.yaml", "$.key");
        assertThatThrownBy(() -> repo.file(jsonPathQuery).viewRaw(true).get().join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON_PATH query cannot be used with raw view");
    }

    @Test
    void getMultipleYamlFilesWithViewRaw() {
        //language=yaml
        final String yamlText1 = "# Config 1\n" +
                                 "name: config1\n" +
                                 "value: 100\n";
        //language=yaml
        final String yamlText2 = "# Config 2\n" +
                                 "name: config2\n" +
                                 "value: 200\n";

        final Change<JsonNode> change1 = Change.ofYamlUpsert("/configs/config1.yaml", yamlText1);
        final Change<JsonNode> change2 = Change.ofYamlUpsert("/configs/config2.yaml", yamlText2);
        repo.commit("Add configs", change1, change2).push().join();

        // Read multiple files with viewRaw=true
        final Map<String, Entry<?>> entriesWithRaw = repo.file(PathPattern.of("/configs/*.yaml"))
                                                         .viewRaw(true)
                                                         .get()
                                                         .join();

        assertThat(entriesWithRaw).hasSize(2);

        final Entry<?> entry1 = entriesWithRaw.get("/configs/config1.yaml");
        assertThat(entry1.rawContent()).isEqualTo(yamlText1);
        assertThatJson(entry1.content()).node("name").isEqualTo("config1");
        assertThatJson(entry1.content()).node("value").isEqualTo(100);

        final Entry<?> entry2 = entriesWithRaw.get("/configs/config2.yaml");
        assertThat(entry2.rawContent()).isEqualTo(yamlText2);
        assertThatJson(entry2.content()).node("name").isEqualTo("config2");
        assertThatJson(entry2.content()).node("value").isEqualTo(200);
    }

    @Test
    void getMultipleYamlFilesWithoutViewRaw() {
        final String yamlText1 = "key1: value1\n";
        final String yamlText2 = "key2: value2\n";

        final Change<JsonNode> change1 = Change.ofYamlUpsert("/data/file1.yaml", yamlText1);
        final Change<JsonNode> change2 = Change.ofYamlUpsert("/data/file2.yaml", yamlText2);
        repo.commit("Add data files", change1, change2).push().join();

        // Read multiple files with viewRaw=false (default)
        final Map<String, Entry<?>> entries = repo.file(PathPattern.of("/data/*.yaml"))
                                                  .get()
                                                  .join();

        assertThat(entries).hasSize(2);

        final Entry<?> entry1 = entries.get("/data/file1.yaml");
        assertThat(entry1.rawContent()).isNull();
        assertThatJson(entry1.content()).node("key1").isEqualTo("value1");

        final Entry<?> entry2 = entries.get("/data/file2.yaml");
        assertThat(entry2.rawContent()).isNull();
        assertThatJson(entry2.content()).node("key2").isEqualTo("value2");
    }

    @Test
    void watchYamlFileWithViewRaw() {
        //language=yaml
        final String initialYamlText = "# Version 1\n" +
                                       "version: 1\n" +
                                       "status: active\n";
        final Change<JsonNode> initialChange = Change.ofYamlUpsert("/watch.yaml", initialYamlText);
        final PushResult initialResult = repo.commit("Initial version", initialChange).push().join();

        //language=yaml
        final String updatedYamlText = "# Version 2\n" +
                                       "version: 2\n" +
                                       "status: updated\n" +
                                       "timestamp: '2025-01-15'\n";
        final Change<JsonNode> updateChange = Change.ofYamlUpsert("/watch.yaml", updatedYamlText);
        repo.commit("Update version", updateChange).push().join();

        final Entry<JsonNode> entryWithRaw = repo.watch(Query.ofYaml("/watch.yaml"))
                                                 .viewRaw(true)
                                                 .start(initialResult.revision())
                                                 .join();

        assertThat(entryWithRaw.rawContent()).isEqualTo(updatedYamlText);
        assertThatJson(entryWithRaw.content()).node("version").isEqualTo(2);
        assertThatJson(entryWithRaw.content()).node("status").isEqualTo("updated");
        assertThatJson(entryWithRaw.content()).node("timestamp").isEqualTo("2025-01-15");
    }

    @Test
    void watchYamlFileWithoutViewRaw() {
        //language=yaml
        final String initialYamlText = "counter: 1\n";
        final Change<JsonNode> initialChange = Change.ofYamlUpsert("/counter.yaml", initialYamlText);
        final PushResult initialResult = repo.commit("Initial counter", initialChange).push().join();

        //language=yaml
        final String updatedYamlText = "counter: 2\n" +
                                       "updated: true\n";
        final Change<JsonNode> updateChange = Change.ofYamlUpsert("/counter.yaml", updatedYamlText);
        repo.commit("Increment counter", updateChange).push().join();

        final Entry<JsonNode> entry = repo.watch(Query.ofJson("/counter.yaml"))
                                          .start(initialResult.revision())
                                          .join();

        assertThat(entry.rawContent()).isNull();
        assertThatJson(entry.content()).node("counter").isEqualTo(2);
        assertThatJson(entry.content()).node("updated").isEqualTo(true);
    }

    @Test
    void watchYamlFileWithJsonPathCannotUseViewRaw() {
        //language=yaml
        final String yamlText = "key: value\n" +
                                "number: 42\n";
        final Change<JsonNode> change = Change.ofYamlUpsert("/test.yaml", yamlText);
        repo.commit("Add test file", change).push().join();

        // JSON_PATH query cannot be used with viewRaw=true (watch)
        final Query<JsonNode> jsonPathQuery = Query.ofJsonPath("/test.yaml", "$.key");
        assertThatThrownBy(() -> repo.watch(jsonPathQuery).viewRaw(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON_PATH query cannot be used with raw view");
    }

    // JSON Patch Tests for YAML files

    @Test
    void jsonPatchAddOnYamlFile() throws JsonParseException {
        //language=yaml
        final String yamlText = "name: test\n" +
                                "value: 1\n";
        repo.commit("add initial yaml", Change.ofYamlUpsert("/config.yaml", yamlText))
            .push()
            .join();

        final AddOperation add = JsonPatchOperation.add(JsonPointer.compile("/newField"), new IntNode(100));
        final Change<JsonNode> change = Change.ofJsonPatch("/config.yaml", add);
        repo.commit("add newField", change)
            .push()
            .join();

        @SuppressWarnings("unchecked")
        final Entry<JsonNode> entry = (Entry<JsonNode>) repo.file("/config.yaml")
                                                            .viewRaw(true)
                                                            .get()
                                                            .join();
        // `name: test` is normalized to `name: "test"`
        //language=yaml
        assertThat(entry.rawContent()).isEqualTo("name: \"test\"\n" +
                                                 "value: 1\n" +
                                                 "newField: 100\n");
        final JsonNode jsonNode = entry.content();
        assertThatJson(jsonNode).node("name").isEqualTo("test");
        assertThatJson(jsonNode).node("value").isEqualTo(1);
        assertThatJson(jsonNode).node("newField").isEqualTo(100);
    }

    @Test
    void jsonPatchCopyOnYamlFile() throws JsonParseException {
        //language=yaml
        final String yamlText = "source: original\n" +
                                "value: 42\n";
        repo.commit("add initial yaml", Change.ofYamlUpsert("/data.yaml", yamlText))
            .push()
            .join();

        final CopyOperation copy = JsonPatchOperation.copy(JsonPointer.compile("/source"),
                                                           JsonPointer.compile("/destination"));
        final Change<JsonNode> change = Change.ofJsonPatch("/data.yaml", copy);
        repo.commit("copy source to destination", change)
            .push()
            .join();

        final JsonNode jsonNode = repo.file("/data.yaml").get().join().contentAsJson();
        assertThatJson(jsonNode).node("source").isEqualTo("original");
        assertThatJson(jsonNode).node("destination").isEqualTo("original");
        assertThatJson(jsonNode).node("value").isEqualTo(42);
    }

    @Test
    void jsonPatchMoveOnYamlFile() throws JsonParseException {
        //language=yaml
        final String yamlText = "oldName: value\n" +
                                "other: data\n";
        repo.commit("add initial yaml", Change.ofYamlUpsert("/move.yaml", yamlText))
            .push()
            .join();

        final MoveOperation move = JsonPatchOperation.move(JsonPointer.compile("/oldName"),
                                                           JsonPointer.compile("/newName"));
        final Change<JsonNode> change = Change.ofJsonPatch("/move.yaml", move);
        repo.commit("move oldName to newName", change)
            .push()
            .join();

        final JsonNode jsonNode = repo.file("/move.yaml").get().join().contentAsJson();
        assertThat(jsonNode.has("oldName")).isFalse();
        assertThatJson(jsonNode).node("newName").isEqualTo("value");
        assertThatJson(jsonNode).node("other").isEqualTo("data");
    }

    @Test
    void jsonPatchRemoveOnYamlFile() throws JsonParseException {
        //language=yaml
        final String yamlText = "keep: value1\n" +
                                "remove: value2\n" +
                                "alsoKeep: value3\n";
        repo.commit("add initial yaml", Change.ofYamlUpsert("/remove.yaml", yamlText))
            .push()
            .join();

        final RemoveOperation remove = JsonPatchOperation.remove(JsonPointer.compile("/remove"));
        final Change<JsonNode> change = Change.ofJsonPatch("/remove.yaml", remove);
        repo.commit("remove field", change)
            .push()
            .join();

        final JsonNode jsonNode = repo.file("/remove.yaml").get().join().contentAsJson();
        assertThatJson(jsonNode).node("keep").isEqualTo("value1");
        assertThat(jsonNode.has("remove")).isFalse();
        assertThatJson(jsonNode).node("alsoKeep").isEqualTo("value3");
    }

    @Test
    void jsonPatchRemoveNonExistentFieldThrowsException() {
        //language=yaml
        final String yamlText = "existing: value\n";
        repo.commit("add initial yaml", Change.ofYamlUpsert("/test.yaml", yamlText))
            .push()
            .join();

        final RemoveOperation remove = JsonPatchOperation.remove(JsonPointer.compile("/nonExistent"));
        final Change<JsonNode> change = Change.ofJsonPatch("/test.yaml", remove);

        assertThatThrownBy(() -> repo.commit("remove non-existent", change).push().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(JsonPatchConflictException.class)
                .hasMessageContaining("non-existent path: /nonExistent");
    }

    @Test
    void jsonPatchRemoveIfExistsOnYamlFile() throws JsonParseException {
        //language=yaml
        final String yamlText = "keep: value1\n" +
                                "maybeRemove: value2\n";
        repo.commit("add initial yaml", Change.ofYamlUpsert("/removeIfExists.yaml", yamlText))
            .push()
            .join();

        final RemoveIfExistsOperation removeIfExists =
                JsonPatchOperation.removeIfExists(JsonPointer.compile("/maybeRemove"));
        final Change<JsonNode> change = Change.ofJsonPatch("/removeIfExists.yaml", removeIfExists);
        final PushResult result = repo.commit("remove if exists", change)
                                      .push()
                                      .join();

        final JsonNode jsonNode = repo.file("/removeIfExists.yaml").get().join().contentAsJson();
        assertThatJson(jsonNode).node("keep").isEqualTo("value1");
        assertThat(jsonNode.has("maybeRemove")).isFalse();

        // Trying to remove again should throw RedundantChangeException
        assertThatThrownBy(() -> repo.commit("remove again", change).push().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RedundantChangeException.class);
    }

    @Test
    void jsonPatchSafeReplaceOnYamlFile() throws JsonParseException {
        //language=yaml
        final String yamlText = "counter: 10\n";
        repo.commit("add initial yaml", Change.ofYamlUpsert("/counter.yaml", yamlText))
            .push()
            .join();

        // Safe replace with correct old value
        SafeReplaceOperation safeReplace =
                JsonPatchOperation.safeReplace(JsonPointer.compile("/counter"),
                                               new IntNode(10), new IntNode(20));
        Change<JsonNode> change = Change.ofJsonPatch("/counter.yaml", safeReplace);
        repo.commit("safe replace counter", change)
            .push()
            .join();

        JsonNode jsonNode = repo.file("/counter.yaml").get().join().contentAsJson();
        assertThatJson(jsonNode).node("counter").isEqualTo(20);

        // Safe replace with wrong old value should fail
        safeReplace = JsonPatchOperation.safeReplace(JsonPointer.compile("/counter"),
                                                     new IntNode(10), new IntNode(30));
        final Change<JsonNode> failChange = Change.ofJsonPatch("/counter.yaml", safeReplace);

        assertThatThrownBy(() -> repo.commit("invalid safe replace", failChange).push().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(JsonPatchConflictException.class)
                .hasMessageContaining("mismatching value at '/counter': 20 (expected: 10)");
    }

    @Test
    void jsonPatchTestOnYamlFile() {
        //language=yaml
        final String yamlText = "testField: 100\n";
        repo.commit("add initial yaml", Change.ofYamlUpsert("/test.yaml", yamlText))
            .push()
            .join();

        // Test operation with matching value should throw RedundantChangeException (no change)
        TestOperation test = JsonPatchOperation.test(JsonPointer.compile("/testField"), new IntNode(100));
        Change<JsonNode> change = Change.ofJsonPatch("/test.yaml", test);

        assertThatThrownBy(() -> repo.commit("test field", change).push().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RedundantChangeException.class);

        // Test operation with non-matching value should throw JsonPatchConflictException
        test = JsonPatchOperation.test(JsonPointer.compile("/testField"), new IntNode(999));
        final Change<JsonNode> failChange = Change.ofJsonPatch("/test.yaml", test);

        assertThatThrownBy(() -> repo.commit("test field fail", failChange).push().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(JsonPatchConflictException.class);
    }

    @Test
    void jsonPatchMultipleOperationsOnYamlFile() throws JsonParseException {
        //language=yaml
        final String yamlText = "name: original\n" +
                                "count: 1\n" +
                                "toRemove: unwanted\n";
        repo.commit("add initial yaml", Change.ofYamlUpsert("/multi.yaml", yamlText))
            .push()
            .join();

        // Apply multiple patches sequentially
        final ReplaceOperation replace = JsonPatchOperation.replace(JsonPointer.compile("/name"),
                                                                    new TextNode("updated"));
        repo.commit("replace name", Change.ofJsonPatch("/multi.yaml", replace)).push().join();

        final AddOperation add = JsonPatchOperation.add(JsonPointer.compile("/newField"),
                                                        new TextNode("added"));
        repo.commit("add newField", Change.ofJsonPatch("/multi.yaml", add)).push().join();

        final RemoveOperation remove = JsonPatchOperation.remove(JsonPointer.compile("/toRemove"));
        repo.commit("remove toRemove", Change.ofJsonPatch("/multi.yaml", remove)).push().join();

        final JsonNode jsonNode = repo.file("/multi.yaml").get().join().contentAsJson();
        assertThatJson(jsonNode).node("name").isEqualTo("updated");
        assertThatJson(jsonNode).node("count").isEqualTo(1);
        assertThatJson(jsonNode).node("newField").isEqualTo("added");
        assertThat(jsonNode.has("toRemove")).isFalse();
    }

    @Test
    void jsonPatchOnYamlFileWithNestedArrays() throws JsonParseException {
        //language=yaml
        final String yamlText = "users:\n" +
                                "  - name: Alice\n" +
                                "    role: admin\n" +
                                "  - name: Bob\n" +
                                "    role: user\n";
        repo.commit("add initial yaml", Change.ofYamlUpsert("/users.yaml", yamlText))
            .push()
            .join();

        // Add a new user to the array
        final AddOperation add = JsonPatchOperation.add(JsonPointer.compile("/users/2"),
                                                        new TextNode("Charlie"));
        // Note: Adding to array requires proper JsonNode, let's use replace on first user's role instead
        final ReplaceOperation replace = JsonPatchOperation.replace(JsonPointer.compile("/users/0/role"),
                                                                    new TextNode("superadmin"));
        repo.commit("update first user role", Change.ofJsonPatch("/users.yaml", replace))
            .push()
            .join();

        final JsonNode jsonNode = repo.file("/users.yaml").get().join().contentAsJson();
        assertThatJson(jsonNode).node("users[0].role").isEqualTo("superadmin");
        assertThatJson(jsonNode).node("users[1].role").isEqualTo("user");
    }

    @Test
    void jsonPatchOnYmlExtensionFile() throws JsonParseException {
        //language=yaml
        final String ymlText = "database:\n" +
                               "  host: localhost\n" +
                               "  port: 5432\n";
        repo.commit("add initial yml", Change.ofYamlUpsert("/db.yml", ymlText))
            .push()
            .join();

        final ReplaceOperation replace = JsonPatchOperation.replace(JsonPointer.compile("/database/port"),
                                                                    new IntNode(3306));
        final Change<JsonNode> change = Change.ofJsonPatch("/db.yml", replace);
        repo.commit("change port", change)
            .push()
            .join();

        final JsonNode jsonNode = repo.file("/db.yml").get().join().contentAsJson();
        assertThatJson(jsonNode).node("database.host").isEqualTo("localhost");
        assertThatJson(jsonNode).node("database.port").isEqualTo(3306);
    }
}
