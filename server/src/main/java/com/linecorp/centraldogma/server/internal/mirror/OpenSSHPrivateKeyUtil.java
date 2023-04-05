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

package com.linecorp.centraldogma.server.internal.mirror;

import static net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.ED_25519_CURVE_SPEC;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Objects;

import org.apache.sshd.common.cipher.ECCurves;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.buffer.BufferUtils;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.bouncycastle.util.Strings;
import org.eclipse.jgit.util.Base64;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

final class OpenSSHPrivateKeyUtil {

    static final byte[] BEGIN_OPENSSH = Strings.toByteArray("-----BEGIN OPENSSH PRIVATE KEY-----\n");
    static final byte[] END_OPENSSH = Strings.toByteArray("\n-----END OPENSSH PRIVATE KEY-----");
    static final byte[] AUTH_MAGIC = Strings.toByteArray("openssh-key-v1\0");

    private static final String ED25519 = "ssh-ed25519";
    private static final String ECDSA = "ecdsa";
    private static final String RSA = "ssh-rsa";

    static KeyPair parsePrivateKeyBlob(byte[] blob) {
        // Referred to https://github.com/bcgit/bc-java/blob/43e0631b3c0ab5c0d660dc6bd72dd28633f3f839/core/src/main/java/org/bouncycastle/crypto/util/OpenSSHPrivateKeyUtil.java
        final SSHBuffer kIn = new SSHBuffer(AUTH_MAGIC, decode(blob));
        final String cipherName = kIn.readString();
        if (!"none".equals(cipherName)) {
            throw new IllegalArgumentException("encrypted keys are not supported. cipher: " + cipherName);
        }

        // KDF name
        kIn.skipBlock();
        // KDF options
        kIn.skipBlock();

        final int publicKeyCount = kIn.readU32();
        if (publicKeyCount != 1) {
            throw new IllegalArgumentException("multiple keys are not supported. key count: " + publicKeyCount);
        }

        // Skip public key.
        kIn.readBlock();

        final byte[] privateKeyBlock = kIn.readPaddedBlock();
        final SSHBuffer pkIn = new SSHBuffer(privateKeyBlock);
        final int checksum1 = pkIn.readU32();
        final int checksum2 = pkIn.readU32();

        if (checksum1 != checksum2) {
            throw new IllegalArgumentException("private key checksum values are not the same.");
        }
        final String keyType = pkIn.readString();

        try {
            if (ED25519.equals(keyType)) {
                return eddsaKeyPair(pkIn);
            }
            if (keyType.startsWith(ECDSA)) {
                return ecdsaKeyPair(pkIn, keyType);
            }
            if (RSA.equals(keyType)) {
                return rsaKeyPair(pkIn);
            }
        } catch (Exception e) {
            throw new RuntimeException("failed to create a key pair. keyType:" + keyType, e);
        }
        throw new IllegalArgumentException("unsupported key type: " + keyType);
    }

    private static byte[] decode(byte[] blob) {
        final int start = BEGIN_OPENSSH.length;
        return Base64.decode(blob, start, blob.length - start - END_OPENSSH.length);
    }

    private static KeyPair eddsaKeyPair(SSHBuffer pkIn) {
        // Public key
        pkIn.readBlock();
        // Private key value..
        final byte[] edPrivateKey = pkIn.readBlock();
        if (edPrivateKey.length != 64) {
            throw new IllegalArgumentException("the length of the eddsa private key must be 64. length: " +
                                               edPrivateKey.length);
        }

        final EdDSAPrivateKey eddsaPrivateKey = new EdDSAPrivateKey(
                new EdDSAPrivateKeySpec(Arrays.copyOfRange(edPrivateKey, 0, 32), ED_25519_CURVE_SPEC));
        final EdDSAPublicKey eddsaPublicKey = new EdDSAPublicKey(
                new EdDSAPublicKeySpec(Arrays.copyOfRange(edPrivateKey, 32, 64), ED_25519_CURVE_SPEC));
        return new KeyPair(eddsaPublicKey, eddsaPrivateKey);
    }

