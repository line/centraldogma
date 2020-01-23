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

package com.linecorp.centraldogma.internal.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthorConverterTest {

    private static final com.linecorp.centraldogma.common.Author COMMON =
            new com.linecorp.centraldogma.common.Author("user", "user@sample.com");
    private static final Author THRIFT = new Author("user", "user@sample.com");

    @Test
    void test() {
        assertThat(AuthorConverter.TO_DATA.convert(COMMON)).isEqualTo(THRIFT);
        assertThat(AuthorConverter.TO_MODEL.convert(THRIFT)).isEqualTo(COMMON);
        assertThat(AuthorConverter.TO_DATA.convert(AuthorConverter.TO_MODEL.convert(THRIFT))).isEqualTo(THRIFT);
    }
}
