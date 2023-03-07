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
package com.linecorp.centraldogma.server;

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

/**
 * CORS configuration.
 */
public final class CorsConfig {

    private static final int DEFAULT_MAX_AGE = 7200;

    private final List<String> allowedOrigins;
    private final int maxAge;

    /**
     * Creates an instance with the specified {@code allowedOrigins} and
     * {@code maxAge}.
     */
    @JsonCreator
    public CorsConfig(@JsonProperty("allowedOrigins") Object allowedOrigins,
                      @JsonProperty("maxAge") @Nullable Integer maxAge) {
        if (allowedOrigins instanceof Iterable &&
            Streams.stream((Iterable<?>) allowedOrigins).allMatch(String.class::isInstance)) {
            this.allowedOrigins = ImmutableList.copyOf((Iterable<String>) allowedOrigins);
        } else if (allowedOrigins instanceof String) {
            final String origin = (String) allowedOrigins;
            this.allowedOrigins = ImmutableList.of(origin);
        } else {
            throw new IllegalArgumentException(
                    "allowedOrigins: " + allowedOrigins +
                    " (expected: either a string or an array of strings)");
        }
        if (this.allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowedOrigins: " + allowedOrigins +
                    " (expected: the list of origins must not be empty)");
        }

        if (maxAge == null) {
            this.maxAge = DEFAULT_MAX_AGE;
            return;
        }
        if (maxAge <= 0) {
            throw new IllegalArgumentException(
                    "maxAge: " + maxAge +
                    " (expected: maxAge must be positive)");
        }
        this.maxAge = maxAge;
    }

    /**
     * Returns the list of origins which are allowed a CORS policy.
     */
    @JsonProperty
    public List<String> allowedOrigins() {
        return allowedOrigins;
    }

    /**
     * Returns how long in seconds the results of a preflight request can be cached.
     * If unspecified, the default of {@value #DEFAULT_MAX_AGE} is returned.
     */
    @JsonProperty
    public int maxAge() {
        return maxAge;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("allowedOrigins", allowedOrigins)
                          .add("maxAge", maxAge)
                          .toString();
    }
}
