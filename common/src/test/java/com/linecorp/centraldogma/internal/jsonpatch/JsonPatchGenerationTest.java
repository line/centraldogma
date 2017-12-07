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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Equivalence;
import com.google.common.base.Predicate;

public final class JsonPatchGenerationTest {

    private static final Equivalence<JsonNode> EQUIVALENCE = JsonNumEquals.getInstance();

    private final JsonNode testData;

    public JsonPatchGenerationTest() throws IOException {
        final String resource = "/jsonpatch/diff/diff.json";
        URL url = getClass().getResource(resource);
        ObjectMapper objectMapper = new ObjectMapper();
        testData = objectMapper.readTree(url);
    }

    @DataProvider
    public Iterator<Object[]> getPatchesOnly() {
        final List<Object[]> list = new ArrayList<>();
        for (final JsonNode node : testData) {
            list.add(new Object[] { node.get("first"), node.get("second") });
        }

        return list.iterator();
    }

    @Test(dataProvider = "getPatchesOnly")
    public void generatedPatchAppliesCleanly(final JsonNode source, final JsonNode target) {
        final JsonPatch patch = JsonPatch.generate(source, target, ReplaceMode.SAFE);
        final Predicate<JsonNode> predicate = EQUIVALENCE.equivalentTo(target);
        final JsonNode actual = patch.apply(source);

        assertThat(predicate.apply(actual)).overridingErrorMessage(
                "Generated patch failed to apply\nexpected: %s\nactual: %s",
                target, actual
        ).isTrue();
    }

    @DataProvider
    public Iterator<Object[]> getLiteralPatches() {
        final List<Object[]> list = new ArrayList<>();
        for (final JsonNode node : testData) {
            if (!node.has("patch")) {
                continue;
            }
            list.add(new Object[] {
                    node.get("message").textValue(), node.get("first"),
                    node.get("second"), node.get("patch")
            });
        }

        return list.iterator();
    }

    @Test(dataProvider = "getLiteralPatches",
          dependsOnMethods = "generatedPatchAppliesCleanly")
    public void generatedPatchesAreWhatIsExpected(final String message,
                                                  final JsonNode source, final JsonNode target,
                                                  final JsonNode expected) {
        final JsonNode actual = JsonPatch.generate(source, target, ReplaceMode.SAFE).toJson();
        final Predicate<JsonNode> predicate = EQUIVALENCE.equivalentTo(expected);
        assertThat(predicate.apply(actual)).overridingErrorMessage(
                "patch is not what was expected\nscenario: %s\n" +
                "expected: %s\nactual: %s\n", message, expected, actual
        ).isTrue();
    }
}