    private static KeyPair ecdsaKeyPair(SSHBuffer pkIn, String keyType) throws GeneralSecurityException {
        // Referred to https://github.com/apache/mina-sshd/blob/752faa5664ce51c09395a77e0d760de45f5a6140/sshd-common/src/main/java/org/apache/sshd/common/config/keys/loader/openssh/OpenSSHECDSAPrivateKeyEntryDecoder.java
        final ECCurves curve = ECCurves.fromKeyType(keyType);
        if (curve == null) {
            throw new InvalidKeySpecException("Not an EC curve name. keyType: " + keyType);
        }

        if (!SecurityUtils.isECCSupported()) {
            throw new NoSuchProviderException("ECC not supported. keyType: " + keyType);
        }

        final String keyCurveName = curve.getName();
        final String encCurveName = pkIn.readString();
        if (!keyCurveName.equals(encCurveName)) {
            throw new InvalidKeySpecException(
                    "Mismatched key curve name (" + keyCurveName + ") vs. encoded one (" + encCurveName + ')');
        }
        final ECParameterSpec params = curve.getParameters();

        final byte[] pubKey = pkIn.readBlock();
        final KeyFactory keyFactory = SecurityUtils.getKeyFactory(KeyUtils.EC_ALGORITHM);

        final ECPoint w;
        try {
            w = ECCurves.octetStringToEcPoint(pubKey);
        } catch (Exception e) {
            throw new InvalidKeySpecException("failed to generate ECPoint for curve=" + keyCurveName +
                                              " from octets=" + BufferUtils.toHex(':', pubKey), e);
        }
        if (w == null) {
            throw new InvalidKeySpecException("No ECPoint generated for curve=" + keyCurveName +
                                              " from octets=" + BufferUtils.toHex(':', pubKey));
        }
        final PublicKey publicKey = keyFactory.generatePublic(new ECPublicKeySpec(w, params));
        final PrivateKey privateKey = keyFactory.generatePrivate(
                new ECPrivateKeySpec(new BigInteger(pkIn.readBlock()), params));
        return new KeyPair(publicKey, privateKey);
    }

    private static KeyPair rsaKeyPair(SSHBuffer pkIn) throws GeneralSecurityException {
        // Referred to https://github.com/apache/mina-sshd/blob/752faa5664ce51c09395a77e0d760de45f5a6140/sshd-common/src/main/java/org/apache/sshd/common/config/keys/loader/openssh/OpenSSHRSAPrivateKeyDecoder.java
        final BigInteger n = new BigInteger(pkIn.readBlock());
        final BigInteger e = new BigInteger(pkIn.readBlock());
        if (!Objects.equals(e, KeyUtils.DEFAULT_RSA_PUBLIC_EXPONENT)) {
            throw new IllegalArgumentException("non-standard RSA exponent: " + e);
        }

        final BigInteger d = new BigInteger(pkIn.readBlock());
        final BigInteger inverseQmodP = new BigInteger(pkIn.readBlock());
        final BigInteger p = new BigInteger(pkIn.readBlock());
        final BigInteger q = new BigInteger(pkIn.readBlock());
        final BigInteger modulus = p.multiply(q);
        if (!Objects.equals(n, modulus)) {
            throw new IllegalArgumentException("mismatched modulus values: encoded=" + n +
                                               ", calculated=" + modulus);
        }
        final KeyFactory keyFactory = SecurityUtils.getKeyFactory(KeyUtils.RSA_ALGORITHM);
        final PrivateKey privateKey = keyFactory.generatePrivate(
                new RSAPrivateCrtKeySpec(n, e, d, p, q, d.mod(p.subtract(BigInteger.ONE)),
                                         d.mod(q.subtract(BigInteger.ONE)), inverseQmodP));
        final PublicKey publicKey = keyFactory.generatePublic(new RSAPublicKeySpec(n, e));
        return new KeyPair(publicKey, privateKey);
    }

    private OpenSSHPrivateKeyUtil() {}
}
