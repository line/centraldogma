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
package com.linecorp.centraldogma.server;

import static java.nio.file.Files.createTempFile;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.auth.MtlsConfig;

class ConfigDeserializationTest {

    @TempDir
    static Path tempDir;

    @Test
    void tlsConfig() throws Exception {
        final Path certFile = createTempFile(tempDir, "", "");
        Files.write(certFile, "foo".getBytes(StandardCharsets.UTF_8));
        final Path keyFile = createTempFile(tempDir, "", "");
        Files.write(keyFile, "bar".getBytes(StandardCharsets.UTF_8));

        final String cert = Jackson.escapeText(certFile.toAbsolutePath().toString());
        final String key = Jackson.escapeText(keyFile.toAbsolutePath().toString());

        final String jsonConfig = String.format("{\"tls\": {" +
                                                "\"keyCertChainFile\": \"%s\", " +
                                                "\"keyFile\": \"%s\", " +
                                                "\"keyPassword\": \"sesame\"" +
                                                "}}", cert, key);
        checkContent(jsonConfig);
    }

    @Test
    void tlsConfigFilePrefix() throws Exception {
        final Path certFile = createTempFile(tempDir, "", "");
        Files.write(certFile, "foo".getBytes(StandardCharsets.UTF_8));

        final String cert = Jackson.escapeText("file:" + certFile.toAbsolutePath());
        final String jsonConfig = String.format("{\"tls\": {" +
                                                "\"keyCertChain\": \"%s\", " +
                                                "\"key\": \"plaintext:bar\", " +
                                                "\"keyPassword\": \"sesame\"" +
                                                "}}", cert);
        checkContent(jsonConfig);
    }

    @Test
    void tlsConfigConfigValueConverter() throws Exception {
        final String jsonConfig = "{\"tls\": {" +
                                  "\"keyCertChain\": \"encryption:chain\", " +
                                  "\"key\": \"encryption:key\", " +
                                  "\"keyPassword\": \"encryption:password\"" +
                                  "}}";
        checkContent(jsonConfig);
    }

    @Test
    void mtlsConfigMultipleCaCerts() throws Exception {
        final Path caCertFile1 = createTempFile(tempDir, "", "");
        Files.write(caCertFile1, "ca-cert-1".getBytes(StandardCharsets.UTF_8));
        final Path caCertFile2 = createTempFile(tempDir, "", "");
        Files.write(caCertFile2, "ca-cert-2".getBytes(StandardCharsets.UTF_8));

        final String caCert1 = Jackson.escapeText("file:" + caCertFile1.toAbsolutePath());
        final String caCert2 = Jackson.escapeText("file:" + caCertFile2.toAbsolutePath());
        final String jsonConfig = String.format("{\"mtls\": {" +
                                                "\"enabled\": true, " +
                                                "\"caCertificateFiles\": [\"%s\", \"%s\"]" +
                                                "}}", caCert1, caCert2);

        final ParentMtlsConfig parentMtlsConfig = Jackson.readValue(jsonConfig, ParentMtlsConfig.class);
        assertThat(parentMtlsConfig.mtlsConfig.enabled()).isTrue();
        assertThat(parentMtlsConfig.mtlsConfig.caCertificateFiles()).hasSize(2);
    }

    @Test
    void mtlsConfigCaCertificates() throws Exception {
        // Generate a self-signed certificate for testing
        final SelfSignedCertificate ssc = new SelfSignedCertificate();
        final Path caCertFile = ssc.certificate().toPath();

        final String caCert = Jackson.escapeText("file:" + caCertFile.toAbsolutePath());
        final String jsonConfig = String.format("{\"mtls\": {" +
                                                "\"enabled\": true, " +
                                                "\"caCertificateFiles\": [\"%s\"]" +
                                                "}}", caCert);

        final ParentMtlsConfig parentMtlsConfig = Jackson.readValue(jsonConfig, ParentMtlsConfig.class);

        assertThat(parentMtlsConfig.mtlsConfig.enabled()).isTrue();
        assertThat(parentMtlsConfig.mtlsConfig.caCertificateFiles()).hasSize(1);

        final List<X509Certificate> certificates = parentMtlsConfig.mtlsConfig.caCertificates();
        assertThat(certificates).isNotEmpty();
        assertThat(certificates.get(0)).isInstanceOf(X509Certificate.class);
        assertThat(certificates.get(0)).isEqualTo(ssc.cert());
        ssc.delete();
    }

    private static void checkContent(String jsonConfig) throws IOException {
        final TlsConfig tlsConfig = Jackson.readValue(jsonConfig, ParentConfig.class).tlsConfig;

        readStream(tlsConfig.keyCertChainInputStream(), "foo");
        readStream(tlsConfig.keyInputStream(), "bar");
        assertThat(tlsConfig.keyPassword()).isEqualTo("sesame");
    }

    private static void readStream(InputStream inputStream, String content) throws IOException {
        try (InputStream inputStream0 = inputStream;
             BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream0))) {
            assertThat(bufferedReader.readLine()).isEqualTo(content);
        }
    }

    public static class KeyConfigValueConverter implements ConfigValueConverter {

        @Override
        public List<String> supportedPrefixes() {
            return ImmutableList.of("encryption");
        }

        @Override
        public String convert(String prefix, String value) {
            if ("chain".equals(value)) {
                return "foo";
            }
            if ("key".equals(value)) {
                return "bar";
            }
            if ("password".equals(value)) {
                return "sesame";
            }
            throw new IllegalArgumentException("unsupported prefix: " + prefix + ", value: " + value);
        }
    }

    static class ParentConfig {
        private final TlsConfig tlsConfig;

        @JsonCreator
        ParentConfig(@JsonProperty("tls") TlsConfig tlsConfig) {
            this.tlsConfig = tlsConfig;
        }
    }

    static class ParentMtlsConfig {
        private final MtlsConfig mtlsConfig;

        @JsonCreator
        ParentMtlsConfig(@JsonProperty("mtls") MtlsConfig mtlsConfig) {
            this.mtlsConfig = mtlsConfig;
        }
    }
}
