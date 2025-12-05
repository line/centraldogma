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

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.server.auth.CertificateIdExtractor;

enum SpiffeIdExtractor implements CertificateIdExtractor {

    INSTANCE;

    private static final Logger logger = LoggerFactory.getLogger(SpiffeIdExtractor.class);

    @Nullable
    @Override
    public String extractCertificateId(X509Certificate certificate) {
        try {
            final Collection<List<?>> subjectAlternativeNames = certificate.getSubjectAlternativeNames();
            if (subjectAlternativeNames == null) {
                return null;
            }

            // We're looking for type 6 (URI)
            for (List<?> san : subjectAlternativeNames) {
                if (san.size() >= 2) {
                    final Integer type = (Integer) san.get(0);
                    if (type != null && type == 6) { // URI type
                        final Object value = san.get(1);
                        if (value instanceof String) {
                            final String uri = (String) value;
                            if (uri.startsWith("spiffe://")) {
                                return uri.substring(9); // Remove "spiffe://"
                            }
                        }
                    }
                }
            }
        } catch (CertificateParsingException e) {
            logger.trace("Failed to parse certificate SAN", e);
        }
        return null;
    }
}
