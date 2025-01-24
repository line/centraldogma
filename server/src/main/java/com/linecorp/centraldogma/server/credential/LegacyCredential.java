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

package com.linecorp.centraldogma.server.credential;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import com.linecorp.centraldogma.server.internal.credential.AccessTokenLegacyCredential;
import com.linecorp.centraldogma.server.internal.credential.NoneLegacyCredential;
import com.linecorp.centraldogma.server.internal.credential.PasswordLegacyCredential;
import com.linecorp.centraldogma.server.internal.credential.PublicKeyLegacyCredential;

/**
 * A credential used to access external resources such as Git repositories or the Kubernetes control plane.
 *
 * @deprecated Use {@link Credential} instead.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({
        @Type(value = NoneLegacyCredential.class, name = "none"),
        @Type(value = PasswordLegacyCredential.class, name = "password"),
        @Type(value = PublicKeyLegacyCredential.class, name = "public_key"),
        @Type(value = AccessTokenLegacyCredential.class, name = "access_token")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Deprecated
public interface LegacyCredential {

    /**
     * Returns the ID of the credential.
     */
    @JsonProperty("id")
    String id();

    /**
     * Returns whether this {@link LegacyCredential} is enabled.
     */
    @JsonProperty("enabled")
    boolean enabled();

    /**
     * Converts this {@link LegacyCredential} into a new {@link Credential}.
     */
    Credential toNewCredential(String name);

    /**
     * Returns a new {@link LegacyCredential} that does not contain any sensitive information.
     */
    LegacyCredential withoutSecret();
}
