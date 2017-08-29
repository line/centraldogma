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

import org.assertj.core.api.Assertions;
import org.junit.Test;

public class ChangeConverterTest {
    private static final String TIMESTAMP = "2016-01-02T03:04:05Z";

    private static final com.linecorp.centraldogma.common.Change<String> COMMON =
            com.linecorp.centraldogma.common.Change.ofTextUpsert("/a.txt", "hello");

    private static final Change THRIFT = new Change().setPath("/a.txt")
                                                     .setType(ChangeType.UPSERT_TEXT)
                                                     .setContent("hello");

    @Test
    public void test() throws Exception {
        Assertions.assertThat(ChangeConverter.TO_DATA.convert(COMMON)).isEqualTo(THRIFT);
        Assertions.assertThat(ChangeConverter.TO_MODEL.convert(THRIFT)).isEqualTo(COMMON);
        Assertions.assertThat(ChangeConverter.TO_DATA.convert(ChangeConverter.TO_MODEL.convert(THRIFT))).isEqualTo(THRIFT);
    }
}
