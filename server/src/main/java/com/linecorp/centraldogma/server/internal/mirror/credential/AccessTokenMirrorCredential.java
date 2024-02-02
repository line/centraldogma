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

package com.linecorp.centraldogma.server.internal.mirror.credential;

import static com.linecorp.centraldogma.server.CentralDogmaConfig.convertValue;
import static com.linecorp.centraldogma.server.internal.mirror.credential.MirrorCredentialUtil.requireNonEmpty;

import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.MoreObjects.ToStringHelper;

public final class AccessTokenMirrorCredential extends AbstractMirrorCredential {

    private static final Logger logger = LoggerFactory.getLogger(AccessTokenMirrorCredential.class);

    private final String accessToken;

    @JsonCreator
    public AccessTokenMirrorCredential(@JsonProperty("id") @Nullable String id,
                                       @JsonProperty("hostnamePatterns") @Nullable
                                       @JsonDeserialize(contentAs = Pattern.class)
                                       Iterable<Pattern> hostnamePatterns,
                                       @JsonProperty("accessToken") String accessToken) {
        super(id, hostnamePatterns);
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

        if (!(o instanceof AccessTokenMirrorCredential)) {
            return false;
        }

        if (!super.equals(o)) {
            return false;
        }

        final AccessTokenMirrorCredential that = (AccessTokenMirrorCredential) o;
        return accessToken.equals(that.accessToken);
    }

    @Override
    void addProperties(ToStringHelper helper) {
        // Access token must be kept secret.
    }
}
