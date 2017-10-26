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

package com.linecorp.centraldogma.server.internal.command;

import static com.linecorp.centraldogma.testing.internal.TestUtil.assertJsonConversion;

import java.util.Collections;

import org.junit.Test;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;

public class PushCommandTest {

    @Test
    public void testJsonConversion() {
        assertJsonConversion(
                new PushCommand("foo", "bar", new Revision(42), 1234L,
                                new Author("Marge Simpson", "marge@simpsonsworld.com"),
                                "baz", "qux", Markup.MARKDOWN,
                                Collections.singletonList(Change.ofTextUpsert("/memo.txt", "Bon voyage!"))),
                Command.class,
                '{' +
                "  \"type\": \"PUSH\"," +
                "  \"projectName\": \"foo\"," +
                "  \"repositoryName\": \"bar\"," +
                "  \"baseRevision\": {" +
                "    \"major\": 42," +
                "    \"minor\": 0" +
                "  }," +
                "  \"commitTimeMillis\": 1234," +
                "  \"author\": {" +
                "    \"name\": \"Marge Simpson\"," +
                "    \"email\": \"marge@simpsonsworld.com\"" +
                "  }," +
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
