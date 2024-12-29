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

import java.text.ParseException;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.quartz.CronExpression;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * An authentication configuration spec for the Central Dogma server.
 */
public interface AuthConfigSpec {

    /**
     * A default session timeout in milliseconds.
     */
    long DEFAULT_SESSION_TIMEOUT_MILLIS = 604800000;   // 7 days
    /**
     * A default specification for a session cache.
     */
    String DEFAULT_SESSION_CACHE_SPEC =
            // Expire after the duration of session timeout.
            "maximumSize=8192,expireAfterWrite=" + (DEFAULT_SESSION_TIMEOUT_MILLIS / 1000) + 's';
    /**
     * A default schedule for validating sessions at 0:30, 4:30, 8:30, 12:30, 16:30 and 20:30 for every day.
     */
    String DEFAULT_SESSION_VALIDATION_SCHEDULE = "0 30 */4 ? * *";

    /**
     * To validate session schedule.
     * @param sessionValidationSchedule Target session schedule.
     * @return Validated session schedule.
     */
    static String validateSchedule(String sessionValidationSchedule) {
        try {
            CronExpression.validateExpression(sessionValidationSchedule);
            return sessionValidationSchedule;
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid session validation schedule", e);
        }
    }

    /**
     * Returns the {@link AuthProviderFactory}.
     */
    AuthProviderFactory factory();

    /**
     * Returns the class name of the {@link AuthProviderFactory}.
     */
    String factoryClassName();

    /**
     * Returns the usernames of the users with administrator rights.
     */
    Set<String> systemAdministrators();

    /**
     * Returns whether login names are case-sensitive.
     */
    boolean caseSensitiveLoginNames();

    /**
     * Returns the spec of the session cache.
     */
    String sessionCacheSpec();

    /**
     * Returns the timeout of an inactive session in milliseconds.
     */
    long sessionTimeoutMillis();

    /**
     * Returns the cron expression that describes how often session validation task should run.
     */
    String sessionValidationSchedule();

    /**
     * Returns the additional properties, converted to {@code T}.
     */
    @Nullable
    <T> T properties(Class<T> clazz) throws JsonProcessingException;

    /**
     * Returns a {@link Function} which normalizes a login name based on the
     * {@link AuthConfigSpec#caseSensitiveLoginNames()} property.
     */
    Function<String, String> loginNameNormalizer();
}
