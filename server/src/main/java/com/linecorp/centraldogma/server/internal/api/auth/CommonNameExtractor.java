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
package com.linecorp.centraldogma.server.internal.api.auth;

import java.security.cert.X509Certificate;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.server.auth.ApplicationCertificateIdExtractor;

enum CommonNameExtractor implements ApplicationCertificateIdExtractor {

    INSTANCE;

    private static final Logger logger = LoggerFactory.getLogger(CommonNameExtractor.class);

    @Nullable
    @Override
    public String extractCertificateId(X509Certificate certificate) {
        try {
            final LdapName ldapName = new LdapName(certificate.getSubjectX500Principal().getName());
            for (Rdn rdn : ldapName.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return rdn.getValue().toString();
                }
            }
        } catch (InvalidNameException e) {
            logger.trace("Failed to parse certificate subject DN", e);
        }
        return null;
    }
}
