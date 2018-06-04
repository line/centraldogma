/*
 * Copyright 2017 LINE Corporation
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

import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;

public class PreviewDiffsTest extends AbstractMultiClientTest {

    @ClassRule
    public static final CentralDogmaRuleWithScaffolding rule = new CentralDogmaRuleWithScaffolding();

    public PreviewDiffsTest(ClientType clientType) {
        super(clientType);
    }

    @Test
    public void testInvalidPatch() throws Exception {
        // Apply a conflict change
        final Change<?> change = Change.ofJsonPatch("/test/new_json_file.json",
                                                    "{ \"a\": \"apple\" }", "{ \"a\": \"angle\" }");
        assertThatThrownByWithExpectedException(ChangeConflictException.class, "/test/new_json_file.json", () ->
                client().getPreviewDiffs(rule.project(), rule.repo1(), Revision.HEAD, change).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(ChangeConflictException.class);
    }

    @Test
    public void testInvalidRemoval() throws Exception {
        // Apply a conflict removal
        final Change<?> change = Change.ofRemoval("/non_existent_path.txt");
        assertThatThrownByWithExpectedException(ChangeConflictException.class, "non_existent_path.txt", () ->
                client().getPreviewDiffs(rule.project(), rule.repo1(), Revision.HEAD, change).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(ChangeConflictException.class);
    }

    @Test
    public void testInvalidRevision() throws Exception {
        final Change<String> change = Change.ofTextUpsert("/a_new_text_file.txt", "text");
        assertThatThrownByWithExpectedException(RevisionNotFoundException.class, "2147483647", () ->
                client().getPreviewDiffs(
                        rule.project(), rule.repo1(), new Revision(Integer.MAX_VALUE), change).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(RevisionNotFoundException.class);
    }

    @Test
    public void testEmptyChange() throws Exception {
        assertThat(client().getPreviewDiffs(rule.project(), rule.repo1(), Revision.HEAD).join()).isEmpty();
    }

    @Test
    public void testApplyUpsertOnExistingPath() throws Exception {
        final String jsonPath = "/a_new_json_file.json";
        try {
            client().push(rule.project(), rule.repo1(), Revision.HEAD,
                          "Add a new JSON file", Change.ofJsonUpsert(jsonPath, "{ \"a\": \"apple\" }")).join();
        } catch (CompletionException e) {
            // Might have been added already in previous run.
            assertThat(e.getCause()).isInstanceOf(RedundantChangeException.class);
        }

        final Change<JsonNode> change =
                Change.ofJsonPatch(jsonPath, "{ \"a\": \"apple\" }", "{ \"a\": \"angle\" }");

        final List<Change<?>> returnedList =
                client().getPreviewDiffs(rule.project(), rule.repo1(),
                                              Revision.HEAD, change).join();

        assertThat(returnedList).hasSize(1);
        assertThat(returnedList.get(0).type()).isEqualTo(ChangeType.APPLY_JSON_PATCH);
    }
}
