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

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.testing.internal.TestUtil;

class QueryTest {

    @Test
    void testJsonConversion() {
        TestUtil.assertJsonConversion(Query.ofText("/foo.txt"), Query.class,
                             '{' +
                             "  \"type\": \"IDENTITY\"," +
                             "  \"path\": \"/foo.txt\"" +
                             '}');

        TestUtil.assertJsonConversion(Query.ofJsonPath("/bar.json", "$..author", "$..name"), Query.class,
                             '{' +
                             "  \"type\": \"JSON_PATH\"," +
                             "  \"path\": \"/bar.json\"," +
                             "  \"expressions\": [ \"$..author\", \"$..name\" ]" +
                             '}');
    }
}
