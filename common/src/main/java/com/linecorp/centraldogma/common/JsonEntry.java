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

package com.linecorp.centraldogma.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;

final class JsonEntry extends DefaultEntry<JsonNode> {

    private String strVal;

    JsonEntry(String path, JsonNode content) {
        super(path, content, EntryType.JSON);
    }

    @Override
    public String contentAsText() {
        String strVal = this.strVal;
        if (strVal == null) {
            try {
                this.strVal = strVal = Jackson.writeValueAsString(content());
            } catch (JsonProcessingException e) {
                // Should never happen because it's a JSON tree already.
                throw new Error(e);
            }
        }

        return strVal;
    }
}
