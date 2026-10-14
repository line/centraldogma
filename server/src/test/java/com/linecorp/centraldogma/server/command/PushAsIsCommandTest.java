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

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;

class PushAsIsCommandTest {

    @Test
    void testJsonConversion() {
        assertJsonConversion(
                new PushAsIsCommand(1234L, new Author("Marge Simpson", "marge@simpsonsworld.com"),
                                    "foo", "bar", new Revision(42), "baz", "qux", Markup.MARKDOWN,
                                    ImmutableList.of(Change.ofTextUpsert("/memo.txt", "Bon voyage!"))),
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
}
