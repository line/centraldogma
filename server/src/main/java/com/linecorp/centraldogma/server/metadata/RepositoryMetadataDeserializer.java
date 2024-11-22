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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.centraldogma.server.metadata.PerRolePermissions.READ_WRITE;

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
import com.google.common.collect.ImmutableList;

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

        final PerRolePermissions perRolePermissions;
        final Map<String, Collection<Permission>> perUserPermissions;
        final Map<String, Collection<Permission>> perTokenPermissions;
        if (perRolePermissionsNode != null)  {
            // legacy format
            perRolePermissions = Jackson.treeToValue(perRolePermissionsNode, PerRolePermissions.class);
            perUserPermissions = Jackson.readValue(jsonNode.get("perUserPermissions").traverse(),
                                                   PER_PERMISSIONS_TYPE);
            perTokenPermissions = Jackson.readValue(jsonNode.get("perTokenPermissions").traverse(),
                                                    PER_PERMISSIONS_TYPE);
        } else {
            // new format
            final Roles roles = Jackson.treeToValue(jsonNode.get("roles"), Roles.class);
            perRolePermissions = new PerRolePermissions(READ_WRITE,
                                                        getPermissions(roles.projectRoles().member()),
                                                        getPermissions(roles.projectRoles().guest()), null);
            perUserPermissions = convert(roles.users());
            perTokenPermissions = convert(roles.tokens());
        }

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

    private static Map<String, Collection<Permission>> convert(
            Map<String, RepositoryRole> roles) {
        return roles.entrySet().stream()
                    .collect(toImmutableMap(Entry::getKey, entry -> getPermissions(entry.getValue())));
    }

    static Collection<Permission> getPermissions(@Nullable RepositoryRole repositoryRole) {
        if (repositoryRole == null) {
            return ImmutableList.of();
        }
        if (repositoryRole == RepositoryRole.READ) {
            return ImmutableList.of(Permission.READ);
        }
        // WRITE or ADMIN RepositoryRole.
        return ImmutableList.of(Permission.READ, Permission.WRITE);
    }
}
