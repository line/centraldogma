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

import static java.util.Objects.requireNonNull;

import java.security.cert.X509Certificate;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.Authorizer;
import com.linecorp.centraldogma.server.auth.ApplicationCertificateIdExtractor;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.metadata.ApplicationNotFoundException;
import com.linecorp.centraldogma.server.metadata.ApplicationCertificate;
import com.linecorp.centraldogma.server.metadata.UserWithApplication;

import io.netty.handler.ssl.OpenSslSession;

/**
 * An authorizer which extracts certificate ID from mTLS peer certificate and validates it.
 */
public final class ApplicationCertificateAuthorizer implements Authorizer<HttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationCertificateAuthorizer.class);

    // TODO(minwoox): Make it configurable via SPI.
    private static final ApplicationCertificateIdExtractor ID_EXTRACTOR = SpiffeIdExtractor.INSTANCE;

    private final Function<String, ApplicationCertificate> certificateLookupFunc;

    public ApplicationCertificateAuthorizer(Function<String, ApplicationCertificate> certificateLookupFunc) {
        this.certificateLookupFunc = requireNonNull(certificateLookupFunc, "certificateLookupFunc");
    }

    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest data) {
        final SSLSession sslSession = ctx.sslSession();
        if (sslSession == null) {
            return UnmodifiableFuture.completedFuture(false);
        }

        if (sslSession instanceof OpenSslSession && !((OpenSslSession) sslSession).hasPeerCertificates()) {
            return UnmodifiableFuture.completedFuture(false);
        }

        final java.security.cert.Certificate[] peerCertificates;
        try {
            peerCertificates = sslSession.getPeerCertificates();
        } catch (SSLPeerUnverifiedException e) {
            // TODO(minwoox): Fix not to raise an exception for no peer certificate.
            return UnmodifiableFuture.completedFuture(false);
        }

        if (peerCertificates.length == 0) {
            return UnmodifiableFuture.completedFuture(false);
        }

        String certificateId = null;
        for (java.security.cert.Certificate peerCert : peerCertificates) {
            logger.trace("Peer certificate: addr={}, cert={}", ctx.clientAddress(), peerCert);
            if (!(peerCert instanceof X509Certificate)) {
                continue;
            }
            final X509Certificate x509Certificate = (X509Certificate) peerCert;
            if (x509Certificate.getBasicConstraints() != -1) {
                logger.trace("Skipping CA certificate: addr={}, cert={}", ctx.clientAddress(), x509Certificate);
                continue;
            }

            certificateId = ID_EXTRACTOR.extractCertificateId(x509Certificate);
            if (certificateId != null) {
                break;
            }
        }

        if (certificateId == null) {
            logger.trace("No certificateId found in certificate: addr={}", ctx.clientAddress());
            return UnmodifiableFuture.completedFuture(false);
        }

        try {
            final ApplicationCertificate certificate = certificateLookupFunc.apply(certificateId);
            if (certificate != null && certificate.isActive()) {
                final String appId = certificate.appId();
                ctx.logBuilder().authenticatedUser("app/" + appId + "/cert");
                final UserWithApplication user = new UserWithApplication(certificate);
                AuthUtil.setCurrentUser(ctx, user);
                HttpApiUtil.setVerboseResponses(ctx, user);
                return UnmodifiableFuture.completedFuture(true);
            }
            return UnmodifiableFuture.completedFuture(false);
        } catch (Throwable cause) {
            final Throwable peeled = Exceptions.peel(cause);
            if (peeled instanceof IllegalArgumentException ||
                peeled instanceof ApplicationNotFoundException) {
                // Do not log the cause.
                logger.debug("Failed to authorize certificate: certificateId={}, addr={}",
                             certificateId, ctx.clientAddress());
            } else {
                logger.warn("Failed to authorize certificate: certificateId={}, addr={}",
                            certificateId, ctx.clientAddress(), cause);
            }
            return UnmodifiableFuture.completedFuture(false);
        }
    }
}
