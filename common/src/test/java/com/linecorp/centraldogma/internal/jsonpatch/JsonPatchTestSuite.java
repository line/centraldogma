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

import static com.google.common.base.MoreObjects.firstNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.jsonpatch.JsonPatchConflictException;

class JsonPatchTestSuite {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @MethodSource("tests")
    void testSuite(JsonNode source, JsonPatch patch, JsonNode expected, boolean valid) {
        if (valid) {
            final JsonNode actual = patch.apply(source);
            // Cast to Object so that we do not use IterableAssert.
            assertThat((Object) actual).isEqualTo(expected);
        } else {
            assertThatThrownBy(() -> patch.apply(source))
                    .isInstanceOf(JsonPatchConflictException.class);
        }
    }

    private static List<Arguments> tests() throws Exception {
        final ImmutableList.Builder<Arguments> arguments = ImmutableList.builder();

        final URL url = JsonPatchTestSuite.class.getResource("/jsonpatch/testsuite.json");
        final JsonNode testNode = MAPPER.readTree(url);

        for (JsonNode element : testNode) {
            if (!element.has("patch")) {
                continue;
            }

            final JsonPatch patch = JsonPatch.fromJson(element.get("patch"));
            final JsonNode source = element.get("doc");
            final JsonNode expected = firstNonNull(element.get("expected"), source);
            final boolean valid = !element.has("error");

            arguments.add(Arguments.of(source, patch, expected, valid));
        }

        return arguments.build();
    }
}
