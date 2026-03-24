/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.centraldogma.it;

import java.security.cert.X509Certificate;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.jspecify.annotations.Nullable;

import com.linecorp.centraldogma.server.auth.ApplicationCertificateIdExtractor;

/**
 * A test {@link ApplicationCertificateIdExtractor} that extracts the CN from the certificate
 * and prepends {@code "test-"} to verify that the SPI-loaded extractor is used.
 */
public final class TestCertificateIdExtractor implements ApplicationCertificateIdExtractor {

    @Nullable
    @Override
    public String extractCertificateId(X509Certificate certificate) {
        try {
            final LdapName ldapName = new LdapName(certificate.getSubjectX500Principal().getName());
            for (Rdn rdn : ldapName.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return "test-" + rdn.getValue().toString();
                }
            }
        } catch (InvalidNameException e) {
            // ignore
        }
        return null;
    }
}
