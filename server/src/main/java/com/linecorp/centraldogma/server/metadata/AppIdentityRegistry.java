/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.centraldogma.server.metadata;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.server.storage.repository.HasWeight;

/**
 * Holds an app identity map, a secret map and a certificate ID map for fast lookup.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public final class AppIdentityRegistry implements HasWeight {

    static final String SECRET_PREFIX = "appToken-";

    /**
     * App identities which belong to this project.
     */
    private final Map<String, AppIdentity> appIds;

    /**
     * A mapping of secret and {@link Token#appId()}.
     */
    private final Map<String, String> secrets;

    /**
     * A mapping of certificate ID and {@link CertificateAppIdentity#appId()}.
     */
    private final Map<String, String> certificateIds;

    /**
     * Creates a new empty instance.
     */
    public AppIdentityRegistry() {
        this(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of());
    }

    /**
     * Creates a new instance with the given appIDs, secrets, and certificateIds.
     */
    @JsonCreator
    public AppIdentityRegistry(@JsonProperty("appIds") Map<String, AppIdentity> appIds,
                               @JsonProperty("secrets") Map<String, String> secrets,
                               @JsonProperty("certificateIds") @Nullable Map<String, String> certificateIds) {
        this.appIds = requireNonNull(appIds, "appIds");
        this.secrets = requireNonNull(secrets, "secrets");
        if (certificateIds == null) {
            this.certificateIds = ImmutableMap.of();
        } else {
            this.certificateIds = ImmutableMap.copyOf(certificateIds);
        }
    }

    /**
     * Returns the app identities.
     */
    @JsonProperty
    public Map<String, AppIdentity> appIds() {
        return appIds;
    }

    /**
     * Returns the secrets.
     */
    @JsonProperty
    public Map<String, String> secrets() {
        return secrets;
    }

    /**
     * Returns the certificate IDs.
     */
    @JsonProperty
    public Map<String, String> certificateIds() {
        return certificateIds;
    }

    /**
     * Returns the {@link AppIdentity} that corresponds to the specified application ID.
     */
    public AppIdentity get(String appId) {
        final AppIdentity appIdentity = getOrDefault(appId, null);
        if (appIdentity != null) {
            return appIdentity;
        }
        throw new AppIdentityNotFoundException("App identity not found: " + appId);
    }

    /**
     * Returns the {@link AppIdentity} that corresponds to the specified application ID. {@code defaultValue} is
     * returned if there's no such app identity.
     */
    @Nullable
    public AppIdentity getOrDefault(String appId, @Nullable AppIdentity defaultValue) {
        requireNonNull(appId, "appId");
        final AppIdentity appIdentity = appIds.get(appId);
        if (appIdentity != null) {
            return appIdentity;
        }
        return defaultValue;
    }

    /**
     * Returns the {@link Token} that corresponds to the specified secret.
     */
    public Token findBySecret(String secret) {
        final Token token = findBySecretOrDefault(secret, null);
        if (token != null) {
            return token;
        }
        throw new AppIdentityNotFoundException("Secret not found: " + secret);
    }

    /**
     * Returns the {@link Token} that corresponds to the specified secret. {@code defaultValue} is returned
     * if there's no such secret.
     */
    @Nullable
    public Token findBySecretOrDefault(String secret, @Nullable Token defaultValue) {
        requireNonNull(secret, "secret");
        if (!secret.startsWith(SECRET_PREFIX)) {
            return defaultValue;
        }
        final String appId = secrets.get(secret);
        if (appId != null) {
            final AppIdentity appIdentity = getOrDefault(appId, null);
            if (appIdentity instanceof Token) {
                return (Token) appIdentity;
            }
        }
        return defaultValue;
    }

    /**
     * Returns the {@link CertificateAppIdentity} that corresponds to the specified certificate ID.
     */
    public CertificateAppIdentity findByCertificateId(String certificateId) {
        final CertificateAppIdentity certificate = findByCertificateIdOrDefault(certificateId, null);
        if (certificate != null) {
            return certificate;
        }
        throw new AppIdentityNotFoundException("Certificate ID not found: " + certificateId);
    }

    /**
     * Returns the {@link CertificateAppIdentity} that corresponds to the specified certificate ID.
     * {@code defaultValue} is returned if there's no such certificate ID.
     */
    @Nullable
    public CertificateAppIdentity findByCertificateIdOrDefault(
            String certificateId, @Nullable CertificateAppIdentity defaultValue) {
        requireNonNull(certificateId, "certificateId");
        final String appId = certificateIds.get(certificateId);
        if (appId != null) {
            final AppIdentity appIdentity = getOrDefault(appId, null);
            if (appIdentity instanceof CertificateAppIdentity) {
                return (CertificateAppIdentity) appIdentity;
            }
        }
        return defaultValue;
    }

    /**
     * Returns a new {@link AppIdentityRegistry} which does not contain any secrets.
     */
    public AppIdentityRegistry withoutSecret() {
        final Map<String, AppIdentity> appIds =
                appIds().values().stream()
                        .map(app -> app instanceof Token ? ((Token) app).withoutSecret() : app)
                        .collect(Collectors.toMap(AppIdentity::id, Function.identity()));
        return new AppIdentityRegistry(appIds, ImmutableMap.of(), ImmutableMap.of());
    }

    @Override
    public int weight() {
        int weight = 0;
        weight += secrets.size();
        for (Entry<String, String> entry : secrets.entrySet()) {
            weight += entry.getKey().length();
            weight += entry.getValue().length();
        }
        weight += certificateIds.size();
        for (Entry<String, String> entry : certificateIds.entrySet()) {
            weight += entry.getKey().length();
            weight += entry.getValue().length();
        }
        return weight;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("appIds", appIds())
                          .add("secrets.size", secrets().size())
                          .add("certificateIds", certificateIds())
                          .toString();
    }

    /**
     * Returns {@code true} if the specified secret is valid.
     */
    public static boolean isValidSecret(@Nullable String secret) {
        return secret != null && secret.startsWith(SECRET_PREFIX);
    }

    /**
     * Throws an {@link IllegalArgumentException} if the specified secret is not valid.
     */
    public static void validateSecret(String secret) {
        checkArgument(isValidSecret(secret),
                      "invalid secret: " + secret +
                      " (secret must start with '" + SECRET_PREFIX + "')");
    }
}
