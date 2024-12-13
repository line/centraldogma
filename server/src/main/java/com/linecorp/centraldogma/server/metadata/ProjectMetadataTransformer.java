/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.server.metadata;

import static com.linecorp.centraldogma.server.metadata.MetadataService.METADATA_JSON;

import java.util.function.BiFunction;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.ContentTransformer;

class ProjectMetadataTransformer extends ContentTransformer<JsonNode> {

    ProjectMetadataTransformer(BiFunction<Revision, ProjectMetadata, ProjectMetadata> transformer) {
        super(METADATA_JSON, EntryType.JSON,
              (headRevision, jsonNode) -> Jackson.valueToTree(
                      transformer.apply(headRevision, projectMetadata(jsonNode))));
    }

    private static ProjectMetadata projectMetadata(JsonNode node) {
        try {
            return Jackson.treeToValue(node, ProjectMetadata.class);
        } catch (JsonParseException | JsonMappingException e) {
            // Should never reach here.
            throw new Error();
        }
    }
}
