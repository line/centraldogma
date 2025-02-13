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
import com.linecorp.centraldogma.server.internal.credential.SshKeyCredential;

/**
 * A credential used to access external resources such as Git repositories or the Kubernetes control plane.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({
        @Type(value = NoneCredential.class, name = "NONE"),
        @Type(value = PasswordCredential.class, name = "PASSWORD"),
        @Type(value = SshKeyCredential.class, name = "SSH_KEY"),
        @Type(value = AccessTokenCredential.class, name = "ACCESS_TOKEN")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public interface Credential {

    Credential NONE = new NoneCredential("");

    /**
     * Returns the ID of the credential.
     */
    String id();

    /**
     * Returns the name of the credential.
     * It is in the form of {@code "projects/<project>/credentials/<credential>"} or
     * {@code "projects/<project>/repos/<repo>/credentials/<credential>"}.
     */
    @JsonProperty("name")
    String name();

    /**
     * Returns the {@link CredentialType}.
     */
    @JsonProperty("type")
    CredentialType type();

    /**
     * Returns a new {@link Credential} that does not contain any sensitive information.
     */
    Credential withoutSecret();

    /**
     * Returns a new {@link Credential} with the specified {@code credentialName}.
     */
    Credential withName(String credentialName);
}
