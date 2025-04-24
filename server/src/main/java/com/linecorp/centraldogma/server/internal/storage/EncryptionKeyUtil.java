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
package com.linecorp.centraldogma.server.internal.storage;

import java.security.SecureRandom;
import java.security.Security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.conscrypt.Conscrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.util.SystemInfo;

public final class EncryptionKeyUtil {

    private static final Logger logger = LoggerFactory.getLogger(EncryptionKeyUtil.class);

    private static final String ALGORITHM = "AES/GCM-SIV/NoPadding";
    // https://datatracker.ietf.org/doc/html/rfc8452#section-4
    private static final int KEY_SIZE_BYTES = 32;
    public static final int NONCE_SIZE_BYTES = 12;
    private static final int TAG_SIZE_BITS = 128;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final String PROVIDER;

    static {
        if (SystemInfo.isLinux()) {
            Security.addProvider(Conscrypt.newProvider());
            PROVIDER = "Conscrypt";
        } else {
            Security.addProvider(new BouncyCastleProvider());
            PROVIDER = "BC";
        }
        logger.info("Using cryptographic provider: {}", PROVIDER);
    }

    public static byte[] generateAes256Key() {
        final byte[] keyBytes = new byte[KEY_SIZE_BYTES]; // 256비트
        SECURE_RANDOM.nextBytes(keyBytes);
        return keyBytes;
    }

    public static byte[] generateNonce() {
        final byte[] nonce = new byte[NONCE_SIZE_BYTES];
        SECURE_RANDOM.nextBytes(nonce);
        return nonce;
    }

    public static byte[] encrypt(SecretKey key, byte[] nonce, byte[] data, int off, int len) throws Exception {
        final Cipher cipher = Cipher.getInstance(ALGORITHM, PROVIDER);
        final GCMParameterSpec parameterSpec = new GCMParameterSpec(TAG_SIZE_BITS, nonce);
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);
        return cipher.doFinal(data, off, len);
    }

    public static byte[] decrypt(SecretKey key, byte[] nonce, byte[] ciphertext) throws Exception {
        final Cipher cipher = Cipher.getInstance(ALGORITHM, PROVIDER);
        final GCMParameterSpec parameterSpec = new GCMParameterSpec(TAG_SIZE_BITS, nonce);
        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);
        return cipher.doFinal(ciphertext);
    }

    private EncryptionKeyUtil() {}
}
