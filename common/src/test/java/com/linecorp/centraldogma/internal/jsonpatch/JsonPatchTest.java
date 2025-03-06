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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.jsonpatch.JsonPatchConflictException;
import com.linecorp.centraldogma.common.jsonpatch.JsonPatchOperation;

class JsonPatchTest {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    @Mock
    private JsonPatchOperation op1;
    @Mock
    private JsonPatchOperation op2;

    @Test
    void nullInputsDuringBuildAreRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> JsonPatch.fromJson(null));
    }

    @Test
    void cannotPatchNull() {
        final JsonPatch patch = new JsonPatch(ImmutableList.of(op1, op2));

        assertThatNullPointerException()
                .isThrownBy(() -> patch.apply(null));
    }

    @Test
    void operationsAreCalledInOrder() {
        final JsonNode node1 = FACTORY.textNode("hello");
        final JsonNode node2 = FACTORY.textNode("world");

        when(op1.apply(node1)).thenReturn(node2);

        final JsonPatch patch = new JsonPatch(ImmutableList.of(op1, op2));
        final ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);

        patch.apply(node1);
        verify(op1, only()).apply(same(node1));
        verify(op2, only()).apply(captor.capture());

        assertThat(captor.getValue()).isSameAs(node2);
    }

    @Test
    void whenOneOperationFailsNextOperationIsNotCalled() {
        final String message = "foo";
        when(op1.apply(any(JsonNode.class)))
                .thenThrow(new JsonPatchConflictException(message));

        final JsonPatch patch = new JsonPatch(ImmutableList.of(op1, op2));

        assertThatThrownBy(() -> patch.apply(FACTORY.nullNode()))
                .isInstanceOf(JsonPatchConflictException.class)
                .hasMessage(message);

        verifyNoMoreInteractions(op2);
    }
}
