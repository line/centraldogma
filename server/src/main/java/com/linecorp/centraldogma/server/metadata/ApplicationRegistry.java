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
 * Holds an application map, a secret map and a certificate ID map for fast lookup.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public final class ApplicationRegistry implements HasWeight {

    static final String SECRET_PREFIX = "appToken-";

    /**
     * Applications which belong to this project.
     */
    private final Map<String, Application> appIds;

    /**
     * A mapping of secret and {@link Token#appId()}.
     */
    private final Map<String, String> secrets;

    /**
     * A mapping of certificate ID and {@link Certificate#appId()}.
     */
    private final Map<String, String> certificateIds;

    /**
     * Creates a new empty instance.
     */
    public ApplicationRegistry() {
        this(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of());
    }

    /**
     * Creates a new instance with the given application IDs, secrets, and certificateIds.
     */
    @JsonCreator
    public ApplicationRegistry(@JsonProperty("appIds") Map<String, Application> appIds,
                               @JsonProperty("secrets") Map<String, String> secrets,
                               @JsonProperty("certificateIds") Map<String, String> certificateIds) {
        this.appIds = requireNonNull(appIds, "appIds");
        this.secrets = requireNonNull(secrets, "secrets");
        this.certificateIds = requireNonNull(certificateIds, "certificateIds");
    }

    /**
     * Returns the applications.
     */
    @JsonProperty
    public Map<String, Application> appIds() {
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
     * Returns the {@link Application} that corresponds to the specified application ID.
     */
    public Application get(String appId) {
        final Application application = getOrDefault(appId, null);
        if (application != null) {
            return application;
        }
        throw new ApplicationNotFoundException("Application ID not found: " + appId);
    }

    /**
     * Returns the {@link Application} that corresponds to the specified application ID. {@code defaultValue} is
     * returned if there's no such application.
     */
    @Nullable
    public Application getOrDefault(String appId, @Nullable Application defaultValue) {
        requireNonNull(appId, "appId");
        final Application application = appIds.get(appId);
        if (application != null) {
            return application;
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
        throw new ApplicationNotFoundException("Secret not found: " + secret);
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
            final Application application = getOrDefault(appId, null);
            if (application instanceof Token) {
                return (Token) application;
            }
        }
        return defaultValue;
    }

    /**
     * Returns the {@link Certificate} that corresponds to the specified certificate ID.
     */
    public Certificate findByCertificateId(String certificateId) {
        final Certificate certificate = findByCertificateIdOrDefault(certificateId, null);
        if (certificate != null) {
            return certificate;
        }
        throw new ApplicationNotFoundException("Certificate ID not found: " + certificateId);
    }

    /**
     * Returns the {@link Certificate} that corresponds to the specified certificate ID.
     * {@code defaultValue} is returned if there's no such certificate ID.
     */
    @Nullable
    public Certificate findByCertificateIdOrDefault(String certificateId, @Nullable Certificate defaultValue) {
        requireNonNull(certificateId, "certificateId");
        final String appId = certificateIds.get(certificateId);
        if (appId != null) {
            final Application application = getOrDefault(appId, null);
            if (application instanceof Certificate) {
                return (Certificate) application;
            }
        }
        return defaultValue;
    }

    /**
     * Returns a new {@link ApplicationRegistry} which does not contain any secrets.
     */
    public ApplicationRegistry withoutSecret() {
        final Map<String, Application> appIds =
                appIds().values().stream()
                        .map(app -> app instanceof Token ? ((Token) app).withoutSecret() : app)
                        .collect(Collectors.toMap(Application::id, Function.identity()));
        return new ApplicationRegistry(appIds, ImmutableMap.of(), ImmutableMap.of());
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
