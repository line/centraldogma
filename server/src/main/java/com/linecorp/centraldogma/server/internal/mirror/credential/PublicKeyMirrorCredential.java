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

import static com.linecorp.centraldogma.server.internal.mirror.credential.MirrorCredentialUtil.maybeDecodeBase64;
import static com.linecorp.centraldogma.server.internal.mirror.credential.MirrorCredentialUtil.requireNonEmpty;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.CharMatcher;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

public final class PublicKeyMirrorCredential extends AbstractMirrorCredential {

    private static final Splitter NEWLINE_SPLITTER = Splitter.on(CharMatcher.anyOf("\n\r"))
                                                             .omitEmptyStrings()
                                                             .trimResults();

    private static final int PUBLIC_KEY_PREVIEW_LEN = 40;

    private final String username;
    private final String publicKey;
    private final String privateKey;
    @Nullable
    private final String passphrase;
    @Nullable
    private final String passphraseString;

    @JsonCreator
    public PublicKeyMirrorCredential(@JsonProperty("id") @Nullable String id,
                                     @JsonProperty("hostnamePatterns") @Nullable
                                     @JsonDeserialize(contentAs = Pattern.class)
                                     Iterable<Pattern> hostnamePatterns,
                                     @JsonProperty("username") String username,
                                     @JsonProperty("publicKey") String publicKey,
                                     @JsonProperty("privateKey") String privateKey,
                                     @JsonProperty("passphrase") @Nullable String passphrase,
                                     @JsonProperty("enabled") @Nullable Boolean enabled) {

        super(id, "public_key", hostnamePatterns, enabled);

        this.username = requireNonEmpty(username, "username");
        this.publicKey = requireNonEmpty(publicKey, "publicKey");
        requireNonEmpty(privateKey, "privateKey");
        // privateKey is converted into a list of Strings that will be used as an input of
        // KeyPairResourceLoader.loadKeyPairs(...)
        this.privateKey = privateKey;
        this.passphrase = maybeDecodeBase64(passphrase, "passphrase");
        this.passphraseString = passphrase;
    }

    @JsonProperty("username")
    public String username() {
        return username;
    }

    @JsonProperty("publicKey")
    public String publicKey() {
        return publicKey;
    }

    @JsonProperty("privateKey")
    public String privateKey() {
        return privateKey;
    }

    @Nullable
    public String passphrase() {
        return passphrase;
    }

    @JsonProperty("passphrase")
    @Nullable
    public String passphraseString() {
        return passphraseString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof PublicKeyMirrorCredential)) {
            return false;
        }

        if (!super.equals(o)) {
            return false;
        }

        final PublicKeyMirrorCredential that = (PublicKeyMirrorCredential) o;

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
}
