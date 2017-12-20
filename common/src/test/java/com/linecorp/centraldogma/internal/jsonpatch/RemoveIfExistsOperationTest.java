/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.centraldogma.internal.jsonpatch;

import static org.testng.Assert.assertTrue;

import java.io.IOException;

import org.testng.annotations.Test;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

public final class RemoveIfExistsOperationTest extends JsonPatchOperationTest {

    private static final JsonPointer EMPTY_JSON_POINTER = JsonPointer.compile("");

    public RemoveIfExistsOperationTest() throws IOException {
        super("removeIfExists");
    }

    @Test
    public void removingRootReturnsMissingNode() {
        final JsonNode node = JsonNodeFactory.instance.nullNode();
        final JsonPatchOperation op = new RemoveIfExistsOperation(EMPTY_JSON_POINTER);
        final JsonNode ret = op.apply(node);
        assertTrue(ret.isMissingNode());
    }
}
