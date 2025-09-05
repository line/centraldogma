/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.centraldogma.server.auth.saml;

import static com.google.common.base.Preconditions.checkState;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;

import org.opensaml.security.credential.CredentialResolver;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.server.saml.KeyStoreCredentialResolverBuilder;
import com.linecorp.armeria.server.saml.SamlServiceProvider;
import com.linecorp.armeria.server.saml.SamlServiceProviderBuilder;
import com.linecorp.centraldogma.server.auth.AuthConfig;
import com.linecorp.centraldogma.server.auth.AuthProvider;
import com.linecorp.centraldogma.server.auth.AuthProviderFactory;
import com.linecorp.centraldogma.server.auth.AuthProviderParameters;
import com.linecorp.centraldogma.server.auth.saml.SamlAuthProperties.Acs;
import com.linecorp.centraldogma.server.auth.saml.SamlAuthProperties.Idp;
import com.linecorp.centraldogma.server.auth.saml.SamlAuthProperties.KeyStore;

/**
 * A factory for creating an OpenSAML based {@link AuthProvider}.
 */
public final class SamlAuthProviderFactory implements AuthProviderFactory {
    @Override
    public AuthProvider create(AuthProviderParameters parameters) {
        final SamlAuthProperties properties = getProperties(parameters.authConfig());
        try {
            final KeyStore ks = properties.keyStore();
            final Idp idp = properties.idp();
            final SamlServiceProviderBuilder builder = SamlServiceProvider.builder();
            builder.entityId(properties.entityId())
                   .hostname(properties.hostname())
                   .signingKey(properties.signingKey())
                   .encryptionKey(properties.encryptionKey())
                   .authorizer(parameters.authorizer())
                   .ssoHandler(new SamlAuthSsoHandler(
                           parameters.sessionIdGenerator(),
                           parameters.loginSessionPropagator(),
                           Duration.ofMillis(parameters.authConfig().sessionTimeoutMillis()),
                           parameters.authConfig().loginNameNormalizer(),
                           properties.idp().subjectLoginNameIdFormat(),
                           properties.idp().attributeLoginName(),
                           parameters.tlsEnabled()))
                   .credentialResolver(credentialResolver(ks))
                   .signatureAlgorithm(ks.signatureAlgorithm())
                   .idp()
                   .entityId(idp.entityId())
                   .ssoEndpoint(idp.endpoint())
                   .signingKey(idp.signingKey())
                   .encryptionKey(idp.encryptionKey());
            final Acs acs = properties.acs();
            if (acs != null && !acs.endpoints().isEmpty()) {
                acs.endpoints().forEach(builder::acs);
            }

            return new SamlAuthProvider(builder.build(), parameters);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create " +
                                            SamlAuthProvider.class.getSimpleName(), e);
        }
    }

    private static SamlAuthProperties getProperties(AuthConfig authConfig) {
        try {
            final SamlAuthProperties p = authConfig.properties(SamlAuthProperties.class);
            checkState(p != null, "authentication properties are not specified");
            return p;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to get properties from " +
                                               AuthConfig.class.getSimpleName(), e);
        }
    }

    private static CredentialResolver credentialResolver(KeyStore keyStore)
            throws IOException, GeneralSecurityException {
        final KeyStoreCredentialResolverBuilder builder;
        final String path = keyStore.path();
        final File file = new File(path);
        if (file.isFile()) {
            builder = new KeyStoreCredentialResolverBuilder(file);
        } else {
            builder = new KeyStoreCredentialResolverBuilder(
                    SamlAuthProviderFactory.class.getClassLoader(), path);
        }

        builder.type(keyStore.type())
               .password(keyStore.password())
               .keyPasswords(keyStore.keyPasswords());
        return builder.build();
    }
}
