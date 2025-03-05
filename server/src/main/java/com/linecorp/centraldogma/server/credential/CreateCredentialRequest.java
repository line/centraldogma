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
package com.linecorp.centraldogma.server.credential;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A request to create a new {@link Credential}.
 */
public final class CreateCredentialRequest {

    private final String credentialId;
    private final Credential credential;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public CreateCredentialRequest(@JsonProperty("credentialId") String credentialId,
                                   @JsonProperty("credential") Credential credential) {
        this.credentialId = credentialId;
        this.credential = credential;
    }

    /**
     * Returns the {@link Credential} ID.
     */
    @JsonProperty
    public String credentialId() {
        return credentialId;
    }

    /**
     * Returns the {@link Credential}.
     */
    @JsonProperty
    public Credential credential() {
        return credential;
    }
}
