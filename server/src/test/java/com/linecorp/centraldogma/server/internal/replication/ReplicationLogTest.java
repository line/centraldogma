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

package com.linecorp.centraldogma.server.internal.replication;

import static com.linecorp.centraldogma.testing.internal.TestUtil.assertJsonConversion;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.command.NormalizingPushCommand;
import com.linecorp.centraldogma.server.command.PushAsIsCommand;
import com.linecorp.centraldogma.testing.internal.FlakyTest;

@FlakyTest
class ReplicationLogTest {

    private static final Author AUTHOR = new Author("foo", "bar@baz.com");

    @Test
    void testJsonConversion() {
        assertJsonConversion(new ReplicationLog<>(1, Command.createProject(1234L, AUTHOR, "foo"), null),
                             '{' +
                             "  \"replicaId\": 1," +
                             "  \"command\": {" +
                             "    \"type\": \"CREATE_PROJECT\"," +
                             "    \"projectName\": \"foo\"," +
                             "    \"timestamp\": 1234," +
                             "    \"author\": {" +
                             "      \"name\": \"foo\"," +
                             "      \"email\": \"bar@baz.com\"" +
                             "    }" +
                             "  }," +
                             "  \"result\": null" +
                             '}');

        final ImmutableList<Change<?>> changes = ImmutableList.of(
                Change.ofTextUpsert("/result.txt", "too soon to tell"));
        final Command<CommitResult> push = Command.push(
                1234L, new Author("Sedol Lee", "sedol@lee.com"), "foo", "bar", new Revision(42),
                "4:1", "L-L-L-W-L", Markup.PLAINTEXT, changes);
        assert push instanceof NormalizingPushCommand;
        final PushAsIsCommand pushAsIs = ((NormalizingPushCommand) push).asIs(
                CommitResult.of(new Revision(43), changes));

        assertJsonConversion(new ReplicationLog<>(2, pushAsIs, new Revision(43)),
                             '{' +
                             "  \"replicaId\": 2," +
                             "  \"command\": {" +
                             "    \"type\": \"PUSH\"," +
                             "    \"projectName\": \"foo\"," +
                             "    \"repositoryName\": \"bar\"," +
                             "    \"baseRevision\": 42," +
                             "    \"timestamp\": 1234," +
                             "    \"author\": {" +
                             "      \"name\": \"Sedol Lee\"," +
                             "      \"email\": \"sedol@lee.com\"" +
                             "    }," +
                             "    \"summary\": \"4:1\"," +
                             "    \"detail\": \"L-L-L-W-L\"," +
                             "    \"markup\": \"PLAINTEXT\"," +
                             "    \"changes\": [{" +
                             "      \"type\": \"UPSERT_TEXT\"," +
                             "      \"path\": \"/result.txt\"," +
                             "      \"content\": \"too soon to tell\"" +
                             "    }]" +
                             "  }," +
                             "  \"result\": 43" +
                             '}');
    }
}
