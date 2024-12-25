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

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;

import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.QuotaConfig;

final class RepositoryMetadataDeserializer extends StdDeserializer<RepositoryMetadata> {

    private static final long serialVersionUID = 1173216371065909688L;

    private static final TypeReference<Map<String, Collection<Permission>>> PER_PERMISSIONS_TYPE =
            new TypeReference<Map<String, Collection<Permission>>>() {};

    RepositoryMetadataDeserializer() {
        super(RepositoryMetadata.class);
    }

    @Override
    public RepositoryMetadata deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        final JsonNode jsonNode = p.readValueAsTree();
        final String name = jsonNode.get("name").textValue();
        final JsonNode perRolePermissionsNode = jsonNode.get("perRolePermissions");

        final Roles roles;
        if (perRolePermissionsNode != null)  {
            assert jsonNode.get("roles") == null;
            // legacy format
            final PerRolePermissions perRolePermissions =
                    Jackson.treeToValue(perRolePermissionsNode, PerRolePermissions.class);
            final Map<String, Collection<Permission>> perUserPermissions =
                    Jackson.readValue(jsonNode.get("perUserPermissions").traverse(), PER_PERMISSIONS_TYPE);
            final Map<String, Collection<Permission>> perTokenPermissions =
                    Jackson.readValue(jsonNode.get("perTokenPermissions").traverse(), PER_PERMISSIONS_TYPE);
            roles = new Roles(ProjectRoles.of(repositoryRole(perRolePermissions.member()),
                                              repositoryRole(perRolePermissions.guest())),
                              convert(perUserPermissions), convert(perTokenPermissions));
        } else {
            // new format
            roles = Jackson.treeToValue(jsonNode.get("roles"), Roles.class);
        }

        final UserAndTimestamp creation = Jackson.treeToValue(jsonNode.get("creation"), UserAndTimestamp.class);
        final JsonNode removalNode = jsonNode.get("removal");
        final UserAndTimestamp removal =
                removalNode == null ? null : Jackson.treeToValue(removalNode, UserAndTimestamp.class);

        final JsonNode writeQuotaNode = jsonNode.get("writeQuota");
        final QuotaConfig writeQuota =
                writeQuotaNode == null ? null : Jackson.treeToValue(writeQuotaNode, QuotaConfig.class);

        return new RepositoryMetadata(name, roles, creation, removal, writeQuota);
    }

    @Nullable
    private static RepositoryRole repositoryRole(Collection<Permission> permissions) {
        if (permissions.isEmpty()) {
            return null;
        }
        if (permissions.contains(Permission.WRITE)) {
            return RepositoryRole.WRITE;
        }
        return RepositoryRole.READ;
    }

    private static Map<String, RepositoryRole> convert(Map<String, Collection<Permission>> permissions) {
        final Builder<String, RepositoryRole> builder = ImmutableMap.builder();
        for (Entry<String, Collection<Permission>> entry : permissions.entrySet()) {
            final RepositoryRole repositoryRole = repositoryRole(entry.getValue());
            if (repositoryRole != null) {
                builder.put(entry.getKey(), repositoryRole);
            }
        }

        return builder.build();
    }
}
