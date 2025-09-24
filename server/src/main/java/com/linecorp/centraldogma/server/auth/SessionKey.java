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
package com.linecorp.centraldogma.server.auth;

import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.aesSecretKey;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

/**
 * A session key used to sign and encrypt session cookies.
 */
public final class SessionKey {

    private static final byte[] SIGNING_KEY_INFO = "session-signing-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENCRYPTION_KEY_INFO = "session-encryption-key".getBytes(StandardCharsets.UTF_8);

    /**
     * Derives a new {@link SessionKey} from the specified {@code masterKey} and {@code salt}.
     */
    public static SessionKey of(byte[] masterKey, byte[] salt, int version) {
        final HKDFBytesGenerator hkdfBytesGenerator = new HKDFBytesGenerator(new SHA256Digest());
        final byte[] signingKeyBytes = new byte[32];
        hkdfBytesGenerator.init(new HKDFParameters(masterKey, salt, SIGNING_KEY_INFO));
        hkdfBytesGenerator.generateBytes(signingKeyBytes, 0, 32);

        final byte[] encryptionKeyBytes = new byte[32];
        hkdfBytesGenerator.init(new HKDFParameters(masterKey, salt, ENCRYPTION_KEY_INFO));
        hkdfBytesGenerator.generateBytes(encryptionKeyBytes, 0, 32);

        final SecretKey finalSigningKey = new SecretKeySpec(signingKeyBytes, "HmacSHA256");
        final SecretKey finalEncryptionKey = aesSecretKey(encryptionKeyBytes);
        return new SessionKey(finalSigningKey, finalEncryptionKey, version);
    }

    private final SecretKey signingKey;
    private final SecretKey encryptionKey;
    private final int version;

    private SessionKey(SecretKey signingKey, SecretKey encryptionKey, int version) {
        this.signingKey = signingKey;
        this.encryptionKey = encryptionKey;
        this.version = version;
    }

    /**
     * Returns the secret key used to sign session tokens.
     */
    public SecretKey signingKey() {
        return signingKey;
    }

    /**
     * Returns the secret key used to encrypt session tokens.
     */
    public SecretKey encryptionKey() {
        return encryptionKey;
    }

    /**
     * Returns the version of this session key.
     */
    public int version() {
        return version;
    }
}
