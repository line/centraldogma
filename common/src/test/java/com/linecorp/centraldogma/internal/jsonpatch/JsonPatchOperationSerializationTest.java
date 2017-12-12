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

import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.util.Iterator;
import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Equivalence;
import com.google.common.collect.Lists;

@Test
public abstract class JsonPatchOperationSerializationTest {

    private static final Equivalence<JsonNode> EQUIVALENCE = JsonNumEquals.getInstance();

    private final Class<? extends JsonPatchOperation> opType;
    private final JsonNode node;
    private final ObjectMapper mapper;

    protected JsonPatchOperationSerializationTest(
            final String prefix, final Class<? extends JsonPatchOperation> opType) throws IOException {
        mapper = new ObjectMapper();
        final String resource = "/jsonpatch/" + prefix + ".json";
        URL url = getClass().getResource(resource);
        node = mapper.readTree(url);
        this.opType = opType;
    }

    @DataProvider
    public final Iterator<Object[]> getInputs() {
        final List<Object[]> list = Lists.newArrayList();

        for (final JsonNode n : node.get("errors")) {
            list.add(new Object[] { n.get("op") });
        }

        for (final JsonNode n : node.get("ops")) {
            list.add(new Object[] { n.get("op") });
        }

        return list.iterator();
    }

    @Test(dataProvider = "getInputs")
    public final void patchOperationSerializationWorks(final JsonNode input) throws IOException {
        /*
         * Deserialize a string input
         */
        final String in = input.toString();
        final JsonPatchOperation op = mapper.readValue(in, JsonPatchOperation.class);

        /*
         * Check that the class of the operation is what is expected
         */
        assertSame(op.getClass(), opType);

        /*
         * Now, write the operation as a String...
         */
        final String out = mapper.writeValueAsString(op);

        /*
         * And read it as a JsonNode again, then test for equality.
         *
         * The reason we do that is that JSON does not guarantee the order of
         * object members; but JsonNode's .equals() method will correctly handle
         * this event, and we trust its .toString().
         */
        final JsonNode output = mapper.readTree(out);
        assertTrue(EQUIVALENCE.equivalent(input, output));
    }
}

