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

import static com.linecorp.centraldogma.internal.thrift.CommitConverter.TO_DATA;
import static com.linecorp.centraldogma.internal.thrift.CommitConverter.TO_MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

public class CommitConverterTest {
    private static final String TIMESTAMP = "2016-01-02T03:04:05Z";

    private static final com.linecorp.centraldogma.common.Commit COMMON =
            new com.linecorp.centraldogma.common.Commit(
                    new com.linecorp.centraldogma.common.Revision(1),
                    new com.linecorp.centraldogma.common.Author("user", "user@sample.com"),
                    Instant.parse(TIMESTAMP).toEpochMilli(),
                    "summary",
                    "hello",
                    com.linecorp.centraldogma.common.Markup.PLAINTEXT);

    private static final Commit THRIFT = new Commit(new Revision(1, 0),
                                                    new Author("user", "user@sample.com"),
                                                    TIMESTAMP,
                                                    "summary",
                                                    new Comment("hello").setMarkup(Markup.PLAINTEXT),
                                                    ImmutableList.of());

    @Test
    public void test() throws Exception {
        assertThat(TO_DATA.convert(COMMON)).isEqualTo(THRIFT);
        assertThat(TO_MODEL.convert(THRIFT)).isEqualTo(COMMON);
        assertThat(TO_DATA.convert(TO_MODEL.convert(THRIFT))).isEqualTo(THRIFT);
    }
}
