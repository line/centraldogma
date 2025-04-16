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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.centraldogma.server.CentralDogmaConfig.convertValue;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.opensaml.xmlsec.signature.support.SignatureConstants;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;

import com.linecorp.armeria.server.saml.SamlBindingProtocol;
import com.linecorp.armeria.server.saml.SamlEndpoint;
import com.linecorp.armeria.server.saml.SamlNameIdFormat;
import com.linecorp.centraldogma.internal.Jackson;

/**
 * Properties which are used to configure SAML authentication for Central Dogma server.
 * A user can specify them as the authentication property in the {@code dogma.json} as follows:
 * <pre>{@code
 * "authentication": {
 *     "factoryClassName": "com.linecorp.centraldogma.server.auth.saml.SamlAuthProviderFactory",
 *     "properties": {
 *         "entityId": "...the service provider ID...",
 *         "hostname": "dogma-example.linecorp.com",
 *         "signingKey": "...the name of signing key (optional)...",
 *         "encryptionKey": "...the name of encryption key (optional)...",
 *         "keyStore": {
 *             "type": "...the type of the keystore (optional)...",
 *             "path": "...the path where keystore file exists...",
 *             "password": "...the password of the keystore (optional)...",
 *             "keyPasswords": {
 *                 "signing": "...the password of the signing key...",
 *                 "encryption": "...the password of the encryption key..."
 *             },
 *             "signatureAlgorithm": "...the signature algorithm for signing and encryption (optional)..."
 *         },
 *         // Specify when your server uses different uri from the recipient of the assertion that
 *         // the IdP sends. // For example, if your server is behind a proxy, you need to specify
 *         // the uri of the proxy.
 *         "acs": {
 *             "endpoints": [{
 *                 "uri": "https://dogma-example.linecorp.com/saml/acs/post",
 *                 "binding": "HTTP_POST"
 *             }, {
 *                 "uri": "https://dogma-example.linecorp.com/saml/acs/redirect",
 *                 "binding": "HTTP_REDIRECT"
 *             }]
 *         },
 *         "idp": {
 *             "entityId": "...the identity provider ID...",
 *             "uri": "https://idp-example.linecorp.com/saml/sso",
 *             "binding": "HTTP_POST or HTTP_REDIRECT (optional)",
 *             "signingKey": "...the name of signing certificate (optional)...",
 *             "encryptionKey": "...the name of encryption certificate (optional)...",
 *             "subjectLoginNameIdFormat":
 *                  "...the name ID format of a subject which holds a login name (optional)...",
 *             "attributeLoginName": "...the attribute name which holds a login name (optional)..."
 *         }
 *     }
 * }
 * }</pre>
 */
final class SamlAuthProperties {
    /**
     * A default key name for signing.
     */
    private static final String DEFAULT_SIGNING_KEY = "signing";

    /**
     * A default key name for encryption.
     */
    private static final String DEFAULT_ENCRYPTION_KEY = "encryption";

    /**
     * An ID of this service provider.
     */
    private final String entityId;

    /**
     * A hostname of this service provider.
     */
    private final String hostname;

    /**
     * A key name which is used for signing. The default name is {@value DEFAULT_SIGNING_KEY}.
     */
    private final String signingKey;

    /**
     * A key name which is used for encryption. The default name is {@value DEFAULT_ENCRYPTION_KEY}.
     */
    private final String encryptionKey;

    /**
     * A configuration for the keystore.
     */
    private final KeyStore keyStore;

    /**
     * {@link SamlEndpoint}s of the assertion consumer service.
     */
    @Nullable
    private final Acs acs;

    /**
     * An identity provider configuration. A single identity provider is supported.
     */
    private final Idp idp;

    @JsonCreator
    SamlAuthProperties(
            @JsonProperty("entityId") String entityId,
            @JsonProperty("hostname") String hostname,
            @JsonProperty("signingKey") @Nullable String signingKey,
            @JsonProperty("encryptionKey") @Nullable String encryptionKey,
            @JsonProperty("keyStore") KeyStore keyStore,
            @JsonProperty("acs") @Nullable Acs acs,
            @JsonProperty("idp") Idp idp) {
        this.entityId = requireNonNull(entityId, "entityId");
        this.hostname = requireNonNull(hostname, "hostname");
        this.signingKey = firstNonNull(signingKey, DEFAULT_SIGNING_KEY);
        this.encryptionKey = firstNonNull(encryptionKey, DEFAULT_ENCRYPTION_KEY);
        this.keyStore = requireNonNull(keyStore, "keyStore");
        this.acs = acs;
        this.idp = requireNonNull(idp, "idp");
    }

    @JsonProperty
    String entityId() {
        return entityId;
    }

    @JsonProperty
    String hostname() {
        return hostname;
    }

    @JsonProperty
    String signingKey() {
        return signingKey;
    }

    @JsonProperty
    String encryptionKey() {
        return encryptionKey;
    }

    @JsonProperty
    KeyStore keyStore() {
        return keyStore;
    }

    @Nullable
    @JsonProperty
    Acs acs() {
        return acs;
    }

    @JsonProperty
    Idp idp() {
        return idp;
    }

