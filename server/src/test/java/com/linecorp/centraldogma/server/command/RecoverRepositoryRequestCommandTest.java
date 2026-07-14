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

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;

class RecoverRepositoryRequestCommandTest {

    // The command crosses the replication log as JSON, so the wire format must stay stable.
    @Test
    void testJsonConversion() {
        assertJsonConversion(
                new RecoverRepositoryRequestCommand(1234L, Author.SYSTEM, "foo", "bar", 2, new Revision(3)),
                Command.class,
                '{' +
                "  \"type\": \"RECOVER_REPOSITORY_REQUEST\"," +
                "  \"timestamp\": 1234," +
                "  \"author\": {" +
                "    \"name\": \"system\"," +
                "    \"email\": \"system@localhost.localdomain\"" +
                "  }," +
                "  \"projectName\": \"foo\"," +
                "  \"repositoryName\": \"bar\"," +
                "  \"sourceServerId\": 2," +
                "  \"fromRevision\": 3" +
                '}');
    }
}
