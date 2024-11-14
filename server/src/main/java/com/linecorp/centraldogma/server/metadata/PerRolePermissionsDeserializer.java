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

import static com.linecorp.centraldogma.server.metadata.Permission.highestPermission;

import java.io.IOException;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import com.linecorp.centraldogma.internal.Jackson;

final class PerRolePermissionsDeserializer extends StdDeserializer<PerRolePermissions> {

    private static final long serialVersionUID = 1173216371065909688L;

    private static final TypeReference<Set<Permission>> PERMISSION_SET_TYPE =
            new TypeReference<Set<Permission>>() {};

    PerRolePermissionsDeserializer() {
        super(PerRolePermissions.class);
    }

    @Override
    public PerRolePermissions deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        final JsonNode jsonNode = p.readValueAsTree();
        final Permission memberPermission = getPermission(jsonNode.get("member"));
        final Permission guestPermission = getPermission(jsonNode.get("guest"));

        return new PerRolePermissions(memberPermission, guestPermission);
    }

    @Nullable
    static Permission getPermission(@Nullable JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) {
            return null;
        }
        if (jsonNode.isArray()) {
            final Set<Permission> permissions = Jackson.convertValue(jsonNode, PERMISSION_SET_TYPE);
            return highestPermission(permissions);
        }
        return Permission.valueOf(jsonNode.textValue());
    }
}
