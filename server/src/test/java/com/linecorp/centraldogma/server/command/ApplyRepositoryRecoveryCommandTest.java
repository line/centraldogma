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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;

class ApplyRepositoryRecoveryCommandTest {

    @Test
    void rejectsTooManyCommitsAtIngestion() {
        final ReplayCommit commit =
                new ReplayCommit(new Revision(2), 1234L, Author.SYSTEM, "summary", "", Markup.PLAINTEXT,
                                 ImmutableList.of(Change.ofTextUpsert("/memo.txt", "v2")),
                                 "0123456789012345678901234567890123456789");

        assertThatThrownBy(() -> new ApplyRepositoryRecoveryCommand(
                1234L, Author.SYSTEM, "foo", "bar",
                Collections.nCopies(ApplyRepositoryRecoveryCommand.MAX_RECOVERY_COMMITS + 1, commit)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected: <= " + ApplyRepositoryRecoveryCommand.MAX_RECOVERY_COMMITS);
    }

    @Test
    void rejectsInvalidCommitRanges() {
        final ReplayCommit revision2 =
                new ReplayCommit(new Revision(2), 1234L, Author.SYSTEM, "summary", "", Markup.PLAINTEXT,
                                 ImmutableList.of(Change.ofTextUpsert("/memo.txt", "v2")),
                                 "0123456789012345678901234567890123456789");
        final ReplayCommit maxRevision =
                new ReplayCommit(new Revision(Integer.MAX_VALUE), 1234L, Author.SYSTEM, "summary", "",
                                 Markup.PLAINTEXT,
                                 ImmutableList.of(Change.ofTextUpsert("/memo.txt", "max")),
                                 "0123456789012345678901234567890123456789");

        assertThatThrownBy(() -> new ApplyRepositoryRecoveryCommand(
                1234L, Author.SYSTEM, "foo", "bar",
                ImmutableList.of(revision2,
                                 new ReplayCommit(
                                         new Revision(100), 1234L, Author.SYSTEM, "summary", "",
                                         Markup.PLAINTEXT,
                                         ImmutableList.of(Change.ofTextUpsert("/memo.txt", "v100")),
                                         "0123456789012345678901234567890123456789"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commits[1].revision");
        assertThatThrownBy(() -> new ApplyRepositoryRecoveryCommand(
                1234L, Author.SYSTEM, "foo", "bar",
                ImmutableList.of(new ReplayCommit(
                        Revision.INIT, 1234L, Author.SYSTEM, "summary", "", Markup.PLAINTEXT,
                        ImmutableList.of(Change.ofTextUpsert("/memo.txt", "v1")),
                        "0123456789012345678901234567890123456789"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commits[0].revision");
        assertThatThrownBy(() -> new ApplyRepositoryRecoveryCommand(
                1234L, Author.SYSTEM, "foo", "bar", ImmutableList.of(maxRevision, maxRevision)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum revision");
    }

    // The command crosses the replication log as JSON, so assert the exact payload.
    @Test
    void testJsonConversion() {
        assertJsonConversion(
                new ApplyRepositoryRecoveryCommand(
                        1234L, Author.SYSTEM, "foo", "bar",
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
                "  \"type\": \"APPLY_REPOSITORY_RECOVERY\"," +
                "  \"timestamp\": 1234," +
                "  \"author\": {" +
                "    \"name\": \"system\"," +
                "    \"email\": \"system@localhost.localdomain\"" +
                "  }," +
                "  \"projectName\": \"foo\"," +
                "  \"repositoryName\": \"bar\"," +
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
                "    \"expectedTreeId\": \"1111111111111111111111111111111111111111\"" +
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
                "    \"expectedTreeId\": \"0123456789012345678901234567890123456789\"" +
                "  }]" +
                '}');
    }
}
