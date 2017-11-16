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
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.internal.Jackson;

public class SaveNamedQueryCommandTest {
    @Test
    public void testJsonConversion() {
        assertJsonConversion(new SaveNamedQueryCommand(1234L, Author.SYSTEM, "foo", "bar", true, "qux",
                                                       Query.identity("/first.txt"),
                                                       "plaintext comment", Markup.PLAINTEXT),
                             Command.class,
                             '{' +
                             "  \"type\": \"SAVE_NAMED_QUERY\"," +
                             "  \"timestamp\": 1234," +
                             "  \"author\": {" +
                             "    \"name\": \"System\"," +
                             "    \"email\": \"system@localhost.localdomain\"" +
                             "  }," +
                             "  \"projectName\": \"foo\"," +
                             "  \"queryName\": \"bar\"," +
                             "  \"enabled\": true," +
                             "  \"repositoryName\": \"qux\"," +
                             "  \"query\": {" +
                             "    \"type\": \"IDENTITY\"," +
                             "    \"path\": \"/first.txt\"" +
                             "  }," +
                             "  \"comment\": \"plaintext comment\"," +
                             "  \"markup\": \"PLAINTEXT\"" +
                             '}');

        assertJsonConversion(new SaveNamedQueryCommand(1234L, Author.SYSTEM, "qux", "foo", false, "bar",
                                                       Query.ofJsonPath("/second.json", "$..author", "$..name"),
                                                       "markdown comment", Markup.MARKDOWN),
                             Command.class,
                             '{' +
                             "  \"type\": \"SAVE_NAMED_QUERY\"," +
                             "  \"timestamp\": 1234," +
                             "  \"author\": {" +
                             "    \"name\": \"System\"," +
                             "    \"email\": \"system@localhost.localdomain\"" +
                             "  }," +
                             "  \"projectName\": \"qux\"," +
                             "  \"queryName\": \"foo\"," +
                             "  \"enabled\": false," +
                             "  \"repositoryName\": \"bar\"," +
                             "  \"query\": {" +
                             "    \"type\": \"JSON_PATH\"," +
                             "    \"path\": \"/second.json\"," +
                             "    \"expressions\": [ \"$..author\", \"$..name\" ]" +
                             "  }," +
                             "  \"comment\": \"markdown comment\"," +
                             "  \"markup\": \"MARKDOWN\"" +
                             '}');
    }

    @Test
    public void backwardCompatibility() throws Exception {
        final SaveNamedQueryCommand c = (SaveNamedQueryCommand) Jackson.readValue(
                '{' +
                "  \"type\": \"SAVE_NAMED_QUERY\"," +
                "  \"projectName\": \"foo\"," +
                "  \"queryName\": \"bar\"," +
                "  \"enabled\": true," +
                "  \"repositoryName\": \"qux\"," +
                "  \"query\": {" +
                "    \"type\": \"IDENTITY\"," +
                "    \"path\": \"/first.txt\"" +
                "  }," +
                "  \"comment\": \"plaintext comment\"," +
                "  \"markup\": \"PLAINTEXT\"" +
                '}', Command.class);

        assertThat(c.author()).isEqualTo(Author.SYSTEM);
        assertThat(c.timestamp()).isNotZero();
        assertThat(c.projectName()).isEqualTo("foo");
        assertThat(c.queryName()).isEqualTo("bar");
        assertThat(c.isEnabled()).isTrue();
        assertThat(c.repositoryName()).isEqualTo("qux");
        assertThat(c.query()).isEqualTo(Query.identity("/first.txt"));
        assertThat(c.comment()).isEqualTo("plaintext comment");
        assertThat(c.markup()).isSameAs(Markup.PLAINTEXT);
    }
}
