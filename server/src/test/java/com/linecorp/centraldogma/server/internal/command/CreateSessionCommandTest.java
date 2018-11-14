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
import com.linecorp.centraldogma.server.auth.Session;

public class CreateSessionCommandTest {

    @Test
    public void testJsonConversion() throws Exception {
        final Session session =
                new Session("session-id-12345",
                            "foo",
                            Instant.EPOCH,
                            Instant.EPOCH.plus(1, ChronoUnit.MINUTES),
                            "serializable_raw_session_object");

        final String encodedSession =
                "rO0ABXNyAC1jb20ubGluZWNvcnAuY2VudHJhbGRvZ21hLnNlcnZlci5hdXRoLlNlc3Npb247BjwUfq8E" +
                "gQIABUwADGNyZWF0aW9uVGltZXQAE0xqYXZhL3RpbWUvSW5zdGFudDtMAA5leHBpcmF0aW9uVGltZXEA" +
                "fgABTAACaWR0ABJMamF2YS9sYW5nL1N0cmluZztMAApyYXdTZXNzaW9udAAWTGphdmEvaW8vU2VyaWFs" +
                "aXphYmxlO0wACHVzZXJuYW1lcQB+AAJ4cHNyAA1qYXZhLnRpbWUuU2VylV2EuhsiSLIMAAB4cHcNAgAA" +
                "AAAAAAAAAAAAAHhzcQB+AAV3DQIAAAAAAAAAPAAAAAB4dAAQc2Vzc2lvbi1pZC0xMjM0NXQAH3Nlcmlh" +
                "bGl6YWJsZV9yYXdfc2Vzc2lvbl9vYmplY3R0AANmb28=";

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
