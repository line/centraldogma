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

import com.linecorp.centraldogma.server.internal.credential.AccessTokenCredential;
import com.linecorp.centraldogma.server.internal.credential.NoneCredential;
import com.linecorp.centraldogma.server.internal.credential.PasswordCredential;
import com.linecorp.centraldogma.server.internal.credential.PublicKeyCredential;

/**
 * A credential used to access external resources such as Git repositories or the Kubernetes control plane.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({
        @Type(value = NoneCredential.class, name = "none"),
        @Type(value = PasswordCredential.class, name = "password"),
        @Type(value = PublicKeyCredential.class, name = "public_key"),
        @Type(value = AccessTokenCredential.class, name = "access_token")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public interface Credential {

    Credential FALLBACK = new NoneCredential("", true);

    /**
     * Returns the ID of the credential.
     */
    @JsonProperty("id")
    String id();

    /**
     * Returns the resource name of the credential.
     * It is in the form of {@code "projects/<project>/credentials/<credential>"} or
     * {@code "projects/<project>/repos/<repo>/credentials/<credential>"}.
     */
    @JsonProperty("resourceName")
    String resourceName();

    /**
     * Returns whether this {@link Credential} is enabled.
     */
    @JsonProperty("enabled")
    boolean enabled();

    /**
     * Returns a new {@link Credential} that does not contain any sensitive information.
     */
    Credential withoutSecret();
}
