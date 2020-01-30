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
/*
 * Copyright (c) 2014, Francis Galiegue (fgaliegue@gmail.com)
 *
 * This software is dual-licensed under:
 *
 * - the Lesser General Public License (LGPL) version 3.0 or, at your option, any
 *   later version;
 * - the Apache Software License (ASL) version 2.0.
 *
 * The text of this file and of both licenses is available at the root of this
 * project or, if you have the jar distribution, in directory META-INF/, under
 * the names LGPL-3.0.txt and ASL-2.0.txt respectively.
 *
 * Direct link to the sources:
 *
 * - LGPL 3.0: https://www.gnu.org/licenses/lgpl-3.0.txt
 * - ASL 2.0: https://www.apache.org/licenses/LICENSE-2.0.txt
 */

package com.linecorp.centraldogma.internal.jsonpatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

class JsonPatchUnchangedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<JsonPointer, JsonNode>> TYPE_REF =
            new TypeReference<Map<JsonPointer, JsonNode>>() {};

    @ParameterizedTest
    @MethodSource("arguments")
    void computeUnchangedValues(JsonNode source, JsonNode target, Map<JsonPointer, JsonNode> expected) {
        final Map<JsonPointer, JsonNode> actual = JsonPatch.unchangedValues(source, target);
        assertThat(actual).isEqualTo(expected);
    }

    private static List<Arguments> arguments() throws IOException {
        final String resource = "/jsonpatch/diff/unchanged.json";
        final URL url = JsonPatchUnchangedTest.class.getResource(resource);
        final JsonNode nodes = MAPPER.readTree(url);

        final ImmutableList.Builder<Arguments> arguments = ImmutableList.builder();

        for (JsonNode node : nodes) {
            arguments.add(Arguments.of(
                    node.get("first"), node.get("second"),
                    MAPPER.readValue(node.get("unchanged").traverse(), TYPE_REF)));
        }

        return arguments.build();
    }
}
