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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.QuotaConfig;

final class RepositoryMetadataDeserializer extends StdDeserializer<RepositoryMetadata> {

    private static final long serialVersionUID = 1173216371065909688L;

    RepositoryMetadataDeserializer() {
        super(RepositoryMetadata.class);
    }

    @Override
    public RepositoryMetadata deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        final JsonNode jsonNode = p.readValueAsTree();
        final String name = jsonNode.get("name").textValue();
        final PerRolePermissions perRolePermissions =
                Jackson.treeToValue(jsonNode.get("perRolePermissions"), PerRolePermissions.class);
        final Map<String, Permission> perUserPermissions = perPermission(jsonNode, "perUserPermissions");
        final Map<String, Permission> perTokenPermissions = perPermission(jsonNode, "perTokenPermissions");
        final UserAndTimestamp creation = Jackson.treeToValue(jsonNode.get("creation"), UserAndTimestamp.class);
        final JsonNode removalNode = jsonNode.get("removal");
        final UserAndTimestamp removal =
                removalNode == null ? null : Jackson.treeToValue(removalNode, UserAndTimestamp.class);

        final JsonNode writeQuotaNode = jsonNode.get("writeQuota");
        final QuotaConfig writeQuota =
                writeQuotaNode == null ? null : Jackson.treeToValue(writeQuotaNode, QuotaConfig.class);

        return new RepositoryMetadata(name, perRolePermissions, perUserPermissions, perTokenPermissions,
                                      creation, removal, writeQuota);
    }

    private static Map<String, Permission> perPermission(JsonNode rootNode, String filed) {
        final JsonNode permissionsNode = rootNode.get(filed);

        final ImmutableMap.Builder<String, Permission> builder = ImmutableMap.builder();
        final Iterator<Entry<String, JsonNode>> fields = permissionsNode.fields();
        while (fields.hasNext()) {
            final Entry<String, JsonNode> field = fields.next();
            final String id = field.getKey();
            final Permission permission =
                    requireNonNull(PerRolePermissionsDeserializer.getPermission(field.getValue()));
            builder.put(id, permission);
        }

        return builder.build();
    }
}
