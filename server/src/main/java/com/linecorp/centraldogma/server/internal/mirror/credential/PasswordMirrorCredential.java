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

package com.linecorp.centraldogma.server.internal.mirror.credential;

import static com.linecorp.centraldogma.server.CentralDogmaConfig.convertValue;
import static com.linecorp.centraldogma.server.internal.mirror.credential.MirrorCredentialUtil.requireNonEmpty;
import static java.util.Objects.requireNonNull;

import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.server.mirror.MirrorCredential;

public final class PasswordMirrorCredential extends AbstractMirrorCredential {

    private static final Logger logger = LoggerFactory.getLogger(PasswordMirrorCredential.class);

    private final String username;
    private final String password;

    @JsonCreator
    public PasswordMirrorCredential(@JsonProperty("id") String id,
                                    @JsonProperty("enabled") @Nullable Boolean enabled,
                                    @JsonProperty("hostnamePatterns") @Nullable
                                    @JsonDeserialize(contentAs = Pattern.class)
                                    Iterable<Pattern> hostnamePatterns,
                                    @JsonProperty("username") String username,
                                    @JsonProperty("password") String password) {
        super(id, enabled, "password", hostnamePatterns);

        this.username = requireNonEmpty(username, "username");
        this.password = requireNonNull(password, "password");
    }

    @JsonProperty("username")
    public String username() {
        return username;
    }

    public String password() {
        try {
            return convertValue(password, "credentials.password");
        } catch (Throwable t) {
            // The password probably has `:` without prefix. Just return it as is for backward compatibility.
            logger.debug("Failed to convert the password of the credential. username: {}, id: {}",
                    username, id(), t);
            return password;
        }
    }

    @JsonProperty("password")
    public String rawPassword() {
        return password;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + username.hashCode();
        result = 31 * result + password.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof PasswordMirrorCredential)) {
            return false;
        }

        if (!super.equals(o)) {
            return false;
        }

        final PasswordMirrorCredential that = (PasswordMirrorCredential) o;
        return username.equals(that.username) && password.equals(that.password);
    }

    @Override
    void addProperties(ToStringHelper helper) {
        helper.add("username", username);
    }

    @Override
    public MirrorCredential withoutSecret() {
        return new PasswordMirrorCredential(id(), enabled(), hostnamePatterns(), username(), "****");
    }
}
