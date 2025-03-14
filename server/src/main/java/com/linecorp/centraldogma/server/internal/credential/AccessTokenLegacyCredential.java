/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.credential;

import static com.linecorp.centraldogma.internal.CredentialUtil.requireNonEmpty;
import static com.linecorp.centraldogma.server.CentralDogmaConfig.convertValue;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.credential.LegacyCredential;

public final class AccessTokenLegacyCredential extends AbstractLegacyCredential {

    private static final Logger logger = LoggerFactory.getLogger(AccessTokenLegacyCredential.class);

    private final String accessToken;

    @JsonCreator
    public AccessTokenLegacyCredential(@JsonProperty("id") String id,
                                       @JsonProperty("enabled") @Nullable Boolean enabled,
                                       @JsonProperty("accessToken") String accessToken) {
        super(id, enabled, "access_token");
        this.accessToken = requireNonEmpty(accessToken, "accessToken");
    }

    public String accessToken() {
        try {
            return convertValue(accessToken, "credentials.accessToken");
        } catch (Throwable t) {
            // The accessToken probably has `:` without prefix. Just return it as is for backward compatibility.
            logger.debug("Failed to convert the access token of the credential: {}", id(), t);
            return accessToken;
        }
    }

    @JsonProperty("accessToken")
    public String rawAccessToken() {
        return accessToken;
    }

    @Override
    public Credential toNewCredential(String name) {
        return new AccessTokenCredential(name, accessToken);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + accessToken.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof AccessTokenLegacyCredential)) {
            return false;
        }

        if (!super.equals(o)) {
            return false;
        }

        final AccessTokenLegacyCredential that = (AccessTokenLegacyCredential) o;
        return accessToken.equals(that.accessToken);
    }

    @Override
    void addProperties(ToStringHelper helper) {
        // Access token must be kept secret.
    }

    @Override
    public LegacyCredential withoutSecret() {
        return new AccessTokenLegacyCredential(id(), enabled(), "****");
    }
}
