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

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.server.storage.repository.HasWeight;

/**
 * Holds a token map and a secret map for fast lookup.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public final class Tokens implements HasWeight {

    static final String SECRET_PREFIX = "appToken-";

    /**
     * AppIdentities which belong to this project.
     */
    private final Map<String, AppIdentity> appIds;

    /**
     * A mapping of secret and {@link Token#id()}.
     */
    private final Map<String, String> secrets;

    /**
     * A mapping of certificate ID and application ID.
     */
    private final Map<String, String> certificateIds;

    /**
     * Creates a new empty instance.
     */
    public Tokens() {
        this(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of());
    }

    /**
     * Creates a new instance with the given application IDs, secrets, and certificate IDs.
     */
    @JsonCreator
    public Tokens(@JsonProperty("appIds") Map<String, AppIdentity> appIds,
                  @JsonProperty("secrets") Map<String, String> secrets,
                  @JsonProperty("certificateIds") @Nullable Map<String, String> certificateIds) {
        this.appIds = ImmutableMap.copyOf(requireNonNull(appIds, "appIds"));
        this.secrets = ImmutableMap.copyOf(requireNonNull(secrets, "secrets"));
        if (certificateIds != null) {
            this.certificateIds = ImmutableMap.copyOf(certificateIds);
        } else {
            this.certificateIds = ImmutableMap.of();
        }
    }

    /**
     * Returns the application {@link AppIdentity}s.
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
     * Returns the {@link Token} that corresponds to the specified application ID.
     */
    public Token get(String appId) {
        final Token token = getOrDefault(appId, null);
        if (token != null) {
            return token;
        }
        throw new TokenNotFoundException("Application ID not found: " + appId);
    }

    /**
     * Returns the {@link Token} that corresponds to the specified application ID. {@code defaultValue} is
     * returned if there's no such application.
     */
    @Nullable
    public Token getOrDefault(String appId, @Nullable Token defaultValue) {
        requireNonNull(appId, "appId");
        final AppIdentity appIdentity = appIds.get(appId);
        if (appIdentity == null || appIdentity.type() != AppIdentityType.TOKEN) {
            return defaultValue;
        }
        return (Token) appIdentity;
    }

    /**
     * Returns the {@link Token} that corresponds to the specified secret.
     */
    public Token findBySecret(String secret) {
        final Token token = findBySecretOrDefault(secret, null);
        if (token != null) {
            return token;
        }
        throw new TokenNotFoundException("Secret not found: " + secret);
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
            return getOrDefault(appId, defaultValue);
        }
        return defaultValue;
    }

    /**
     * Returns a new {@link Tokens} which does not contain any secrets.
     */
    public Tokens withoutSecret() {
        final Map<String, AppIdentity> appIds =
                appIds().values().stream()
                        .map(appId -> {
                            if (appId.type() != AppIdentityType.TOKEN) {
                                return appId;
                            }
                            return ((Token) appId).withoutSecret();
                        })
                        .collect(Collectors.toMap(AppIdentity::id, Function.identity()));
        return new Tokens(appIds, ImmutableMap.of(), certificateIds);
    }

    @Override
    public int weight() {
        int weight = 0;
        weight += secrets.size();
        for (Entry<String, String> entry : secrets.entrySet()) {
            weight += entry.getKey().length();
            weight += entry.getValue().length();
        }

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
