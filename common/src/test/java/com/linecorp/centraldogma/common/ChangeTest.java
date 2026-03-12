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

package com.linecorp.centraldogma.common;

import static com.linecorp.centraldogma.testing.internal.TestUtil.assertJsonConversion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;

import difflib.DiffUtils;
import difflib.Patch;
import difflib.PatchFailedException;

class ChangeTest {

    @Test
    void testTextPatches() throws PatchFailedException {
        final String oriStr = "1\n2\n3\n4\n5\n6\n7\n8\n9";
        final String newStr = "1a\n2\n3\n4\n5\n6\n7\n8\n9a";
        final String expectedUnifiedDiff = "--- /text_file.txt\n" +
                                           "+++ /text_file.txt\n" +
                                           "@@ -1,4 +1,4 @@\n" +
                                           "-1\n" +
                                           "+1a\n" +
                                           " 2\n" +
                                           " 3\n" +
                                           " 4\n" +
                                           "@@ -6,4 +6,4 @@\n" +
                                           " 6\n" +
                                           " 7\n" +
                                           " 8\n" +
                                           "-9\n" +
                                           "+9a";
        final Change<String> change = Change.ofTextPatch("/text_file.txt", oriStr, newStr);
        assertThat(change.content()).isEqualTo(expectedUnifiedDiff);
        final Patch<String> patch = DiffUtils.parseUnifiedDiff(Util.stringToLines(change.content()));
        final String patchedStr = String.join("\n", patch.applyTo(Util.stringToLines(oriStr)));
        assertThat(patchedStr).isEqualTo(newStr);
    }

    @Test
    void testJsonConversion() throws JsonProcessingException {
        assertJsonConversion(Change.ofJsonUpsert("/1.json", "{ \"a\": 42 }"), Change.class,
                             '{' +
                             "  \"type\": \"UPSERT_JSON\"," +
                             "  \"path\": \"/1.json\"," +
                             "  \"rawContent\": \"{ \\\"a\\\": 42 }\"" +
                             '}');

        assertJsonConversion(Change.ofJsonUpsert("/1.json", Jackson.readTree("{ \"a\": 42 }")), Change.class,
                             '{' +
                             "  \"type\": \"UPSERT_JSON\"," +
                             "  \"path\": \"/1.json\"," +
                             "  \"content\": {" +
                             "    \"a\": 42" +
                             "  }" +
                             '}');

        assertJsonConversion(Change.ofTextUpsert("/2", "foo"), Change.class,
                             '{' +
                             "  \"type\": \"UPSERT_TEXT\"," +
                             "  \"path\": \"/2\"," +
                             "  \"content\": \"foo\"" +
                             '}');

        assertJsonConversion(Change.ofJsonPatch("/3.json", "{ \"foo\": \"bar\" }",
                                                "{ \"foo\": \"baz\" }"), Change.class,
                             '{' +
                             "  \"type\": \"APPLY_JSON_PATCH\"," +
                             "  \"path\": \"/3.json\"," +
                             "  \"content\": [{" +
                             "    \"op\" : \"safeReplace\"," +
                             "    \"path\": \"/foo\"," +
                             "    \"oldValue\": \"bar\"," +
                             "    \"value\": \"baz\"" +
                             "  }]" +
                             '}');

        assertJsonConversion(Change.ofTextPatch("/4", "foo", "bar"), Change.class,
                             '{' +
                             "  \"type\": \"APPLY_TEXT_PATCH\"," +
                             "  \"path\": \"/4\"," +
                             "  \"content\": \"--- /4\\n" +
                             "+++ /4\\n" +
                             "@@ -1,1 +1,1 @@\\n" +
                             "-foo\\n" +
                             "+bar\"" +
                             '}');

        assertJsonConversion(Change.ofRemoval("/5"), Change.class,
                             '{' +
                             "  \"type\": \"REMOVE\"," +
                             "  \"path\": \"/5\"" +
                             '}');

        assertJsonConversion(Change.ofRename("/6", "/7"), Change.class,
                             '{' +
                             "  \"type\": \"RENAME\"," +
                             "  \"path\": \"/6\"," +
                             "  \"content\": \"/7\"" +
                             '}');
    }

    @Test
    void shouldNotAllowJsonFileWith_OfText() {

        final List<ThrowingCallable> changes =
                ImmutableList.of(() -> Change.ofTextUpsert("/foo.json", "abc"),
                                 () -> Change.ofTextPatch("/foo.json", "abc"),
                                 () -> Change.ofTextPatch("/foo.json", "foo", "bar"));

        for (final ThrowingCallable change : changes) {
            assertThatThrownBy(change)
                    .isInstanceOf(ChangeFormatException.class)
                    .hasMessageContaining("invalid file type: /foo.json (expected: a non-JSON file)");
        }
    }
}
