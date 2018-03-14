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

package com.linecorp.centraldogma.server.internal.metadata;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Map;
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

import com.linecorp.centraldogma.server.internal.admin.service.TokenNotFoundException;

/**
 * Holds a token map and a secret map for fast lookup.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public final class Tokens {

    static final String SECRET_PREFIX = "appToken-";

    /**
     * Tokens which belong to this project.
     */
    private final Map<String, Token> appIds;

    /**
     * A mapping of secret and {@link Token#id()}.
     */
    private final Map<String, String> secrets;

    public Tokens() {
        this(ImmutableMap.of(), ImmutableMap.of());
    }

    @JsonCreator
    public Tokens(@JsonProperty("appIds") Map<String, Token> appIds,
                  @JsonProperty("secrets") Map<String, String> secrets) {
        this.appIds = requireNonNull(appIds, "appIds");
        this.secrets = requireNonNull(secrets, "secrets");
    }

    @JsonProperty
    public Map<String, Token> appIds() {
        return appIds;
    }

    @JsonProperty
    public Map<String, String> secrets() {
        return secrets;
    }

    public Token get(String appId) {
        final Token token = getOrDefault(appId, null);
        if (token != null) {
            return token;
        }
        throw new TokenNotFoundException("Application ID not found: " + appId);
    }

    public Token getOrDefault(String appId, @Nullable Token defaultValue) {
        requireNonNull(appId, "appId");
        final Token token = appIds.get(appId);
        if (token != null) {
            return token;
        }
        return defaultValue;
    }

    public Token findBySecret(String secret) {
        final Token token = findBySecretOrDefault(secret, null);
        if (token != null) {
            return token;
        }
        throw new TokenNotFoundException("Secret not found: " + secret);
    }

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

    public Tokens withoutSecret() {
        final Map<String, Token> appIds =
                appIds().values().stream()
                        .map(Token::withoutSecret)
                        .collect(Collectors.toMap(Token::id, Function.identity()));
        return new Tokens(appIds, ImmutableMap.of());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("appIds", appIds())
                          .add("secrets", secrets())
                          .toString();
    }

    public static boolean isValidSecret(String secret) {
        return secret != null && secret.startsWith(SECRET_PREFIX);
    }

    public static void validateSecret(String secret) {
        checkArgument(isValidSecret(secret),
                      "invalid secret: " + secret +
                      " (secret must start with '" + SECRET_PREFIX + "')");
    }
}
