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

import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonPatchTestSuite {

    private final JsonNode testNode;

    public JsonPatchTestSuite() throws IOException {
        URL url = this.getClass().getResource("/jsonpatch/testsuite.json");
        ObjectMapper objectMapper = new ObjectMapper();
        testNode = objectMapper.readTree(url);
    }

    @DataProvider
    public Iterator<Object[]> getTests() throws IOException {
        final List<Object[]> list = new ArrayList<>();
        for (final JsonNode element : testNode) {
            if (!element.has("patch")) {
                continue;
            }

            final JsonPatch patch = JsonPatch.fromJson(element.get("patch"));
            final JsonNode source = element.get("doc");
            JsonNode expected = element.get("expected");
            if (expected == null) {
                expected = source;
            }

            final boolean valid = !element.has("error");
            list.add(new Object[] { source, patch, expected, valid });
        }

        return list.iterator();
    }

    @Test(dataProvider = "getTests")
    public void testsFromTestSuitePass(final JsonNode source, final JsonPatch patch,
                                       final JsonNode expected, final boolean valid) {
        try {
            final JsonNode actual = patch.apply(source);
            if (!valid) {
                fail("Test was expected to fail!");
            }
            // Have to do that... TestNG tries to be too smart with regards
            // to iterable collections...
            assertTrue(actual.equals(expected));
        } catch (JsonPatchException ignored) {
            if (valid) {
                fail("Test was expected to succeed!");
            }
        }
    }
}
