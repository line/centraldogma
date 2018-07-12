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

import static com.linecorp.centraldogma.server.internal.mirror.credential.MirrorCredentialUtil.decodeBase64OrUtf8;
import static com.linecorp.centraldogma.server.internal.mirror.credential.MirrorCredentialUtil.requireNonEmpty;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.MoreObjects.ToStringHelper;

public final class PublicKeyMirrorCredential extends AbstractMirrorCredential {

    private static final int PUBLIC_KEY_PREVIEW_LEN = 40;

    private final String username;
    private final byte[] publicKey;
    private final byte[] privateKey;
    @Nullable
    private final byte[] passphrase;

    @JsonCreator
    public PublicKeyMirrorCredential(@JsonProperty("id") @Nullable String id,
                                     @JsonProperty("hostnamePatterns") @Nullable
                                     @JsonDeserialize(contentAs = Pattern.class)
                                     Iterable<Pattern> hostnamePatterns,
                                     @JsonProperty("username") String username,
                                     @JsonProperty("publicKey") String publicKey,
                                     @JsonProperty("privateKey") String privateKey,
                                     @JsonProperty("passphrase") @Nullable String passphrase) {

        super(id, hostnamePatterns);

        this.username = requireNonEmpty(username, "username");

        requireNonEmpty(publicKey, "publicKey");
        requireNonEmpty(privateKey, "privateKey");
        this.publicKey = requireNonEmpty(publicKey, "publicKey").getBytes(StandardCharsets.UTF_8);
        this.privateKey = requireNonEmpty(privateKey, "privateKey").getBytes(StandardCharsets.UTF_8);

        this.passphrase = decodeBase64OrUtf8(passphrase, "passphrase");
    }

    public PublicKeyMirrorCredential(@Nullable String id,
                                     @Nullable Iterable<Pattern> hostnamePatterns,
                                     String username, byte[] publicKey, byte[] privateKey,
                                     @Nullable byte[] passphrase) {
        super(id, hostnamePatterns);

        this.username = requireNonEmpty(username, "username");
        this.publicKey = requireNonEmpty(publicKey, "publicKey");
        this.privateKey = requireNonEmpty(privateKey, "privateKey");
        this.passphrase = passphrase;
    }

    public String username() {
        return username;
    }

    public byte[] publicKey() {
        return publicKey.clone();
    }

    public byte[] privateKey() {
        return privateKey.clone();
    }

    @Nullable
    public byte[] passphrase() {
        if (passphrase == null) {
            return null;
        } else {
            return passphrase.clone();
        }
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
               Arrays.equals(publicKey, that.publicKey) &&
               Arrays.equals(privateKey, that.privateKey) &&
               Arrays.equals(passphrase, that.passphrase);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + username.hashCode();
        result = 31 * result + Arrays.hashCode(publicKey);
        result = 31 * result + Arrays.hashCode(privateKey);
        result = 31 * result + Arrays.hashCode(passphrase);
        return result;
    }

    @Override
    void addProperties(ToStringHelper helper) {
        final String publicKeyPreview;
        if (publicKey.length > PUBLIC_KEY_PREVIEW_LEN) {
            publicKeyPreview = new String(publicKey, 0, PUBLIC_KEY_PREVIEW_LEN, StandardCharsets.UTF_8) + "..";
        } else {
            publicKeyPreview = new String(publicKey, StandardCharsets.UTF_8);
        }

        helper.add("username", username)
              .add("publicKey", publicKeyPreview);
    }
}
