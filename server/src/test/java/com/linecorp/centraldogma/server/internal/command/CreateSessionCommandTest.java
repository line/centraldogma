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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.Test;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.auth.AuthenticatedSession;

public class CreateSessionCommandTest {

    @Test
    public void testJsonConversion() throws Exception {
        final AuthenticatedSession session =
                new AuthenticatedSession("session-id-12345",
                                         "foo",
                                         Instant.EPOCH,
                                         Instant.EPOCH.plus(1, ChronoUnit.MINUTES),
                                         "serializable_raw_session_object");

        final String encodedSession =
                "rO0ABXNyADpjb20ubGluZWNvcnAuY2VudHJhbGRvZ21hLnNlcnZlci5hdXRoLkF1dGhlbnRpY2F0ZWRT" +
                "ZXNzaW9uOwY8FH6vBIECAAVMAAxjcmVhdGlvblRpbWV0ABNMamF2YS90aW1lL0luc3RhbnQ7TAAOZXhw" +
                "aXJhdGlvblRpbWVxAH4AAUwAAmlkdAASTGphdmEvbGFuZy9TdHJpbmc7TAAKcmF3U2Vzc2lvbnQAFkxq" +
                "YXZhL2lvL1NlcmlhbGl6YWJsZTtMAAh1c2VybmFtZXEAfgACeHBzcgANamF2YS50aW1lLlNlcpVdhLob" +
                "IkiyDAAAeHB3DQIAAAAAAAAAAAAAAAB4c3EAfgAFdw0CAAAAAAAAADwAAAAAeHQAEHNlc3Npb24taWQt" +
                "MTIzNDV0AB9zZXJpYWxpemFibGVfcmF3X3Nlc3Npb25fb2JqZWN0dAADZm9v";

        assertJsonConversion(
                new CreateSessionCommand(1234L, new Author("foo", "bar@baz.com"), session),
                Command.class,
                '{' +
                "  \"type\": \"CREATE_SESSIONS\"," +
                "  \"timestamp\": 1234," +
                "  \"author\": {" +
                "    \"name\": \"foo\"," +
                "    \"email\": \"bar@baz.com\"" +
                "  }," +
                "  \"session\": \"" + encodedSession + '"' +
                '}');
    }
}
