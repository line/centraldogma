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

package com.linecorp.centraldogma.internal.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class EntryConverterTest {

    private static final com.linecorp.centraldogma.common.Revision REV =
            new com.linecorp.centraldogma.common.Revision(42);
    private static final com.linecorp.centraldogma.common.Entry<String> COMMON =
            com.linecorp.centraldogma.common.Entry.ofText(REV, "/a.txt", "hello");
    private static final Entry THRIFT = new Entry("/a.txt", EntryType.TEXT).setContent("hello");

    @Test
    public void test() throws Exception {
        assertThat(EntryConverter.convert(COMMON)).isEqualTo(THRIFT);
        assertThat(EntryConverter.convert(REV, THRIFT)).isEqualTo(COMMON);
        assertThat(EntryConverter.convert(EntryConverter.convert(REV, THRIFT))).isEqualTo(THRIFT);
    }
}
