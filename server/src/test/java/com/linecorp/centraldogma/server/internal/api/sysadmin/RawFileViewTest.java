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

package com.linecorp.centraldogma.server.internal.api.sysadmin;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class RawFileViewTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void getFileWithViewRaw() {
        final CentralDogma client = dogma.client();
        final CentralDogmaRepository repo = client.forRepo("foo", "bar");
        // Create a JSON file with additional spaces.
        final String jsonText = "{ \"b\": 2,   \"a\": 1 }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/a.json", jsonText);
        final CompletableFuture<PushResult> result = repo.commit("Add a JSON file", change)
                                                         .push();
        assertThat(result.join().revision().major()).isPositive();
        Entry<?> entry = repo.file("/a.json")
                             .viewRaw(true)
                             .get().join();
        // The raw content should be equal to the original JSON text.
        assertThat(entry.rawContent()).isEqualTo(jsonText);
        assertThat(entry.content()).isNotNull();
        assertThatJson(entry.content()).isEqualTo("{\"b\": 2,\"a\": 1}");

        entry = repo.file("/a.json")
                    .get().join();
        // The raw content should be null when viewRaw is false.
        assertThat(entry.rawContent()).isNull();
        assertThatJson(entry.content()).isEqualTo("{\"b\": 2,\"a\": 1}");
    }

    @Test
    void getFilesWithViewRaw() {
        final CentralDogma client = dogma.client();
        final CentralDogmaRepository repo = client.forRepo("foo", "bar");

        // Create multiple JSON files with additional spaces
        final String jsonText1 = "{ \"key1\": \"value1\",   \"key2\": \"value2\" }";
        final String jsonText2 = "{  \"foo\":  \"bar\"  }";

        final Change<JsonNode> change1 = Change.ofJsonUpsert("/file1.json", jsonText1);
        final Change<JsonNode> change2 = Change.ofJsonUpsert("/file2.json", jsonText2);
        final CompletableFuture<PushResult> result = repo.commit("Add JSON files", change1, change2)
                                                         .push();
        assertThat(result.join().revision().major()).isPositive();

        // Test with viewRaw = true using FilesRequest
        final Map<String, Entry<?>> entriesWithRaw = repo.file(PathPattern.of("/*.json"))
                                                         .viewRaw(true)
                                                         .get()
                                                         .join();

        assertThat(entriesWithRaw).hasSize(2);
        final Entry<?> entry1WithRaw = entriesWithRaw.get("/file1.json");
        assertThat(entry1WithRaw.rawContent()).isEqualTo(jsonText1);
        assertThat(entry1WithRaw.content()).isNotNull();
        assertThatJson(entry1WithRaw.content()).isEqualTo("{\"key1\":\"value1\",\"key2\":\"value2\"}");

        final Entry<?> entry2WithRaw = entriesWithRaw.get("/file2.json");
        assertThat(entry2WithRaw.rawContent()).isEqualTo(jsonText2);
        assertThat(entry2WithRaw.content()).isNotNull();
        assertThatJson(entry2WithRaw.content()).isEqualTo("{\"foo\":\"bar\"}");

        // Test with viewRaw = false (default) using FilesRequest
        final Map<String, Entry<?>> entriesWithoutRaw = repo.file(PathPattern.of("/*.json"))
                                                            .get()
                                                            .join();

        assertThat(entriesWithoutRaw).hasSize(2);
        final Entry<?> entry1WithoutRaw = entriesWithoutRaw.get("/file1.json");
        assertThat(entry1WithoutRaw.rawContent()).isNull();
        assertThatJson(entry1WithoutRaw.content()).isEqualTo("{\"key1\":\"value1\",\"key2\":\"value2\"}");

        final Entry<?> entry2WithoutRaw = entriesWithoutRaw.get("/file2.json");
        assertThat(entry2WithoutRaw.rawContent()).isNull();
        assertThatJson(entry2WithoutRaw.content()).isEqualTo("{\"foo\":\"bar\"}");
    }

    @Test
    void watchWithViewRaw() {
        final CentralDogma client = dogma.client();
        final CentralDogmaRepository repo = client.forRepo("foo", "bar");

        // Create initial JSON file
        final String initialJsonText = "{ \"version\": 1 }";
        final Change<JsonNode> initialChange = Change.ofJsonUpsert("/config.json", initialJsonText);
        final PushResult initialResult = repo.commit("Add config", initialChange).push().join();

        // Update the file with new content
        final String updatedJsonText = "{  \"version\":  2  }";
        final Change<JsonNode> updateChange = Change.ofJsonUpsert("/config.json", updatedJsonText);
        repo.commit("Update config", updateChange).push().join();

        // Watch with viewRaw = true
        final Entry<JsonNode> entryWithRaw = repo.watch(Query.ofJson("/config.json"))
                                                 .viewRaw(true)
                                                 .start(initialResult.revision())
                                                 .join();

        assertThat(entryWithRaw.rawContent()).isEqualTo(updatedJsonText);
        assertThat(entryWithRaw.content()).isNotNull();
        assertThatJson(entryWithRaw.content()).isEqualTo("{\"version\":2}");

        // Watch with viewRaw = false (default)
        final Entry<JsonNode> entryWithoutRaw = repo.watch(Query.ofJson("/config.json"))
                                                    .start(initialResult.revision())
                                                    .join();

        assertThat(entryWithoutRaw.rawContent()).isNull();
        assertThatJson(entryWithoutRaw.content()).isEqualTo("{\"version\":2}");
    }

    @Test
    void jsonPathQueryCannotBeUsedWithViewRawForWatch() {
        final CentralDogma client = dogma.client();
        final CentralDogmaRepository repo = client.forRepo("foo", "bar");

        // Create a JSON file
        final String jsonText = "{ \"key\": \"value\", \"number\": 42 }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/test.json", jsonText);
        repo.commit("Add test file", change).push().join();

        // JSON_PATH query should throw IllegalArgumentException when viewRaw is true
        final Query<JsonNode> jsonPathQuery = Query.ofJsonPath("/test.json", "$.key");
        assertThatThrownBy(() -> repo.watch(jsonPathQuery).viewRaw(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON_PATH query cannot be used with raw view");

        // But it should work fine with viewRaw = false (default)
        assertThat(repo.watch(jsonPathQuery).viewRaw(false)).isNotNull();
        assertThat(repo.watch(jsonPathQuery)).isNotNull();
    }

    @Test
    void otherQueryTypesCanBeUsedWithViewRawForWatch() {
        final CentralDogma client = dogma.client();
        final CentralDogmaRepository repo = client.forRepo("foo", "bar");

        // Create a JSON file
        final String jsonText = "{ \"key\": \"value\", \"number\": 42 }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/test.json", jsonText);
        repo.commit("Add test file", change).push().join();

        // IDENTITY query should work with viewRaw
        assertThat(repo.watch(Query.ofJson("/test.json")).viewRaw(true)).isNotNull();

        // JSON query should work with viewRaw
        assertThat(repo.watch(Query.ofJson("/test.json")).viewRaw(true)).isNotNull();

        // TEXT query should work with viewRaw
        assertThat(repo.watch(Query.ofText("/test.json")).viewRaw(true)).isNotNull();
    }

    @Test
    void jsonPathQueryCannotBeUsedWithViewRawForFile() {
        final CentralDogma client = dogma.client();
        final CentralDogmaRepository repo = client.forRepo("foo", "bar");

        // Create a JSON file
        final String jsonText = "{ \"key\": \"value\", \"number\": 42 }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/test.json", jsonText);
        repo.commit("Add test file", change).push().join();

        // JSON_PATH query should throw IllegalArgumentException when viewRaw is true
        final Query<JsonNode> jsonPathQuery = Query.ofJsonPath("/test.json", "$.key");
        assertThatThrownBy(() -> repo.file(jsonPathQuery).viewRaw(true).get().join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON_PATH query cannot be used with raw view");

        // But it should work fine with viewRaw = false (default)
        assertThat(repo.file(jsonPathQuery).viewRaw(false).get().join()).isNotNull();
        assertThat(repo.file(jsonPathQuery).get().join()).isNotNull();
    }

    @Test
    void otherQueryTypesCanBeUsedWithViewRawForFile() {
        final CentralDogma client = dogma.client();
        final CentralDogmaRepository repo = client.forRepo("foo", "bar");

        // Create a JSON file
        final String jsonText = "{ \"key\": \"value\", \"number\": 42 }";
        final Change<JsonNode> change = Change.ofJsonUpsert("/test.json", jsonText);
        repo.commit("Add test file", change).push().join();

        // JSON query should work with viewRaw
        final Entry<JsonNode> jsonEntry = repo.file(Query.ofJson("/test.json"))
                                              .viewRaw(true)
                                              .get()
                                              .join();
        assertThat(jsonEntry).isNotNull();
        assertThat(jsonEntry.rawContent()).isEqualTo(jsonText);

        // TEXT query should work with viewRaw
        final Entry<String> textEntry = repo.file(Query.ofText("/test.json"))
                                            .viewRaw(true)
                                            .get()
                                            .join();
        assertThat(textEntry).isNotNull();
        assertThat(textEntry.rawContent()).isEqualTo(jsonText);
    }
}
