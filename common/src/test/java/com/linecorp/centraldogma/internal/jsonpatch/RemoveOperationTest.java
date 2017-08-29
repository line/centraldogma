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

import java.io.IOException;

import org.testng.annotations.Test;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

public final class RemoveOperationTest
    extends JsonPatchOperationTest
{
    private static final JsonPointer EMPTY_JSON_POINTER = JsonPointer.compile("");

    public RemoveOperationTest()
        throws IOException
    {
        super("remove");
    }

    @Test
    public void removingRootReturnsMissingNode()
        throws JsonPatchException
    {
        final JsonNode node = JsonNodeFactory.instance.nullNode();
        final JsonPatchOperation op = new RemoveOperation(EMPTY_JSON_POINTER);
        final JsonNode ret = op.apply(node);
        assertTrue(ret.isMissingNode());
    }
}
