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

package com.linecorp.centraldogma.server.internal.credential;

import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.ConfigValueConverter;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.credential.LegacyCredential;

public class SshKeyCredentialTest {

    private static final String USERNAME = "trustin";

    // The real key pair generated using:
    //
    //   ssh-keygen -t rsa -b 768 -N sesame
    //
    private static final String PUBLIC_KEY =
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAYQCmkW9HjZE5q0EM06MUWXYFTNTi" +
            "KkfYD/pH2GwJw6yi20Gi0TzjJ6YBLueU48vxkwWmw6sTOEuBxtzefTxs4kQuatev" +
            "uXn7tWX9fhSIAEp+zdyQY7InyCqfHFwRwswemCM= trustin@localhost";

    private static final String PRIVATE_KEY =
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

    private static final String PASSPHRASE = "sesame";
    private static final String PASSPHRASE_BASE64 = "base64:c2VzYW1l"; // 'sesame'

    @Test
    void testConstruction() {
        final String name = credentialName("foo", "key-credential");
        // null checks
        assertThatThrownBy(() -> new SshKeyCredential(name, null, PUBLIC_KEY, PRIVATE_KEY, PASSPHRASE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SshKeyCredential(name, USERNAME, null, PRIVATE_KEY, PASSPHRASE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SshKeyCredential(name, USERNAME, PUBLIC_KEY, null, PASSPHRASE))
                .isInstanceOf(NullPointerException.class);

        // null passphrase must be accepted.
        assertThat(new SshKeyCredential(name, USERNAME, PUBLIC_KEY, PRIVATE_KEY, null).passphrase()).isNull();

        // emptiness checks
        assertThatThrownBy(() -> new SshKeyCredential(name, "", PUBLIC_KEY, PRIVATE_KEY, PASSPHRASE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SshKeyCredential(name, USERNAME, "", PRIVATE_KEY, PASSPHRASE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SshKeyCredential(name, USERNAME, PUBLIC_KEY, "", PASSPHRASE))
                .isInstanceOf(IllegalArgumentException.class);

        // empty passphrase must be accepted, because an empty password is still a password.
        assertThat(new SshKeyCredential(name, USERNAME, PUBLIC_KEY, PRIVATE_KEY, "").passphrase()).isEmpty();

        // successful construction
        final SshKeyCredential c = new SshKeyCredential(name, USERNAME, PUBLIC_KEY, PRIVATE_KEY, PASSPHRASE);

        assertThat(c.name()).isEqualTo(name);
        assertThat(c.id()).isEqualTo("key-credential");
        assertThat(c.username()).isEqualTo(USERNAME);
        assertThat(c.publicKey()).isEqualTo(PUBLIC_KEY);
        assertThat(c.rawPrivateKey()).isEqualTo(PRIVATE_KEY);
        assertThat(c.passphrase()).isEqualTo(PASSPHRASE);
    }

    @Test
    void testBase64Passphrase() {
        final SshKeyCredential c = new SshKeyCredential(credentialName("foo", "id"), USERNAME,
                                                        PUBLIC_KEY, PRIVATE_KEY, PASSPHRASE_BASE64);
        assertThat(c.passphrase()).isEqualTo(PASSPHRASE);
    }

    @Test
    void testDeserialization() throws Exception {
        // Legacy format plaintext passphrase
        assertThat(Jackson.readValue('{' +
                                     "  \"id\": \"foo\"," +
                                     "  \"type\": \"public_key\"," +
                                     "  \"username\": \"trustin\"," +
                                     "  \"publicKey\": \"" + Jackson.escapeText(PUBLIC_KEY) + "\"," +
                                     "  \"privateKey\": \"" + Jackson.escapeText(PRIVATE_KEY) + "\"," +
                                     "  \"passphrase\": \"" + Jackson.escapeText(PASSPHRASE) + '"' +
                                     '}', LegacyCredential.class))
                .isEqualTo(new PublicKeyLegacyCredential("foo", true, USERNAME, PUBLIC_KEY,
                                                         PRIVATE_KEY, PASSPHRASE));

        final String name = credentialName("foo", "key-credential");
        // New format plaintext passphrase
        assertThat(Jackson.readValue('{' +
                                     "  \"type\": \"SSH_KEY\"," +
                                     "  \"name\": \"" + name + "\"," +
                                     "  \"username\": \"trustin\"," +
                                     "  \"publicKey\": \"" + Jackson.escapeText(PUBLIC_KEY) + "\"," +
                                     "  \"privateKey\": \"" + Jackson.escapeText(PRIVATE_KEY) + "\"," +
                                     "  \"passphrase\": \"" + Jackson.escapeText(PASSPHRASE) + '"' +
                                     '}', Credential.class))
                .isEqualTo(new SshKeyCredential(name, USERNAME, PUBLIC_KEY, PRIVATE_KEY, PASSPHRASE));

        // Legacy format base64 passphrase
        final PublicKeyLegacyCredential base64Expected =
                new PublicKeyLegacyCredential("bar", true, USERNAME, PUBLIC_KEY,
                                              PRIVATE_KEY, PASSPHRASE_BASE64);
        assertThat(Jackson.readValue(legacyPublicKey("bar"), LegacyCredential.class))
                .isEqualTo(base64Expected);
        assertThat(base64Expected.passphrase()).isEqualTo(PASSPHRASE);

        // New format base64 passphrase
        final SshKeyCredential newBase64Expected =
                new SshKeyCredential(name, USERNAME, PUBLIC_KEY, PRIVATE_KEY, PASSPHRASE_BASE64);
        assertThat(Jackson.readValue(newSshKey(name), Credential.class))
                .isEqualTo(newBase64Expected);
        assertThat(base64Expected.passphrase()).isEqualTo(PASSPHRASE);
    }

    public static String newSshKey(String name) {
        return '{' +
               "  \"type\": \"SSH_KEY\"," +
               "  \"name\": \"" + name + "\"," +
               "  \"username\": \"trustin\"," +
               "  \"publicKey\": \"" + Jackson.escapeText(PUBLIC_KEY) + "\"," +
               "  \"privateKey\": \"" + Jackson.escapeText(PRIVATE_KEY) + "\"," +
               "  \"passphrase\": \"" + Jackson.escapeText(PASSPHRASE_BASE64) + '"' +
               '}';
    }

    @Test
    void testPrivateKeyConversion() {
        final SshKeyCredential credential =
                new SshKeyCredential(credentialName("foo", "key-credential"), USERNAME,
                                     PUBLIC_KEY, PRIVATE_KEY, "mirror_encryption:foo");
        assertThat(credential.passphrase()).isEqualTo("bar");
    }

    public static String legacyPublicKey(String id) {
        return '{' +
               "  \"id\": \"" + id + "\"," +
               "  \"type\": \"public_key\"," +
               "  \"username\": \"trustin\"," +
               "  \"publicKey\": \"" + Jackson.escapeText(PUBLIC_KEY) + "\"," +
               "  \"privateKey\": \"" + Jackson.escapeText(PRIVATE_KEY) + "\"," +
               "  \"passphrase\": \"" + Jackson.escapeText(PASSPHRASE_BASE64) + '"' +
               '}';
    }

    public static class PasswordConfigValueConverter implements ConfigValueConverter {

        @Override
        public List<String> supportedPrefixes() {
            return ImmutableList.of("mirror_encryption");
        }

        @Override
        public String convert(String prefix, String value) {
            if ("foo".equals(value)) {
                return "bar";
            }
            throw new IllegalArgumentException("unsupported prefix: " + prefix + ", value: " + value);
        }
    }
}