    @Override
    public String toString() {
        try {
            return Jackson.writeValueAsPrettyString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static class KeyStore {
        /**
         * A default signature algorithm.
         */
        private static final String DEFAULT_SIGNATURE_ALGORITHM = SignatureConstants.ALGO_ID_SIGNATURE_RSA;

        /**
         * A type of the keystore. The default value is retrieved from
         * {@code java.security.KeyStore.getDefaultType()}.
         */
        private final String type;

        /**
         * A path of the keystore.
         */
        private final String path;

        /**
         * A password of the keystore. The empty string is used by default.
         */
        @Nullable
        private final String password;

        /**
         * A map of the key name and its password.
         */
        @Nullable
        private final Map<String, String> keyPasswords;

        /**
         * A signature algorithm for signing and encryption. The default algorithm is
         * {@value DEFAULT_SIGNATURE_ALGORITHM}.
         *
         * @see SignatureConstants for more information about the signature algorithm
         */
        private final String signatureAlgorithm;

        @JsonCreator
        KeyStore(@JsonProperty("type") @Nullable String type,
                 @JsonProperty("path") String path,
                 @JsonProperty("password") @Nullable String password,
                 @JsonProperty("keyPasswords") @Nullable Map<String, String> keyPasswords,
                 @JsonProperty("signatureAlgorithm") @Nullable String signatureAlgorithm) {
            this.type = firstNonNull(type, java.security.KeyStore.getDefaultType());
            this.path = requireNonNull(path, "path");
            this.password = password;
            this.keyPasswords = keyPasswords;
            this.signatureAlgorithm = firstNonNull(signatureAlgorithm, DEFAULT_SIGNATURE_ALGORITHM);
        }

        @JsonProperty
        String type() {
            return type;
        }

        @JsonProperty
        String path() {
            return path;
        }

        @Nullable
        @JsonProperty
        String password() {
            return convertValue(password, "keyStore.password");
        }

        @JsonProperty
        Map<String, String> keyPasswords() {
            return sanitizePasswords(keyPasswords);
        }

        private static Map<String, String> sanitizePasswords(@Nullable Map<String, String> keyPasswords) {
            if (keyPasswords == null) {
                return ImmutableMap.of();
            }
            final ImmutableMap.Builder<String, String> builder = new Builder<>();
            keyPasswords.forEach((key, password) -> builder.put(key, firstNonNull(
                    convertValue(password, "keyStore.keyPasswords"), "")));
            return builder.build();
        }

        @JsonProperty
        String signatureAlgorithm() {
            return signatureAlgorithm;
        }
    }

    static class Acs {

        private final List<SamlEndpoint> endpoints;

        @JsonCreator
        Acs(@JsonProperty("endpoints") List<SamlEndpoint> endpoints) {
            this.endpoints = requireNonNull(endpoints, "endpoints");
        }

        @JsonProperty
        List<SamlEndpoint> endpoints() {
            return endpoints;
        }
    }

    static class Idp {
        /**
         * An ID of the identity provider.
         */
        private final String entityId;

        /**
         * A location of the single sign-on service.
         */
        private final String uri;

        /**
         * A name of a {@link SamlBindingProtocol}. The default name is the
         * {@link SamlBindingProtocol#HTTP_POST}.
         */
        private final SamlBindingProtocol binding;

        /**
         * A certificate name which is used for signing. The default name is the {@link #entityId()}.
         */
        private final String signingKey;

        /**
         * A certificate name which is used for encryption. The default name is the {@link #entityId()}.
         */
        private final String encryptionKey;

        /**
         * A name ID format of a subject which holds a login name of an authenticated user. If both
         * {@code subjectLoginNameIdFormat} and {@code attributeLoginName} are {@code null},
         * {@code urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress} is set by default.
         */
        @Nullable
        private final String subjectLoginNameIdFormat;

        /**
         * An attribute name which holds a login name of an authenticated user.
         */
        @Nullable
        private final String attributeLoginName;

        @JsonCreator
        Idp(@JsonProperty("entityId") String entityId,
            @JsonProperty("uri") String uri,
            @JsonProperty("binding") @Nullable String binding,
            @JsonProperty("signingKey") @Nullable String signingKey,
            @JsonProperty("encryptionKey") @Nullable String encryptionKey,
            @JsonProperty("subjectLoginNameIdFormat") @Nullable String subjectLoginNameIdFormat,
            @JsonProperty("attributeLoginName") @Nullable String attributeLoginName) {
            this.entityId = requireNonNull(entityId, "entityId");
            this.uri = requireNonNull(uri, "uri");
            this.binding = binding != null ? SamlBindingProtocol.valueOf(binding)
                                           : SamlBindingProtocol.HTTP_POST;
            this.signingKey = firstNonNull(signingKey, entityId);
            this.encryptionKey = firstNonNull(encryptionKey, entityId);

            if (subjectLoginNameIdFormat == null && attributeLoginName == null) {
                this.subjectLoginNameIdFormat = SamlNameIdFormat.EMAIL.urn();
                this.attributeLoginName = null;
            } else {
                this.subjectLoginNameIdFormat = subjectLoginNameIdFormat;
                this.attributeLoginName = attributeLoginName;
            }
        }

        @JsonProperty
        String entityId() {
            return entityId;
        }

        @JsonProperty
        String uri() {
            return uri;
        }

        @JsonProperty
        String binding() {
            return binding.name();
        }

        @JsonProperty
        String signingKey() {
            return signingKey;
        }

        @JsonProperty
        String encryptionKey() {
            return encryptionKey;
        }

        @Nullable
        @JsonProperty
        String subjectLoginNameIdFormat() {
            return subjectLoginNameIdFormat;
        }

        @Nullable
        @JsonProperty
        String attributeLoginName() {
            return attributeLoginName;
        }

        SamlEndpoint endpoint() {
            switch (binding) {
                case HTTP_POST:
                    return SamlEndpoint.ofHttpPost(uri);
                case HTTP_REDIRECT:
                    return SamlEndpoint.ofHttpRedirect(uri);
                default:
                    throw new IllegalStateException("Failed to get an endpoint of the IdP: " + entityId);
            }
        }
    }
}
