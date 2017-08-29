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

package com.linecorp.centraldogma.common;

import static org.junit.Assert.fail;

import org.junit.Test;

import com.linecorp.centraldogma.testing.internal.TestUtil;

public class AuthorTest {

    @Test
    public void testAuthor_invalidParameters() {
        try {
            new Author(null, "email@test.com");
            fail("Expected NullPointerException");
        } catch (NullPointerException ignored) {
        }
        try {
            new Author("", "invalid mail address");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Test
    public void testJsonConversion() {
        TestUtil.assertJsonConversion(new Author("Homer Simpson", "homer@simpsonsworld.com"),
                             '{' +
                             "  \"name\": \"Homer Simpson\"," +
                             "  \"email\": \"homer@simpsonsworld.com\"" +
                             '}');

        TestUtil.assertJsonConversion(new Author("bart@simpsonsworld.com"),
                             '{' +
                             "  \"name\": \"bart@simpsonsworld.com\"," +
                             "  \"email\": \"bart@simpsonsworld.com\"" +
                             '}');
    }
}
