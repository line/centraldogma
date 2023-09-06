/*
 * Copyright 2020 LINE Corporation
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

import static com.linecorp.centraldogma.server.internal.mirror.credential.MirrorCredentialTest.HOSTNAME_PATTERNS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.google.common.base.Splitter;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;

public class PublicKeyMirrorCredentialTest {

    private static final String USERNAME = "trustin";

    // The real key pair generated using:
    //
    //   ssh-keygen -t rsa -b 768 -N sesame
    //
    public static final String PUBLIC_KEY =
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAYQCmkW9HjZE5q0EM06MUWXYFTNTi" +
            "KkfYD/pH2GwJw6yi20Gi0TzjJ6YBLueU48vxkwWmw6sTOEuBxtzefTxs4kQuatev" +
            "uXn7tWX9fhSIAEp+zdyQY7InyCqfHFwRwswemCM= trustin@localhost";

    public static final String PRIVATE_KEY =
            "-----BEGIN RSA PRIVATE KEY-----\n" +
            "Proc-Type: 4,ENCRYPTED\n" +
            "DEK-Info: AES-128-CBC,C35856D3C524AA2FD32D878F4409B97E\n" +
            '\n' +
            "X3HRmqg2bUqfqxkWjHsr4KeN1UyN5QbypGd7Jov/nDSyiIWe4zPJD/3oji0xOK+h\n" +
            "Lxq+c8DDu7ItpC6dwe5WexcyIKGF7WqlkqeEhVM3VOkQtbpbdnb7bA8mLja2unMW\n" +
            "bFLgQiTF1Y8SlG4Q70N0iY638AeIG/ZUU14LSBFSQDkrtZ+f7bhIhVDDavANMF+B\n" +
            "+eiQ4u3W59Cpbm83AfzqotrPXuBusfyBjH7Wfj0XRvOGRjTQT0jXIWWpLqnIy5ms\n" +
            "HNGlMoJElUQuPpbQUiFvmqiMj40r9V/Wx/8+GciADOs4FsTvGFKIcouWDhjIWg0b\n" +
            "DKFqV/Hw/AjkAafkySxxmk1+EIen4XfkghtlWLwT2Xp4RtJXYiVC9q9483jDv3+Z\n" +
            "iTa5rjFuro4WJkDZp6/N6l+/HcbBXL8L6y66xsJwP+6GLuDLpXjGZrneV1ip2dtG\n" +
            "BQzvlgCOr9pTAa4Ar7MC3E2C6+qPhOwO4B/f1cigwRaEB92MHz5gJsITU3xVfTjV\n" +
            "yf4THKipBDxqnET6F2FMZJFolVzFEXDaCFNC1TjBqS0+A8KaMcO/lXjJxtfvV37l\n" +
            "zmB/ey0dZ8WBCazCp9OX3dYgNkVR1yYNlJWOGJS8Cwc=\n" +
            "-----END RSA PRIVATE KEY-----";

    public static final String PASSPHRASE = "sesame";
    private static final String PASSPHRASE_BASE64 = "base64:c2VzYW1l"; // 'sesame'

    @Test
    void testConstruction() {
        // null checks
        assertThatThrownBy(() -> new PublicKeyMirrorCredential(
                null, null, null, PUBLIC_KEY, PRIVATE_KEY, PASSPHRASE, true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PublicKeyMirrorCredential(
                null, null, USERNAME, null, PRIVATE_KEY, PASSPHRASE, true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PublicKeyMirrorCredential(
                null, null, USERNAME, PUBLIC_KEY, null, PASSPHRASE, true))
                .isInstanceOf(NullPointerException.class);

        // null passphrase must be accepted.
        assertThat(new PublicKeyMirrorCredential(
                null, null, USERNAME, PUBLIC_KEY, PRIVATE_KEY, null, true).passphrase()).isNull();

        // emptiness checks
        assertThatThrownBy(() -> new PublicKeyMirrorCredential(
                null, null, "", PUBLIC_KEY, PRIVATE_KEY, PASSPHRASE, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicKeyMirrorCredential(
                null, null, USERNAME, "", PRIVATE_KEY, PASSPHRASE, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicKeyMirrorCredential(
                null, null, USERNAME, PUBLIC_KEY, "", PASSPHRASE, true))
                .isInstanceOf(IllegalArgumentException.class);

        // empty passphrase must be accepted, because an empty password is still a password.
        assertThat(new PublicKeyMirrorCredential(
                null, null, USERNAME, PUBLIC_KEY, PRIVATE_KEY, "", true).passphrase()).isEmpty();

        // successful construction
        final PublicKeyMirrorCredential c = new PublicKeyMirrorCredential(
                null, null, USERNAME, PUBLIC_KEY, PRIVATE_KEY, PASSPHRASE, true);

        assertThat(c.username()).isEqualTo(USERNAME);
        assertThat(c.publicKey()).isEqualTo(PUBLIC_KEY);
        assertThat(c.privateKey()).isEqualTo(Splitter.on('\n')
                                                     .omitEmptyStrings()
                                                     .trimResults()
                                                     .splitToList(PRIVATE_KEY));
        assertThat(c.passphrase()).isEqualTo(PASSPHRASE);
    }

    @Test
    void testBase64Passphrase() {
        final PublicKeyMirrorCredential c = new PublicKeyMirrorCredential(
                null, null, USERNAME, PUBLIC_KEY, PRIVATE_KEY, PASSPHRASE_BASE64, true);
        assertThat(c.passphrase()).isEqualTo(PASSPHRASE);
    }

    @Test
    void testDeserialization() throws Exception {
        // plaintext passphrase
        assertThat(Jackson.readValue('{' +
                                     "  \"type\": \"public_key\"," +
                                     "  \"hostnamePatterns\": [" +
                                     "    \"^foo\\\\.com$\"" +
                                     "  ]," +
                                     "  \"username\": \"trustin\"," +
                                     "  \"publicKey\": \"" + Jackson.escapeText(PUBLIC_KEY) + "\"," +
                                     "  \"privateKey\": \"" + Jackson.escapeText(PRIVATE_KEY) + "\"," +
                                     "  \"passphrase\": \"" + Jackson.escapeText(PASSPHRASE) + '"' +
                                     '}', MirrorCredential.class))
                .isEqualTo(new PublicKeyMirrorCredential(null, HOSTNAME_PATTERNS, USERNAME,
                                                         PUBLIC_KEY, PRIVATE_KEY, PASSPHRASE, true));

        // base64 passphrase
        assertThat(Jackson.readValue('{' +
                                     "  \"type\": \"public_key\"," +
                                     "  \"hostnamePatterns\": [" +
                                     "    \"^foo\\\\.com$\"" +
                                     "  ]," +
                                     "  \"username\": \"trustin\"," +
                                     "  \"publicKey\": \"" + Jackson.escapeText(PUBLIC_KEY) + "\"," +
                                     "  \"privateKey\": \"" + Jackson.escapeText(PRIVATE_KEY) + "\"," +
                                     "  \"passphrase\": \"" + Jackson.escapeText(PASSPHRASE_BASE64) + '"' +
                                     '}', MirrorCredential.class))
                .isEqualTo(new PublicKeyMirrorCredential(null, HOSTNAME_PATTERNS, USERNAME,
                                                         PUBLIC_KEY, PRIVATE_KEY, PASSPHRASE, true));

        // ID
        assertThat(Jackson.readValue('{' +
                                     "  \"type\": \"public_key\"," +
                                     "  \"id\": \"foo\"," +
                                     "  \"username\": \"trustin\"," +
                                     "  \"publicKey\": \"" + Jackson.escapeText(PUBLIC_KEY) + "\"," +
                                     "  \"privateKey\": \"" + Jackson.escapeText(PRIVATE_KEY) + "\"," +
                                     "  \"passphrase\": \"" + Jackson.escapeText(PASSPHRASE) + '"' +
                                     '}', MirrorCredential.class))
                .isEqualTo(new PublicKeyMirrorCredential("foo", null, USERNAME,
                                                         PUBLIC_KEY, PRIVATE_KEY, PASSPHRASE, true));
    }
}
