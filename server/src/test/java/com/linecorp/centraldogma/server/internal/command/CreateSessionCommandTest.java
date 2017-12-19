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

import java.util.Date;

import org.apache.shiro.session.mgt.SimpleSession;
import org.junit.Test;

import com.linecorp.centraldogma.common.Author;

public class CreateSessionCommandTest {

    private static final SimpleSession EMPTY_SESSION = new SimpleSession();

    static {
        final Date epoch = new Date(0);
        EMPTY_SESSION.setStartTimestamp(epoch);
        EMPTY_SESSION.setLastAccessTime(epoch);
    }

    private static final String ENCODED_EMPTY_SESSION =
            "rO0ABXNyACpvcmcuYXBhY2hlLnNoaXJvLnNlc3Npb24ubWd0LlNpbXBsZVNlc3Npb26dHKG41YxibgMAAHhw" +
            "dwIAGnNyAA5qYXZhLnV0aWwuRGF0ZWhqgQFLWXQZAwAAeHB3CAAAAAAAAAAAeHEAfgADdwgAAAAAABt3QHg=";

    @Test
    public void testJsonConversion() {
        assertJsonConversion(
                new CreateSessionCommand(1234L, new Author("foo", "bar@baz.com"), EMPTY_SESSION),
                Command.class,
                '{' +
                "  \"type\": \"CREATE_SESSIONS\"," +
                "  \"timestamp\": 1234," +
                "  \"author\": {" +
                "    \"name\": \"foo\"," +
                "    \"email\": \"bar@baz.com\"" +
                "  }," +
                "  \"session\": \"" + ENCODED_EMPTY_SESSION + '"' +
                '}');
    }
}
