/*
 * Copyright 2025 LINE Corporation
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

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A custom deserializer for {@link AppIdentity} that handles legacy token JSON format.
 * Legacy tokens may not have a "type" field, but they will have a "secret" field,
 * which indicates they should be deserialized as {@link Token}.
 */
final class AppIdentityDeserializer extends JsonDeserializer<AppIdentity> {

    @Override
    public AppIdentity deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        final ObjectMapper mapper = (ObjectMapper) p.getCodec();
        final JsonNode node = mapper.readTree(p);

        // Check if type field exists
        final JsonNode typeNode = node.get("type");

        if (typeNode != null) {
            // Type field exists, deserialize based on type
            final String typeValue = typeNode.asText();
            final AppIdentityType type;
            try {
                type = AppIdentityType.valueOf(typeValue);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown AppIdentityType: " + typeValue, e);
            }

            switch (type) {
                case TOKEN:
                    return deserializeToken(node);
                case CERTIFICATE:
                    return deserializeCertificate(node);
            }
            // Should never reach here
            throw new Error();
        } else {
            // Legacy format: no type field
            // If secret field exists, it's a Token
            final JsonNode secretNode = node.get("secret");
            if (secretNode != null) {
                return deserializeToken(node);
            } else {
                throw new IllegalArgumentException(
                        "Cannot deserialize AppIdentity: missing both 'type' and 'secret' fields");
            }
        }
    }

    private static Token deserializeToken(JsonNode node) {
        final String appId = getRequiredText(node, "appId");
        final String secret = getRequiredText(node, "secret");
        final boolean systemAdmin = getBoolean(node, "systemAdmin", false);
        final Boolean allowGuestAccess = getOptionalBoolean(node, "allowGuestAccess");
        final UserAndTimestamp creation = deserializeUserAndTimestamp(getRequiredNode(node, "creation"));
        final UserAndTimestamp deactivation = deserializeOptionalUserAndTimestamp(node, "deactivation");
        final UserAndTimestamp deletion = deserializeOptionalUserAndTimestamp(node, "deletion");

        return new Token(appId, secret, systemAdmin, allowGuestAccess, creation, deactivation, deletion);
    }

    private static CertificateAppIdentity deserializeCertificate(JsonNode node) {
        final String appId = getRequiredText(node, "appId");
        final String certificateId = getRequiredText(node, "certificateId");
        final boolean systemAdmin = getBoolean(node, "systemAdmin", false);
        final Boolean allowGuestAccess = getOptionalBoolean(node, "allowGuestAccess");
        final UserAndTimestamp creation = deserializeUserAndTimestamp(getRequiredNode(node, "creation"));
        final UserAndTimestamp deactivation = deserializeOptionalUserAndTimestamp(node, "deactivation");
        final UserAndTimestamp deletion = deserializeOptionalUserAndTimestamp(node, "deletion");

        return new CertificateAppIdentity(appId, certificateId, systemAdmin, allowGuestAccess,
                                          creation, deactivation, deletion);
    }

    private static UserAndTimestamp deserializeUserAndTimestamp(JsonNode node) {
        final String user = getRequiredText(node, "user");
        final String timestamp = getRequiredText(node, "timestamp");
        return new UserAndTimestamp(user, timestamp);
    }

    @Nullable
    private static UserAndTimestamp deserializeOptionalUserAndTimestamp(JsonNode parentNode, String fieldName) {
        final JsonNode node = parentNode.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        return deserializeUserAndTimestamp(node);
    }

    private static JsonNode getRequiredNode(JsonNode parent, String fieldName) {
        final JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }
        return node;
    }

    private static String getRequiredText(JsonNode parent, String fieldName) {
        final JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Field '" + fieldName + "' must be a string");
        }
        return node.asText();
    }

    private static boolean getBoolean(JsonNode parent, String fieldName, boolean defaultValue) {
        final JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw new IllegalArgumentException("Field '" + fieldName + "' must be a boolean");
        }
        return node.asBoolean();
    }

    @Nullable
    private static Boolean getOptionalBoolean(JsonNode parent, String fieldName) {
        final JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new IllegalArgumentException("Field '" + fieldName + "' must be a boolean");
        }
        return node.asBoolean();
    }
}
