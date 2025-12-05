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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.centraldogma.server.CentralDogmaConfig.convertValue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.internal.common.util.CertificateUtil;

/**
 * A mutual TLS (mTLS) configuration for the Central Dogma server.
 */
public final class MtlsConfig {

    private static final MtlsConfig DISABLED = new MtlsConfig(false, ImmutableList.of());

    private final boolean enabled;
    private final List<String> caCertificateFiles;

    /**
     * Returns a disabled mTLS configuration.
     */
    public static MtlsConfig disabled() {
        return DISABLED;
    }

    /**
     * Creates a new instance.
     *
     * @param enabled whether mTLS is enabled
     * @param caCertificateFiles the list of CA certificate file paths
     */
    @JsonCreator
    public MtlsConfig(@JsonProperty("enabled") @Nullable Boolean enabled,
                      @JsonProperty("caCertificateFiles") @Nullable List<String> caCertificateFiles) {
        this.enabled = firstNonNull(enabled, false);
        this.caCertificateFiles = caCertificateFiles != null ? ImmutableList.copyOf(caCertificateFiles)
                                                             : ImmutableList.of();
    }

    /**
     * Returns whether mTLS is enabled.
     */
    @JsonProperty
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the list of CA certificate file paths.
     */
    @JsonProperty
    public List<String> caCertificateFiles() {
        return caCertificateFiles;
    }

    /**
     * Returns the list of CA certificates loaded from the configured certificate files.
     */
    public List<X509Certificate> caCertificates() {
        final List<X509Certificate> certificates = new ArrayList<>();
        for (String caCertFile : caCertificateFiles) {
            try (InputStream certInputStream = caCertInputStream(caCertFile)) {
                certificates.addAll(CertificateUtil.toX509Certificates(certInputStream));
            } catch (Exception e) {
                throw new RuntimeException("Failed to load CA certificate from " + caCertFile, e);
            }
        }
        return certificates;
    }

    private static InputStream caCertInputStream(String caCertFile) {
        final String converted = convertValue(caCertFile, "mtls.caCertificateFiles");
        if (converted == null) {
            throw new NullPointerException(
                    "mtls.caCertificateFiles" + '(' + caCertFile + ") is converted to null.");
        }
        return new ByteArrayInputStream(converted.getBytes());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("enabled", enabled)
                          .add("caCertificateFiles", caCertificateFiles)
                          .toString();
    }
}
