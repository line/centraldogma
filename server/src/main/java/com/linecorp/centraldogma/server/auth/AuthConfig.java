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
package com.linecorp.centraldogma.server.auth;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache.validateCacheSpec;
import static java.util.Objects.requireNonNull;

import java.text.ParseException;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.quartz.CronExpression;

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
public final class AuthConfig {
    /**
     * A default session timeout in milliseconds.
     */
    public static final long DEFAULT_SESSION_TIMEOUT_MILLIS = 604800000;   // 7 days

    /**
     * A default specification for a session cache.
     */
    public static final String DEFAULT_SESSION_CACHE_SPEC =
            // Expire after the duration of session timeout.
            "maximumSize=8192,expireAfterWrite=" + (DEFAULT_SESSION_TIMEOUT_MILLIS / 1000) + 's';

    /**
     * A default schedule for validating sessions at 0:30, 4:30, 8:30, 12:30, 16:30 and 20:30 for every day.
     */
    public static final String DEFAULT_SESSION_VALIDATION_SCHEDULE = "0 30 */4 ? * *";

    private final AuthProviderFactory factory;

    private final Set<String> administrators;
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
     * @param administrators the login names of the administrators
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
            @JsonProperty("administrators") @Nullable Set<String> administrators,
            @JsonProperty("caseSensitiveLoginNames") @Nullable Boolean caseSensitiveLoginNames,
            @JsonProperty("sessionCacheSpec") @Nullable String sessionCacheSpec,
            @JsonProperty("sessionTimeoutMillis") @Nullable Long sessionTimeoutMillis,
            @JsonProperty("sessionValidationSchedule") @Nullable String sessionValidationSchedule,
            @JsonProperty("properties") @Nullable JsonNode properties) throws Exception {
        this((AuthProviderFactory) AuthConfig.class
                     .getClassLoader()
                     .loadClass(requireNonNull(factoryClassName, "factoryClassName"))
                     .getDeclaredConstructor().newInstance(),
             administrators != null ? ImmutableSet.copyOf(administrators) : ImmutableSet.of(),
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
     * @param administrators the login names of the administrators
     * @param caseSensitiveLoginNames the flag whether case-sensitive matching is performed when login names
     *                                are compared
     * @param sessionCacheSpec the cache specification which determines the capacity and behavior of
     *                         the cache for {@link Session} of the server
     * @param sessionTimeoutMillis the timeout for {@link Session}s of the server
     * @param sessionValidationSchedule the schedule for validating sessions
     * @param properties the additional properties which are used in the factory
     */
    public AuthConfig(AuthProviderFactory factory,
                      Set<String> administrators,
                      boolean caseSensitiveLoginNames,
                      String sessionCacheSpec,
                      long sessionTimeoutMillis,
                      String sessionValidationSchedule,
                      @Nullable JsonNode properties) {
        this.factory = requireNonNull(factory, "factory");
        this.administrators = requireNonNull(administrators, "administrators");
        this.caseSensitiveLoginNames = caseSensitiveLoginNames;
        this.sessionCacheSpec = validateCacheSpec(requireNonNull(sessionCacheSpec, "sessionCacheSpec"));
        checkArgument(sessionTimeoutMillis > 0,
                      "sessionTimeoutMillis: %s (expected: > 0)", sessionTimeoutMillis);
        this.sessionTimeoutMillis = sessionTimeoutMillis;
        this.sessionValidationSchedule = validateSchedule(
                requireNonNull(sessionValidationSchedule, "sessionValidationSchedule"));
        this.properties = properties;
    }

    /**
     * Returns the {@link AuthProviderFactory}.
     */
    public AuthProviderFactory factory() {
        return factory;
    }

    /**
     * Returns the class name of the {@link AuthProviderFactory}.
     */
    @JsonProperty
    public String factoryClassName() {
        return factory.getClass().getName();
    }

    /**
     * Returns the usernames of the users with administrator rights.
     */
    @JsonProperty
    public Set<String> administrators() {
        return administrators;
    }

    /**
     * Returns whether login names are case-sensitive.
     */
    @JsonProperty
    public boolean caseSensitiveLoginNames() {
        return caseSensitiveLoginNames;
    }

    /**
     * Returns the spec of the session cache.
     */
    @JsonProperty
    public String sessionCacheSpec() {
        return sessionCacheSpec;
    }

    /**
     * Returns the timeout of an inactive session in milliseconds.
     */
    @JsonProperty
    public long sessionTimeoutMillis() {
        return sessionTimeoutMillis;
    }

    /**
     * Returns the cron expression that describes how often session validation task should run.
     */
    @JsonProperty
    public String sessionValidationSchedule() {
        return sessionValidationSchedule;
    }

    /**
     * Returns the additional properties given to the {@link AuthProviderFactory}.
     */
    @Nullable
    @JsonProperty
    public JsonNode properties() {
        return properties;
    }

    /**
     * Returns the additional properties, converted to {@code T}.
     */
    @Nullable
    public <T> T properties(Class<T> clazz) throws JsonProcessingException {
        return properties != null ? Jackson.treeToValue(properties, clazz) : null;
    }

    /**
     * Returns a {@link Function} which normalizes a login name based on the
     * {@link AuthConfig#caseSensitiveLoginNames()} property.
     */
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

    private static String validateSchedule(String sessionValidationSchedule) {
        try {
            CronExpression.validateExpression(sessionValidationSchedule);
            return sessionValidationSchedule;
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid session validation schedule", e);
        }
    }
}
