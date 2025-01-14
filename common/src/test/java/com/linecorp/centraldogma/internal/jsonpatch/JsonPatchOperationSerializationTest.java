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
import java.util.Map.Entry;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Equivalence;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.jsonpatch.AddOperation;
import com.linecorp.centraldogma.common.jsonpatch.CopyOperation;
import com.linecorp.centraldogma.common.jsonpatch.JsonPatchOperation;
import com.linecorp.centraldogma.common.jsonpatch.MoveOperation;
import com.linecorp.centraldogma.common.jsonpatch.RemoveIfExistsOperation;
import com.linecorp.centraldogma.common.jsonpatch.RemoveOperation;
import com.linecorp.centraldogma.common.jsonpatch.ReplaceOperation;
import com.linecorp.centraldogma.common.jsonpatch.TestAbsenceOperation;
import com.linecorp.centraldogma.common.jsonpatch.TestOperation;

class JsonPatchOperationSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Equivalence<JsonNode> EQUIVALENCE = JsonNumEquals.getInstance();

    private static final Map<String, Class<? extends JsonPatchOperation>> OPERATIONS =
            ImmutableMap.<String, Class<? extends JsonPatchOperation>>builder()
                    .put("add", AddOperation.class)
                    .put("copy", CopyOperation.class)
                    .put("move", MoveOperation.class)
                    .put("remove", RemoveOperation.class)
                    .put("removeIfExists", RemoveIfExistsOperation.class)
                    .put("replace", ReplaceOperation.class)
                    .put("test", TestOperation.class)
                    .put("testAbsence", TestAbsenceOperation.class)
                    .build();

    @ParameterizedTest
    @MethodSource("arguments")
    void serialization(Class<? extends JsonPatchOperation> opType, JsonNode input) throws Exception {
        // Deserialize a string input.
        final String in = input.toString();
        final JsonPatchOperation op = MAPPER.readValue(in, JsonPatchOperation.class);

        // Check that the class of the operation is what is expected.
        assertThat(op.getClass()).isSameAs(opType);

        // Now write the operation as a String...
        final String out = MAPPER.writeValueAsString(op);

        // And read it as a JsonNode again, then test for equality.
        // The reason we do that is that JSON does not guarantee the order of
        // object members; but JsonNode's .equals() method will correctly handle
        // this event, and we trust its .toString().
        final JsonNode output = MAPPER.readTree(out);
        assertThat(EQUIVALENCE.equivalent(input, output)).isTrue();
    }

    private static List<Arguments> arguments() throws Exception {
        final ImmutableList.Builder<Arguments> arguments = ImmutableList.builder();

        for (Entry<String, Class<? extends JsonPatchOperation>> op : OPERATIONS.entrySet()) {
            getNodes(op.getKey())
                    .stream()
                    .map(node -> Arguments.of(op.getValue(), node))
                    .forEach(arguments::add);
        }

        return arguments.build();
    }

    private static List<JsonNode> getNodes(String prefix) throws IOException {
        final String resource = "/jsonpatch/" + prefix + ".json";
        final URL url = JsonPatchOperationSerializationTest.class.getResource(resource);
        final JsonNode node = MAPPER.readTree(url);

        final ImmutableList.Builder<JsonNode> inputs = ImmutableList.builder();

        for (JsonNode n : node.get("errors")) {
            inputs.add(n.get("op"));
        }

        for (JsonNode n : node.get("ops")) {
            inputs.add(n.get("op"));
        }

        return inputs.build();
    }
}

