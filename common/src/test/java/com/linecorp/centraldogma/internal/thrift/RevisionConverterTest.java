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

import static com.linecorp.centraldogma.internal.thrift.RevisionConverter.TO_DATA;
import static com.linecorp.centraldogma.internal.thrift.RevisionConverter.TO_MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class RevisionConverterTest {

    private static final com.linecorp.centraldogma.common.Revision COMMON =
            new com.linecorp.centraldogma.common.Revision(1);
    private static final Revision THRIFT = new Revision(1, 0);

    @Test
    public void test() throws Exception {
        assertThat(TO_DATA.convert(COMMON)).isEqualTo(THRIFT);
        assertThat(TO_MODEL.convert(THRIFT)).isEqualTo(COMMON);
        assertThat(TO_DATA.convert(TO_MODEL.convert(THRIFT))).isEqualTo(THRIFT);
    }
}
