/*
 * Copyright 2026 LY Corporation
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

import static com.linecorp.centraldogma.testing.internal.TestUtil.assertJsonConversion;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;

class RecoverRepositoryCommandTest {

    // The command crosses the replication log as JSON, so the wire format must stay stable.
    @Test
    void testJsonConversion() {
        assertJsonConversion(
                new RecoverRepositoryCommand(
                        1234L, Author.SYSTEM, "foo", "bar", 2, new Revision(2), new Revision(4),
                        ImmutableList.of(
                                new ReplayCommit(new Revision(3), 5678L,
                                                 new Author("Marge Simpson", "marge@simpsonsworld.com"),
                                                 "summary3", "detail3", Markup.PLAINTEXT,
                                                 ImmutableList.of(
                                                         Change.ofTextUpsert("/memo.txt", "Bon voyage!"),
                                                         Change.ofRemoval("/old.txt")),
                                                 "1111111111111111111111111111111111111111"),
                                new ReplayCommit(new Revision(4), 6789L, Author.SYSTEM,
                                                 "summary4", "", Markup.PLAINTEXT,
                                                 ImmutableList.of(Change.ofTextUpsert("/memo.txt", "v4")),
                                                 "0123456789012345678901234567890123456789"))),
                Command.class,
                '{' +
                "  \"type\": \"RECOVER_REPOSITORY\"," +
                "  \"timestamp\": 1234," +
                "  \"author\": {" +
                "    \"name\": \"system\"," +
                "    \"email\": \"system@localhost.localdomain\"" +
                "  }," +
                "  \"projectName\": \"foo\"," +
                "  \"repositoryName\": \"bar\"," +
                "  \"sourceServerId\": 2," +
                "  \"resetToRevision\": 2," +
                "  \"headRevision\": 4," +
                "  \"commits\": [{" +
                "    \"revision\": 3," +
                "    \"timestampMillis\": 5678," +
                "    \"author\": {" +
                "      \"name\": \"Marge Simpson\"," +
                "      \"email\": \"marge@simpsonsworld.com\"" +
                "    }," +
                "    \"summary\": \"summary3\"," +
                "    \"detail\": \"detail3\"," +
                "    \"markup\": \"PLAINTEXT\"," +
                "    \"changes\": [{" +
                "      \"type\": \"UPSERT_TEXT\"," +
                "      \"path\": \"/memo.txt\"," +
                "      \"content\": \"Bon voyage!\"" +
                "    }, {" +
                "      \"type\": \"REMOVE\"," +
                "      \"path\": \"/old.txt\"" +
                "    }]," +
                "    \"expectedCommitId\": \"1111111111111111111111111111111111111111\"" +
                "  }, {" +
                "    \"revision\": 4," +
                "    \"timestampMillis\": 6789," +
                "    \"author\": {" +
                "      \"name\": \"system\"," +
                "      \"email\": \"system@localhost.localdomain\"" +
                "    }," +
                "    \"summary\": \"summary4\"," +
                "    \"detail\": \"\"," +
                "    \"markup\": \"PLAINTEXT\"," +
                "    \"changes\": [{" +
                "      \"type\": \"UPSERT_TEXT\"," +
                "      \"path\": \"/memo.txt\"," +
                "      \"content\": \"v4\"" +
                "    }]," +
                "    \"expectedCommitId\": \"0123456789012345678901234567890123456789\"" +
                "  }]" +
                '}');
    }
}
