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

package com.linecorp.centraldogma.common;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.testing.internal.TestUtil;

class AuthorTest {

    @Test
    void testAuthor_invalidParameters() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Author(null, "email@test.com"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Author("", "invalid mail address"));
    }

    @Test
    void testJsonConversion() {
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
