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

package com.linecorp.centraldogma.it.mirror.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;

import com.jcraft.jsch.JSch;

class KeyPairUtilsTest {

    static String toPemFormat(PrivateKey privateKey) {
        assert "PKCS#8".equalsIgnoreCase(privateKey.getFormat());

        String key = "-----BEGIN PRIVATE KEY-----";
        key += System.lineSeparator();
        key += Base64.getMimeEncoder().encodeToString(privateKey.getEncoded());
        key += System.lineSeparator();
        key += "-----END PRIVATE KEY-----";
        key += System.lineSeparator();

        return key;
    }

    static String toPemFormat(PublicKey publicKey) {
        final StringBuilder sb = new StringBuilder();
        try {
            PublicKeyEntry.appendPublicKeyEntry(sb, publicKey);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return sb.toString();
    }

    @Test
    void testKeyLoad() throws Exception {

        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KeyPair keyPair = generator.generateKeyPair();

        final String pubKey = toPemFormat(keyPair.getPublic());
        final String privKey = toPemFormat(keyPair.getPrivate());

        final com.jcraft.jsch.KeyPair loaded = com.jcraft.jsch.KeyPair.load(
                new JSch(), privKey.getBytes(StandardCharsets.UTF_8), pubKey.getBytes(StandardCharsets.UTF_8));
        assertThat(loaded.getKeyType()).isEqualTo(com.jcraft.jsch.KeyPair.RSA);
    }
}
