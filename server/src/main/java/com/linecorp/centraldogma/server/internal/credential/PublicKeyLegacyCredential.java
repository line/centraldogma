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

package com.linecorp.centraldogma.server.internal.credential;

import static com.linecorp.centraldogma.internal.CredentialUtil.requireNonEmpty;
import static com.linecorp.centraldogma.server.CentralDogmaConfig.convertValue;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.CharMatcher;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.credential.LegacyCredential;

public final class PublicKeyLegacyCredential extends AbstractLegacyCredential {

    private static final Logger logger = LoggerFactory.getLogger(PublicKeyLegacyCredential.class);

    private static final Splitter NEWLINE_SPLITTER = Splitter.on(CharMatcher.anyOf("\n\r"))
                                                             .omitEmptyStrings()
                                                             .trimResults();

    private static final int PUBLIC_KEY_PREVIEW_LEN = 40;

    private final String username;
    private final String publicKey;
    private final String privateKey;
    @Nullable
    private final String passphrase;

    @JsonCreator
    public PublicKeyLegacyCredential(@JsonProperty("id") String id,
                                     @JsonProperty("enabled") @Nullable Boolean enabled,
                                     @JsonProperty("username") String username,
                                     @JsonProperty("publicKey") String publicKey,
                                     @JsonProperty("privateKey") String privateKey,
                                     @JsonProperty("passphrase") @Nullable String passphrase) {
        super(id, enabled, "public_key");

        this.username = requireNonEmpty(username, "username");
        this.publicKey = requireNonEmpty(publicKey, "publicKey");
        this.privateKey = requireNonEmpty(privateKey, "privateKey");
        this.passphrase = passphrase;
    }

    @JsonProperty("username")
    public String username() {
        return username;
    }

    @JsonProperty("publicKey")
    public String publicKey() {
        return publicKey;
    }

    public List<String> privateKey() {
        String converted;
        try {
            converted = convertValue(privateKey, "credentials.privateKey");
        } catch (Throwable t) {
            // Just use it as is for backward compatibility.
            logger.debug("Failed to convert the key of the credential. username: {}, id: {}",
                         username, id(), t);
            converted = privateKey;
        }
        assert converted != null;
        // privateKey is converted into a list of Strings that will be used as an input of
        // KeyPairResourceLoader.loadKeyPairs(...)
        return ImmutableList.copyOf(NEWLINE_SPLITTER.splitToList(converted));
    }

    @JsonProperty("privateKey")
    public String rawPrivateKey() {
        return privateKey;
    }

    @Nullable
    public String passphrase() {
        try {
            return convertValue(passphrase, "credentials.passphrase");
        } catch (Throwable t) {
            // The passphrase probably has `:` without prefix. Just return it as is for backward compatibility.
            logger.debug("Failed to convert the passphrase of the credential. username: {}, id: {}",
                         username, id(), t);
            return passphrase;
        }
    }

    @JsonProperty("passphrase")
    @Nullable
    public String rawPassphrase() {
        return passphrase;
    }

    @Override
    public Credential toNewCredential(String name) {
        return new SshKeyCredential(name, username, publicKey, privateKey, passphrase);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof PublicKeyLegacyCredential)) {
            return false;
        }

        if (!super.equals(o)) {
            return false;
        }

        final PublicKeyLegacyCredential that = (PublicKeyLegacyCredential) o;

        return username.equals(that.username) &&
               Objects.equals(publicKey, that.publicKey) &&
               Objects.equals(privateKey, that.privateKey) &&
               Objects.equals(passphrase, that.passphrase);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), username, publicKey, privateKey, passphrase);
    }

    @Override
    void addProperties(ToStringHelper helper) {
        helper.add("username", username)
              .add("publicKey", publicKeyPreview(publicKey));
    }

    public static String publicKeyPreview(String publicKey) {
        if (publicKey.length() > PUBLIC_KEY_PREVIEW_LEN) {
            return publicKey.substring(0, PUBLIC_KEY_PREVIEW_LEN) + "..";
        }
        return publicKey;
    }

    @Override
    public LegacyCredential withoutSecret() {
        return new PublicKeyLegacyCredential(id(), enabled(), username(), publicKey(),
                                             "****", "****");
    }
}
