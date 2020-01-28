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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.common.base.Equivalence;
import com.google.common.collect.ImmutableList;

class JsonPatchOperationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectReader READER = MAPPER.readerFor(JsonPatchOperation.class);
    private static final Equivalence<JsonNode> EQUIVALENCE = JsonNumEquals.getInstance();

    private static final List<String> OPERATIONS =
            ImmutableList.of("add", "copy", "move", "remove", "removeIfExists",
                             "replace", "safe_replace", "test", "testAbsence");

    @ParameterizedTest
    @MethodSource("ops")
    void operationsYieldExpectedResults(JsonNode patch, JsonNode node, JsonNode expected) throws IOException {
        final JsonPatchOperation op = READER.readValue(patch);
        final JsonNode actual = op.apply(node.deepCopy());

        assertThat(EQUIVALENCE.equivalent(actual, expected)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("errors")
    void errorsAreCorrectlyReported(JsonNode patch, JsonNode node, String message) throws IOException {
        final JsonPatchOperation op = READER.readValue(patch);

        assertThatThrownBy(() -> op.apply(node))
                .isInstanceOf(JsonPatchException.class)
                .hasMessage(message);
    }

    private static List<Arguments> ops() throws Exception {
        final ImmutableList.Builder<Arguments> arguments = ImmutableList.builder();

        for (String prefix : OPERATIONS) {
            final JsonNode ops = getNode(prefix, "ops");

            for (JsonNode node : ops) {
                arguments.add(Arguments.of(
                        node.get("op"),
                        node.get("node"),
                        node.get("expected")));
            }
        }

        return arguments.build();
    }

    private static List<Arguments> errors() throws Exception {
        final ImmutableList.Builder<Arguments> arguments = ImmutableList.builder();

        for (String prefix : OPERATIONS) {
            final JsonNode errors = getNode(prefix, "errors");

            for (JsonNode node : errors) {
                arguments.add(Arguments.of(
                        node.get("op"),
                        node.get("node"),
                        node.get("message").textValue()));
            }
        }

        return arguments.build();
    }

    private static JsonNode getNode(String prefix, String field) throws IOException {
        final String resource = "/jsonpatch/" + prefix + ".json";
        final URL url = JsonPatchOperationTest.class.getResource(resource);
        return MAPPER.readTree(url).get(field);
    }
}
