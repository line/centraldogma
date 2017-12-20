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

package com.linecorp.centraldogma.internal.api.v1;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import com.linecorp.centraldogma.common.Markup;

/**
 * Deserializes JSON into a {@link CommitMessageDto}.
 */
public class CommitMessageDtoDeserializer extends StdDeserializer<CommitMessageDto> {

    protected CommitMessageDtoDeserializer() {
        super(CommitMessageDto.class);
    }

    @Override
    public CommitMessageDto deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        final JsonNode jsonNode = p.readValueAsTree();
        final JsonNode summary = jsonNode.get("summary");
        if (summary == null || summary.textValue() == null) {
            ctxt.reportInputMismatch(CommitMessageDto.class, "commit message should have a summary.");
            // should never reach here
            throw new Error();
        }

        final String detail = jsonNode.get("detail") == null ? "" : jsonNode.get("detail").textValue();
        final JsonNode markupNode = jsonNode.get("markup");
        final Markup markup = Markup.parse(markupNode == null ? "unknown" : markupNode.textValue());
        return new CommitMessageDto(summary.textValue(), detail, markup);
    }
}
