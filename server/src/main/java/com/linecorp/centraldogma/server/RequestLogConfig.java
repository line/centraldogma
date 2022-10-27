/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.centraldogma.server;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.logging.LogLevel;

/**
 * A request log configuration.
 */
public final class RequestLogConfig {

    private final Set<RequestLogGroup> targetGroups;

    private final LogLevel requestLogLevel;
    private final LogLevel successfulResponseLogLevel;
    private final LogLevel failureResponseLogLevel;

    private final float successSamplingRate;
    private final float failureSamplingRate;
    private final String loggerName;

    /**
     * Create a new instance.
     *
     * @param targetGroups the target {@link RequestLogGroup}s which should be logged.
     * @param loggerName the logger name to use when logging.
     * @param requestLogLevel the {@link LogLevel} to use when logging incoming requests.
     * @param successfulResponseLogLevel the {@link LogLevel} to use when logging successful responses
     *                                   (e.g., an HTTP status is less than 400).
     * @param failureResponseLogLevel the {@link LogLevel} to use when logging failed responses
     *                                (e.g., an HTTP status is 4xx or 5xx).
     * @param successSamplingRate The rate at which to sample success requests to log. Any number between
     *                            {@code 0.0} and {@code 1.0} will cause a random sample of the requests to
     *                            be logged.
     * @param failureSamplingRate The rate at which to sample failed requests to log. Any number between
     *                            {@code 0.0} and {@code 1.0} will cause a random sample of the requests to
     *                            be logged.
     */
    public RequestLogConfig(
            @JsonProperty(value = "targetGroups", required = true) Set<RequestLogGroup> targetGroups,
            @JsonProperty("loggerName") @Nullable String loggerName,
            @JsonProperty("requestLogLevel") @Nullable LogLevel requestLogLevel,
            @JsonProperty("successfulResponseLogLevel") @Nullable LogLevel successfulResponseLogLevel,
            @JsonProperty("failureResponseLogLevel") @Nullable LogLevel failureResponseLogLevel,
            @JsonProperty("successSamplingRate") @Nullable Float successSamplingRate,
            @JsonProperty("failureSamplingRate") @Nullable Float failureSamplingRate) {
        this.targetGroups = requireNonNull(targetGroups, "targetGroups");
        this.loggerName = firstNonNull(loggerName, "com.linecorp.centraldogma.request.log");
        this.requestLogLevel = firstNonNull(requestLogLevel, LogLevel.TRACE);
        this.successfulResponseLogLevel = firstNonNull(successfulResponseLogLevel, LogLevel.TRACE);
        this.failureResponseLogLevel = firstNonNull(failureResponseLogLevel, LogLevel.WARN);
        this.successSamplingRate = firstNonNull(successSamplingRate, 1.0f);
        this.failureSamplingRate = firstNonNull(failureSamplingRate, 1.0f);
    }

    /**
     * Returns the target {@link RequestLogGroup}s which should be logged.
     */
    @JsonProperty
    public Set<RequestLogGroup> targetGroups() {
        return targetGroups;
    }

    /**
     * Returns the logger name to use when logging.
     */
    @JsonProperty
    public String loggerName() {
        return loggerName;
    }

    /**
     * Returns the {@link LogLevel} to use when logging requests.
     */
    @JsonProperty
    public LogLevel requestLogLevel() {
        return requestLogLevel;
    }

    /**
     * Returns the {@link LogLevel} to use when logging successful responses
     * (e.g., an HTTP status is less than 400).
     */
    @JsonProperty
    public LogLevel successfulResponseLogLevel() {
        return successfulResponseLogLevel;
    }

    /**
     * Returns the {@link LogLevel} to use when logging failed responses
     * (e.g., an HTTP status is 4xx or 5xx).
     */
    @JsonProperty
    public LogLevel failureResponseLogLevel() {
        return failureResponseLogLevel;
    }

    /**
     * Returns the rate at which to sample success requests to log. Any number between {@code 0.0} and
     * {@code 1.0} will cause a random sample of the requests to be logged.
     */
    @JsonProperty
    public float successSamplingRate() {
        return successSamplingRate;
    }

    /**
     * The rate at which to sample failed requests to log. Any number between {@code 0.0} and {@code 1.0}
     * will cause a random sample of the requests to be logged.
     */
    @JsonProperty
    public float failureSamplingRate() {
        return failureSamplingRate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RequestLogConfig)) {
            return false;
        }
        final RequestLogConfig that = (RequestLogConfig) o;
        return targetGroups.equals(that.targetGroups) &&
               requestLogLevel == that.requestLogLevel &&
               successfulResponseLogLevel == that.successfulResponseLogLevel &&
               failureResponseLogLevel == that.failureResponseLogLevel &&
               Float.compare(that.successSamplingRate, successSamplingRate) == 0 &&
               Float.compare(that.failureSamplingRate, failureSamplingRate) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetGroups, requestLogLevel, successfulResponseLogLevel, failureResponseLogLevel,
                            successSamplingRate, failureSamplingRate);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("targetGroups", targetGroups)
                          .add("requestLogLevel", requestLogLevel)
                          .add("successfulResponseLogLevel", successfulResponseLogLevel)
                          .add("failureResponseLogLevel", failureResponseLogLevel)
                          .add("successSamplingRate", successSamplingRate)
                          .add("failureSamplingRate", failureSamplingRate)
                          .toString();
    }
}
