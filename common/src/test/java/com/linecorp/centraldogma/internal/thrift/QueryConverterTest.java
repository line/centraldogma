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

import static com.linecorp.centraldogma.internal.thrift.QueryConverter.TO_DATA;
import static com.linecorp.centraldogma.internal.thrift.QueryConverter.TO_MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

class QueryConverterTest {

    private static final com.linecorp.centraldogma.common.Query<?> IDENTITY_MODEL =
            com.linecorp.centraldogma.common.Query.of(
                    com.linecorp.centraldogma.common.QueryType.IDENTITY, "/a.txt");
    private static final Query IDENTITY_DATA = new Query().setPath("/a.txt").setType(QueryType.IDENTITY)
                                                          .setExpressions(Collections.emptyList());

    private static final com.linecorp.centraldogma.common.Query<?> IDENTITY_TEXT_MODEL =
            com.linecorp.centraldogma.common.Query.ofText("/a.json");
    private static final Query IDENTITY_TEXT_DATA = new Query().setPath("/a.json")
                                                              .setType(QueryType.IDENTITY_TEXT)
                                                              .setExpressions(Collections.emptyList());

    private static final com.linecorp.centraldogma.common.Query<?> IDENTITY_JSON_MODEL =
            com.linecorp.centraldogma.common.Query.ofJson("/a.json");
    private static final Query IDENTITY_JSON_DATA = new Query().setPath("/a.json")
                                                               .setType(QueryType.IDENTITY_JSON)
                                                               .setExpressions(Collections.emptyList());

    private static final com.linecorp.centraldogma.common.Query<JsonNode> JSON_PATH_MODEL =
            com.linecorp.centraldogma.common.Query.ofJsonPath("/a.json", "a", "b");
    private static final Query JSON_PATH_DATA = new Query().setPath("/a.json")
                                                           .setType(QueryType.JSON_PATH)
                                                           .setExpressions(ImmutableList.of("a", "b"));

    @Test
    void test() {
        assertThat(TO_DATA.convert(IDENTITY_MODEL)).isEqualTo(IDENTITY_DATA);
        assertThat(TO_MODEL.convert(IDENTITY_DATA)).isEqualTo(IDENTITY_MODEL);
        assertThat(TO_DATA.convert(TO_MODEL.convert(IDENTITY_DATA))).isEqualTo(IDENTITY_DATA);

        assertThat(TO_DATA.convert(IDENTITY_TEXT_MODEL)).isEqualTo(IDENTITY_TEXT_DATA);
        assertThat(TO_MODEL.convert(IDENTITY_TEXT_DATA)).isEqualTo(IDENTITY_TEXT_MODEL);
        assertThat(TO_DATA.convert(TO_MODEL.convert(IDENTITY_TEXT_DATA))).isEqualTo(IDENTITY_TEXT_DATA);

        assertThat(TO_DATA.convert(IDENTITY_JSON_MODEL)).isEqualTo(IDENTITY_JSON_DATA);
        assertThat(TO_MODEL.convert(IDENTITY_JSON_DATA)).isEqualTo(IDENTITY_JSON_MODEL);
        assertThat(TO_DATA.convert(TO_MODEL.convert(IDENTITY_JSON_DATA))).isEqualTo(IDENTITY_JSON_DATA);

        assertThat(TO_DATA.convert(JSON_PATH_MODEL)).isEqualTo(JSON_PATH_DATA);
        assertThat(TO_MODEL.convert(JSON_PATH_DATA)).isEqualTo(JSON_PATH_MODEL);
        assertThat(TO_DATA.convert(TO_MODEL.convert(JSON_PATH_DATA))).isEqualTo(JSON_PATH_DATA);
    }
}

