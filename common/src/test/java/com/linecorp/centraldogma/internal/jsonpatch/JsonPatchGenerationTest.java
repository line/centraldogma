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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Equivalence;

class JsonPatchGenerationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Equivalence<JsonNode> EQUIVALENCE = JsonNumEquals.getInstance();

    @Order(1)
    @ParameterizedTest
    @MethodSource("patches")
    void patchAppliesCleanly(JsonNode source, JsonNode target) {
        final JsonPatch patch = JsonPatch.generate(source, target, ReplaceMode.SAFE);
        final Predicate<JsonNode> predicate = EQUIVALENCE.equivalentTo(target)::apply;
        final JsonNode actual = patch.apply(source);

        assertThat(predicate.test(actual))
                .withFailMessage("Generated patch failed to apply\nexpected: %s\nactual: %s", target, actual)
                .isTrue();
    }

    @Order(2)
    @ParameterizedTest
    @MethodSource("literalPatches")
    void expectedPatches(String message, JsonNode source, JsonNode target, JsonNode expected) {
        final JsonNode actual = JsonPatch.generate(source, target, ReplaceMode.SAFE).toJson();
        final Predicate<JsonNode> predicate = EQUIVALENCE.equivalentTo(expected)::apply;

        assertThat(predicate.test(actual))
                .withFailMessage("Patch is not what was expected\nscenario: %s\nexpected: %s\nactual: %s\n",
                                 message, expected, actual)
                .isTrue();
    }

    private static List<Arguments> patches() throws Exception {
        final List<Arguments> arguments = new ArrayList<>();

        for (JsonNode node : getNode()) {
            arguments.add(Arguments.of(node.get("first"), node.get("second")));
        }

        return arguments;
    }

    private static List<Arguments> literalPatches() throws Exception {
        final List<Arguments> arguments = new ArrayList<>();

        for (JsonNode node : getNode()) {
            if (!node.has("patch")) {
                continue;
            }

            arguments.add(Arguments.of(
                    node.get("message").textValue(), node.get("first"),
                    node.get("second"), node.get("patch")));
        }

        return arguments;
    }

    private static JsonNode getNode() throws IOException {
        final String resource = "/jsonpatch/diff/diff.json";
        final URL url = JsonPatchGenerationTest.class.getResource(resource);
        return MAPPER.readTree(url);
    }
}
