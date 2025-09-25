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

package com.linecorp.centraldogma.server.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;

class PushCommandTest {

    @Test
    void shouldNotContainRawContentInToString() {
        final Change<JsonNode> json = Change.ofJsonUpsert("/a.json", "{ \"foo\": \"bar\" }");
        final Change<String> text = Change.ofTextUpsert("/a.txt", "Hello");
        final Change<Void> removal = Change.ofRemoval("/b.txt");
        final List<Change<?>> changes = ImmutableList.of(json, text, removal);

        final Command<CommitResult> pushCommand =
                Command.push(Author.SYSTEM, "myProject", "myRepo",
                             Revision.HEAD,
                             "summary", "detail",
                             Markup.PLAINTEXT, changes);
        assertThat(pushCommand.toString()).contains(
                "changes=[{type: UPSERT_JSON, path: /a.json, contentLength: " + json.contentAsText().length() +
                "}, {type: UPSERT_TEXT, path: /a.txt, contentLength: " + text.contentAsText().length() +
                "}, {type: REMOVE, path: /b.txt, contentLength: 0}]");
    }
}
