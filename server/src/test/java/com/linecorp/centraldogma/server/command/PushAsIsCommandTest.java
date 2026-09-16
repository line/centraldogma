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

package com.linecorp.centraldogma.server.command;

import static com.linecorp.centraldogma.testing.internal.TestUtil.assertJsonConversion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;

class PushAsIsCommandTest {

    @Test
    void testJsonConversion() {
        assertJsonConversion(
                new PushAsIsCommand(1234L, new Author("Marge Simpson", "marge@simpsonsworld.com"),
                                    "foo", "bar", new Revision(42), "baz", "qux", Markup.MARKDOWN,
                                    ImmutableList.of(Change.ofTextUpsert("/memo.txt", "Bon voyage!")),
                                    null),
                Command.class,
                '{' +
                "  \"type\": \"PUSH\"," +
                "  \"timestamp\": 1234," +
                "  \"author\": {" +
                "    \"name\": \"Marge Simpson\"," +
                "    \"email\": \"marge@simpsonsworld.com\"" +
                "  }," +
                "  \"projectName\": \"foo\"," +
                "  \"repositoryName\": \"bar\"," +
                "  \"baseRevision\": 42," +
                "  \"summary\": \"baz\"," +
                "  \"detail\": \"qux\"," +
                "  \"markup\": \"MARKDOWN\"," +
                "  \"changes\": [{" +
                "    \"type\": \"UPSERT_TEXT\"," +
                "    \"path\": \"/memo.txt\"," +
                "    \"content\": \"Bon voyage!\"" +
                "  }]" +
                '}');
    }

    @Test
    void upstreamCommitIdJsonRoundTrip() throws Exception {
        final String upstreamCommitId = "0123456789abcdef0123456789abcdef01234567";
        final PushAsIsCommand command = newCommand(upstreamCommitId);

        final String json = Jackson.writeValueAsString(command);
        assertThat(json).contains("\"upstreamCommitId\":\"" + upstreamCommitId + '\"');
        assertThat(Jackson.readValue(json, Command.class)).isEqualTo(command);
    }

    @Test
    void rejectsInvalidUpstreamCommitId() {
        assertThatThrownBy(() -> newCommand("not-a-commit-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upstreamCommitId");
    }

    private static PushAsIsCommand newCommand(String upstreamCommitId) {
        return new PushAsIsCommand(1234L, new Author("Marge Simpson", "marge@simpsonsworld.com"),
                                   "foo", "bar", new Revision(42), "baz", "qux", Markup.MARKDOWN,
                                   ImmutableList.of(Change.ofTextUpsert("/memo.txt", "Bon voyage!")),
                                   upstreamCommitId);
    }
}
