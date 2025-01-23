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

package com.linecorp.centraldogma.common.jsonpatch;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class JsonPatchOperationIntegrationTest {

    @RegisterExtension
    final CentralDogmaExtension dogma = new CentralDogmaExtension() {
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

    CentralDogmaRepository repository;

    @BeforeEach
    void setUp() {
        repository = dogma.client().forRepo("foo", "bar");
    }

    @Test
    void add() throws JsonParseException {
        repository.commit("add a", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }"))
                  .push()
                  .join();
        final AddOperation add = JsonPatchOperation.add(JsonPointer.compile("/b"), new IntNode(2));
        final Change<JsonNode> change = Change.ofJsonPatch("/a.json", add);
        repository.commit("add b", change)
                  .push()
                  .join();

        final JsonNode jsonNode = repository.file("/a.json").get().join().contentAsJson();
        assertThatJson(jsonNode).isEqualTo("{ \"a\": 1, \"b\": 2 }");
    }

    @Test
    void copy() throws JsonParseException {
        repository.commit("add a", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }"))
                  .push()
                  .join();
        final CopyOperation copy = JsonPatchOperation.copy(JsonPointer.compile("/a"),
                                                           JsonPointer.compile("/b"));
        final Change<JsonNode> change = Change.ofJsonPatch("/a.json", copy);
        repository.commit("copy a", change)
                  .push()
                  .join();

        final JsonNode jsonNode = repository.file("/a.json").get().join().contentAsJson();
        assertThatJson(jsonNode).isEqualTo("{ \"a\": 1, \"b\": 1 }");
    }

    @Test
    void testMove() throws JsonParseException {
        repository.commit("add a", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }"))
                  .push()
                  .join();
        final MoveOperation move = JsonPatchOperation.move(JsonPointer.compile("/a"),
                                                           JsonPointer.compile("/b"));
        final Change<JsonNode> change = Change.ofJsonPatch("/a.json", move);
        repository.commit("move a", change)
                  .push()
                  .join();

        final JsonNode jsonNode = repository.file("/a.json").get().join().contentAsJson();
        assertThatJson(jsonNode).isEqualTo("{ \"b\": 1 }");
    }

    @Test
    void remove() throws JsonParseException {
        repository.commit("add ab", Change.ofJsonUpsert("/a.json", "{ \"a\": 1, \"b\": 2 }"))
                  .push()
                  .join();
        final RemoveOperation remove = JsonPatchOperation.remove(JsonPointer.compile("/a"));
        final Change<JsonNode> change = Change.ofJsonPatch("/a.json", remove);
        repository.commit("remove a", change)
                  .push()
                  .join();

        final JsonNode jsonNode = repository.file("/a.json").get().join().contentAsJson();
        assertThatJson(jsonNode).isEqualTo("{ \"b\": 2 }");

        assertThatThrownBy(() -> repository.commit("remove a again", change)
                                           .push()
                                           .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(JsonPatchConflictException.class)
                .hasMessageContaining("non-existent path: /a");
    }

    @Test
    void removeIfExists() throws JsonParseException {
        repository.commit("add ab", Change.ofJsonUpsert("/a.json", "{ \"a\": 1, \"b\": 2 }"))
                  .push()
                  .join();
        final RemoveIfExistsOperation removeIfExists =
                JsonPatchOperation.removeIfExists(JsonPointer.compile("/a"));
        final Change<JsonNode> change = Change.ofJsonPatch("/a.json", removeIfExists);
        final PushResult result0 = repository.commit("remove a", change)
                                            .push()
                                            .join();

        JsonNode jsonNode = repository.file("/a.json").get().join().contentAsJson();
        assertThatJson(jsonNode).isEqualTo("{ \"b\": 2 }");

        final PushResult result1 = repository.commit("remove a again", change)
                                             .push()
                                             .join();
        // Should not increase the revision if the path is absent and the history must be the same.
        assertThat(result1.revision()).isEqualTo(result0.revision());

        final Entry<?> data = repository.file("/a.json").get().join();
        assertThat(data.revision()).isEqualTo(result0.revision());
        assertThatJson(data.contentAsJson()).isEqualTo("{ \"b\": 2 }");
    }

    @Test
    void replace() throws JsonParseException {
        repository.commit("add a", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }"))
                  .push()
                  .join();
        final ReplaceOperation replace = JsonPatchOperation.replace(JsonPointer.compile("/a"), new IntNode(2));
        final Change<JsonNode> change = Change.ofJsonPatch("/a.json", replace);
        repository.commit("replace a", change)
                  .push()
                  .join();

        final JsonNode jsonNode = repository.file("/a.json").get().join().contentAsJson();
        assertThatJson(jsonNode).isEqualTo("{ \"a\": 2 }");
    }

    @Test
    void safeReplace() throws JsonParseException {
        repository.commit("add a", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }"))
                  .push()
                  .join();
        SafeReplaceOperation safeReplace =
                JsonPatchOperation.safeReplace(JsonPointer.compile("/a"), new IntNode(1), new IntNode(2));
        final Change<JsonNode> change = Change.ofJsonPatch("/a.json", safeReplace);
        repository.commit("safe replace a", change)
                  .push()
                  .join();

        final JsonNode jsonNode = repository.file("/a.json").get().join().contentAsJson();
        assertThatJson(jsonNode).isEqualTo("{ \"a\": 2 }");

        safeReplace = JsonPatchOperation.safeReplace(JsonPointer.compile("/a"), new IntNode(3), new IntNode(4));
        final Change<JsonNode> change1 = Change.ofJsonPatch("/a.json", safeReplace);
        assertThatThrownBy(() -> {
            repository.commit("invalid safe replace a", change1)
                      .push()
                      .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(JsonPatchConflictException.class)
          .hasMessageContaining("mismatching value at '/a': 2 (expected: 3)");
    }

    @Test
    void test() {
        repository.commit("add a", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }"))
                  .push()
                  .join();
        TestOperation test = JsonPatchOperation.test(JsonPointer.compile("/a"), new IntNode(1));
        final Change<JsonNode> change = Change.ofJsonPatch("/a.json", test);
        repository.commit("test a", change)
                  .push()
                  .join();

        test = JsonPatchOperation.test(JsonPointer.compile("/a"), new IntNode(2));
        final Change<JsonNode> change1 = Change.ofJsonPatch("/a.json", test);
        assertThatThrownBy(() -> {
            repository.commit("invalid test a", change1)
                      .push()
                      .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(JsonPatchConflictException.class)
          .hasMessageContaining("mismatching value at '/a': 1 (expected: 2)");
    }

    @Test
    void testAbsence() {
        repository.commit("add a", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }"))
                  .push()
                  .join();
        TestAbsenceOperation testAbsence = JsonPatchOperation.testAbsence(JsonPointer.compile("/b"));
        final Change<JsonNode> change = Change.ofJsonPatch("/a.json", testAbsence);
        repository.commit("test absence", change)
                  .push()
                  .join();

        testAbsence = JsonPatchOperation.testAbsence(JsonPointer.compile("/a"));
        final Change<JsonNode> change1 = Change.ofJsonPatch("/a.json", testAbsence);
        assertThatThrownBy(() -> {
            repository.commit("invalid test absence", change1)
                      .push()
                      .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(JsonPatchConflictException.class)
          .hasMessageContaining("existent path: /a");
    }

    @Test
    void testMultipleOperations() throws JsonParseException {
        repository.commit("add a", Change.ofJsonUpsert("/a.json", "{ \"a\": 1, \"b\": 2 }"))
                  .push()
                  .join();
        // { "a": 1, "b": 2 } -> { "a": 1, "b": 2, "c": 3 }
        final AddOperation add = JsonPatchOperation.add(JsonPointer.compile("/c"), new IntNode(3));
        // { "a": 1, "b": 2, "c": 3 } -> { "b": 2, "c": 3, "d": 1 }
        final MoveOperation move = JsonPatchOperation.move(JsonPointer.compile("/a"),
                                                           JsonPointer.compile("/d"));
        // { "b": 2, "c": 3, "d": 1 } -> { "c": 3, "d": 1 }
        final RemoveOperation remove = JsonPatchOperation.remove(JsonPointer.compile("/b"));
        // { "c": 3, "d": 1 } -> { "c": 4, "d": 1 }
        final SafeReplaceOperation safeReplace = JsonPatchOperation.safeReplace(JsonPointer.compile("/c"),
                                                                                new IntNode(3), new IntNode(4));

        final Change<JsonNode> change = Change.ofJsonPatch("/a.json",
                                                           ImmutableList.of(add, move, remove, safeReplace));

        repository.commit("json patch operations", change)
                  .push()
                  .join();

        assertThatJson(repository.file("/a.json").get().join().contentAsJson())
                .isEqualTo("{ \"c\": 4, \"d\": 1 }");
    }

    @Test
    void testMultipleOperationsWithStringPath() throws JsonParseException {
        repository.commit("add a", Change.ofJsonUpsert("/a.json", "{ \"a\": 1, \"b\": 2 }"))
                  .push()
                  .join();
        // { "a": 1, "b": 2 } -> { "a": 1, "b": 2, "c": 3 }
        final AddOperation add = JsonPatchOperation.add("/c", new IntNode(3));
        // { "a": 1, "b": 2, "c": 3 } -> { "b": 2, "c": 3, "d": 1 }
        final MoveOperation move = JsonPatchOperation.move("/a", "/d");
        // { "b": 2, "c": 3, "d": 1 } -> { "c": 3, "d": 1 }
        final RemoveOperation remove = JsonPatchOperation.remove("/b");
        // { "c": 3, "d": 1 } -> { "c": 4, "d": 1 }
        final SafeReplaceOperation safeReplace =
                JsonPatchOperation.safeReplace("/c", new IntNode(3), new IntNode(4));

        final Change<JsonNode> change = Change.ofJsonPatch("/a.json",
                                                           ImmutableList.of(add, move, remove, safeReplace));

        repository.commit("json patch operations", change)
                  .push()
                  .join();

        assertThatJson(repository.file("/a.json").get().join().contentAsJson())
                .isEqualTo("{ \"c\": 4, \"d\": 1 }");
    }

    @Test
    void testEquality() throws JsonProcessingException {
        ensureSerdesEquality(JsonPatchOperation.add(JsonPointer.compile("/a"), new IntNode(1)),
                             AddOperation.class);
        ensureSerdesEquality(JsonPatchOperation.copy(JsonPointer.compile("/a"), JsonPointer.compile("/b")),
                             CopyOperation.class);
        ensureSerdesEquality(JsonPatchOperation.move(JsonPointer.compile("/a"), JsonPointer.compile("/b")),
                             MoveOperation.class);
        ensureSerdesEquality(JsonPatchOperation.remove(JsonPointer.compile("/a")), RemoveOperation.class);
        ensureSerdesEquality(JsonPatchOperation.removeIfExists(JsonPointer.compile("/a")),
                             RemoveIfExistsOperation.class);
        ensureSerdesEquality(JsonPatchOperation.replace(JsonPointer.compile("/a"), new IntNode(1)),
                             ReplaceOperation.class);
        ensureSerdesEquality(
                JsonPatchOperation.safeReplace(JsonPointer.compile("/a"), new IntNode(1), new IntNode(2)),
                SafeReplaceOperation.class);
        ensureSerdesEquality(JsonPatchOperation.test(JsonPointer.compile("/a"), new IntNode(1)),
                             TestOperation.class);
        ensureSerdesEquality(JsonPatchOperation.testAbsence(JsonPointer.compile("/a")),
                             TestAbsenceOperation.class);
    }

    private static <T extends JsonPatchOperation> void ensureSerdesEquality(T operation, Class<T> clazz)
            throws JsonProcessingException {
        final String json = Jackson.writeValueAsString(operation);
        final JsonNode jsonNode = Jackson.readTree(json);
        final T deserialized = Jackson.convertValue(jsonNode, clazz);
        assertThat(deserialized).isEqualTo(operation);
    }
}
