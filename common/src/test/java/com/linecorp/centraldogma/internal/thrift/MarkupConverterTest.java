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

import static com.linecorp.centraldogma.internal.thrift.MarkupConverter.TO_DATA;
import static com.linecorp.centraldogma.internal.thrift.MarkupConverter.TO_MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkupConverterTest {

    private static final com.linecorp.centraldogma.common.Markup COMMON =
            com.linecorp.centraldogma.common.Markup.PLAINTEXT;
    private static final Markup THRIFT = Markup.PLAINTEXT;

    @Test
    void test() {
        assertThat(TO_DATA.convert(COMMON)).isEqualTo(THRIFT);
        assertThat(TO_MODEL.convert(THRIFT)).isEqualTo(COMMON);
        assertThat(TO_DATA.convert(TO_MODEL.convert(THRIFT))).isEqualTo(THRIFT);

        assertThat(TO_DATA.convert(com.linecorp.centraldogma.common.Markup.MARKDOWN))
                .isEqualTo(Markup.MARKDOWN);
        assertThat(TO_DATA.convert(com.linecorp.centraldogma.common.Markup.PLAINTEXT))
                .isEqualTo(Markup.PLAINTEXT);
        assertThat(TO_DATA.convert(com.linecorp.centraldogma.common.Markup.UNKNOWN))
                .isEqualTo(Markup.UNKNOWN);

        assertThat(TO_MODEL.convert(Markup.MARKDOWN))
                .isEqualTo(com.linecorp.centraldogma.common.Markup.MARKDOWN);
        assertThat(TO_MODEL.convert(Markup.PLAINTEXT))
                .isEqualTo(com.linecorp.centraldogma.common.Markup.PLAINTEXT);
        assertThat(TO_MODEL.convert(Markup.UNKNOWN))
                .isEqualTo(com.linecorp.centraldogma.common.Markup.UNKNOWN);
    }
}
