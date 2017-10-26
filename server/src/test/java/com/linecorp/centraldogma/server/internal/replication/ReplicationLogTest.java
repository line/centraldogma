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

package com.linecorp.centraldogma.server.internal.replication;

import static com.linecorp.centraldogma.testing.internal.TestUtil.assertJsonConversion;

import org.junit.Test;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.command.Command;

public class ReplicationLogTest {
    @Test
    public void testJsonConversion() {
        assertJsonConversion(new ReplicationLog<>("r1", Command.createProject("foo", 1234), null),
                             '{' +
                             "  \"replicaId\": \"r1\"," +
                             "  \"command\": {" +
                             "    \"type\": \"CREATE_PROJECT\"," +
                             "    \"projectName\": \"foo\"," +
                             "    \"creationTimeMillis\": 1234" +
                             "  }," +
                             "  \"result\": null" +
                             '}');

        Command<Revision> pushCommand = Command.push(
                "foo", "bar", Revision.HEAD, 1234, new Author("Sedol Lee", "sedol@lee.com"),
                "4:1", "L-L-L-W-L", Markup.PLAINTEXT, Change.ofTextUpsert("/result.txt", "too soon to tell"));

        assertJsonConversion(new ReplicationLog<>("r2", pushCommand, new Revision(43)),
                             '{' +
                             "  \"replicaId\": \"r2\"," +
                             "  \"command\": {" +
                             "    \"type\": \"PUSH\"," +
                             "    \"projectName\": \"foo\"," +
                             "    \"repositoryName\": \"bar\"," +
                             "    \"baseRevision\": {" +
                             "      \"major\": -1," +
                             "      \"minor\": 0" +
                             "    }," +
                             "    \"commitTimeMillis\": 1234," +
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
                             "  \"result\": {" +
                             "    \"major\": 43," +
                             "    \"minor\": 0" +
                             "  }" +
                             '}');
    }
}
