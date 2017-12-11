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

package com.linecorp.centraldogma.client;

import static com.linecorp.centraldogma.client.CommitAndChangesConverter.TO_DATA;
import static com.linecorp.centraldogma.client.CommitAndChangesConverter.TO_MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.internal.thrift.Author;
import com.linecorp.centraldogma.internal.thrift.Change;
import com.linecorp.centraldogma.internal.thrift.ChangeType;
import com.linecorp.centraldogma.internal.thrift.Comment;
import com.linecorp.centraldogma.internal.thrift.Commit;
import com.linecorp.centraldogma.internal.thrift.Markup;
import com.linecorp.centraldogma.internal.thrift.Revision;

public class CommitAndChangesConverterTest {
    private static final String TIMESTAMP = "2016-01-02T03:04:05Z";

    private static final CommitAndChanges<String> COMMON =
            new CommitAndChanges<>(
                    new com.linecorp.centraldogma.common.Commit(
                            new com.linecorp.centraldogma.common.Revision(1),
                            new com.linecorp.centraldogma.common.Author("user", "user@sample.com"),
                            Instant.parse(TIMESTAMP).toEpochMilli(),
                            "summary",
                            "hello",
                            com.linecorp.centraldogma.common.Markup.PLAINTEXT),
                    ImmutableList.of(com.linecorp.centraldogma.common.Change.ofTextUpsert("/a.txt", "hello")));

    private static final Commit THRIFT = new Commit(new Revision(1, 0),
                                                    new Author("user", "user@sample.com"),
                                                    TIMESTAMP,
                                                    "summary",
                                                    new Comment("hello").setMarkup(Markup.PLAINTEXT),
                                                    ImmutableList.of(new Change()
                                                                             .setPath("/a.txt")
                                                                             .setType(ChangeType.UPSERT_TEXT)
                                                                             .setContent("hello")));

    @Test
    public void test() throws Exception {
        assertThat(TO_DATA.convert(COMMON)).isEqualTo(THRIFT);
        assertThat(TO_MODEL.convert(THRIFT)).isEqualTo(COMMON);
        assertThat(TO_DATA.convert(TO_MODEL.convert(THRIFT))).isEqualTo(THRIFT);
    }
}
