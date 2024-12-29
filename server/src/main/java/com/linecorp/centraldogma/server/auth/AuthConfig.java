/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.server.auth;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache.validateCacheSpec;
import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;

import com.linecorp.centraldogma.internal.Jackson;

/**
 * An authentication configuration for the Central Dogma server.
 */
public final class AuthConfig implements AuthConfigSpec {

    private final AuthProviderFactory factory;

    private final Set<String> systemAdministrators;
    private final boolean caseSensitiveLoginNames;

    private final String sessionCacheSpec;
    private final long sessionTimeoutMillis;
    private final String sessionValidationSchedule;

    @Nullable
    private final JsonNode properties;

    /**
     * Creates a new instance.
     *
     * @param factoryClassName the fully-qualified class name of the {@link AuthProviderFactory}
     * @param systemAdministrators the login names of the system administrators
     * @param caseSensitiveLoginNames the flag whether case-sensitive matching is performed when login names
     *                                are compared
     * @param sessionCacheSpec the cache specification which determines the capacity and behavior of
     *                         the cache for {@link Session} of the server
     * @param sessionTimeoutMillis the timeout for {@link Session}s of the server
     * @param sessionValidationSchedule the schedule for validating sessions
     * @param properties the additional properties which are used in the factory
     */
    @JsonCreator
    public AuthConfig(
            @JsonProperty("factoryClassName") String factoryClassName,
            @JsonProperty("systemAdministrators") @Nullable Set<String> systemAdministrators,
            @JsonProperty("caseSensitiveLoginNames") @Nullable Boolean caseSensitiveLoginNames,
            @JsonProperty("sessionCacheSpec") @Nullable String sessionCacheSpec,
            @JsonProperty("sessionTimeoutMillis") @Nullable Long sessionTimeoutMillis,
            @JsonProperty("sessionValidationSchedule") @Nullable String sessionValidationSchedule,
            @JsonProperty("properties") @Nullable JsonNode properties) throws Exception {
        this((AuthProviderFactory) AuthConfig.class
                     .getClassLoader()
                     .loadClass(requireNonNull(factoryClassName, "factoryClassName"))
                     .getDeclaredConstructor().newInstance(),
             systemAdministrators != null ? ImmutableSet.copyOf(systemAdministrators) : ImmutableSet.of(),
             firstNonNull(caseSensitiveLoginNames, false),
             firstNonNull(sessionCacheSpec, DEFAULT_SESSION_CACHE_SPEC),
             firstNonNull(sessionTimeoutMillis, DEFAULT_SESSION_TIMEOUT_MILLIS),
             firstNonNull(sessionValidationSchedule, DEFAULT_SESSION_VALIDATION_SCHEDULE),
             properties);
    }

    /**
     * Creates a new instance.
     *
     * @param factory the {@link AuthProviderFactory} instance
     * @param systemAdministrators the login names of the system administrators
     * @param caseSensitiveLoginNames the flag whether case-sensitive matching is performed when login names
     *                                are compared
     * @param sessionCacheSpec the cache specification which determines the capacity and behavior of
     *                         the cache for {@link Session} of the server
     * @param sessionTimeoutMillis the timeout for {@link Session}s of the server
     * @param sessionValidationSchedule the schedule for validating sessions
     * @param properties the additional properties which are used in the factory
     */
    public AuthConfig(AuthProviderFactory factory,
                      Set<String> systemAdministrators,
                      boolean caseSensitiveLoginNames,
                      String sessionCacheSpec,
                      long sessionTimeoutMillis,
                      String sessionValidationSchedule,
                      @Nullable JsonNode properties) {
        this.factory = requireNonNull(factory, "factory");
        this.systemAdministrators = requireNonNull(systemAdministrators, "systemAdministrators");
        this.caseSensitiveLoginNames = caseSensitiveLoginNames;
        this.sessionCacheSpec = validateCacheSpec(requireNonNull(sessionCacheSpec, "sessionCacheSpec"));
        checkArgument(sessionTimeoutMillis > 0,
                      "sessionTimeoutMillis: %s (expected: > 0)", sessionTimeoutMillis);
        this.sessionTimeoutMillis = sessionTimeoutMillis;
        this.sessionValidationSchedule = AuthConfigSpec.validateSchedule(
                requireNonNull(sessionValidationSchedule, "sessionValidationSchedule"));
        this.properties = properties;
    }

    /**
     * Returns the {@link AuthProviderFactory}.
     */
    @Override
    public AuthProviderFactory factory() {
        return factory;
    }

    @JsonProperty
    @Override
    public String factoryClassName() {
        return factory.getClass().getName();
    }

    /**
     * Returns the usernames of the users with system administrator rights.
     */
    @JsonProperty
    @Override
    public Set<String> systemAdministrators() {
        return systemAdministrators;
    }

    @JsonProperty
    @Override
    public boolean caseSensitiveLoginNames() {
        return caseSensitiveLoginNames;
    }

    @JsonProperty
    @Override
    public String sessionCacheSpec() {
        return sessionCacheSpec;
    }

    @JsonProperty
    @Override
    public long sessionTimeoutMillis() {
        return sessionTimeoutMillis;
    }

    @JsonProperty
    @Override
    public String sessionValidationSchedule() {
        return sessionValidationSchedule;
    }

    @Nullable
    @Override
    public <T> T properties(Class<T> clazz) throws JsonProcessingException {
        return properties != null ? Jackson.treeToValue(properties, clazz) : null;
    }

    @Override
    public Function<String, String> loginNameNormalizer() {
        return caseSensitiveLoginNames() ? Function.identity() : Ascii::toLowerCase;
    }

    @Override
    public String toString() {
        try {
            return Jackson.writeValueAsPrettyString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
