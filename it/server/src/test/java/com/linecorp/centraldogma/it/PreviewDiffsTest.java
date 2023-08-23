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

package com.linecorp.centraldogma.it;

import static com.linecorp.centraldogma.testing.internal.ExpectedExceptionAppender.assertThatThrownByWithExpectedException;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;

class PreviewDiffsTest {

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding();

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void invalidPatch(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        // Apply a conflict change
        final Change<?> change = Change.ofJsonPatch("/test/new_json_file.json",
                                                    "{ \"a\": \"apple\" }", "{ \"a\": \"angle\" }");
        assertThatThrownByWithExpectedException(ChangeConflictException.class, "/test/new_json_file.json", () ->
                client.forRepo(dogma.project(), dogma.repo1()).diff(change).get().join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(ChangeConflictException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void invalidRemoval(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        // Apply a conflict removal
        final Change<?> change = Change.ofRemoval("/non_existent_path.txt");
        assertThatThrownByWithExpectedException(ChangeConflictException.class, "non_existent_path.txt", () ->
                client.forRepo(dogma.project(), dogma.repo1())
                      .diff(change)
                      .get()
                      .join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(ChangeConflictException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void invalidRevision(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        final Change<String> change = Change.ofTextUpsert("/a_new_text_file.txt", "text");
        assertThatThrownByWithExpectedException(RevisionNotFoundException.class, "2147483647", () ->
                client.forRepo(dogma.project(), dogma.repo1())
                      .diff(change)
                      .get(new Revision(Integer.MAX_VALUE))
                      .join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(RevisionNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void emptyChange(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        assertThat(client.forRepo(dogma.project(), dogma.repo1())
                         .diff()
                         .get()
                         .join()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void applyUpsertOnExistingPath(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final String jsonPath = "/a_new_json_file.json";

        try {
            client.forRepo(dogma.project(), dogma.repo1())
                  .commit("Add a new JSON file", Change.ofJsonUpsert(jsonPath, "{ \"a\": \"apple\" }"))
                  .push()
                  .join();
        } catch (CompletionException e) {
            // Might have been added already in previous run.
            assertThat(e.getCause()).isInstanceOf(RedundantChangeException.class);
        }

        final Change<JsonNode> change =
                Change.ofJsonPatch(jsonPath, "{ \"a\": \"apple\" }", "{ \"a\": \"angle\" }");

        final List<Change<?>> returnedList =
                client.forRepo(dogma.project(), dogma.repo1())
                      .diff(change)
                      .get()
                      .join();

        assertThat(returnedList).hasSize(1);
        assertThat(returnedList.get(0).type()).isEqualTo(ChangeType.APPLY_JSON_PATCH);
    }
}
