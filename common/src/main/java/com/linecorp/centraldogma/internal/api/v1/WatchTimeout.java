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
package com.linecorp.centraldogma.internal.api.v1;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.concurrent.TimeUnit;

/**
 * A utility class which provides constants and methods related to watch timeout.
 */
public final class WatchTimeout {
    /**
     * The maximum timeout duration, in milliseconds.
     */
    public static final long MAX_MILLIS = TimeUnit.DAYS.toMillis(1);

    /**
     * Returns an available timeout duration for a watch request with limitation of max timeout.
     *
     * <p>For example:
     * <pre>{@code
     * assert WatchTimeout.availableTimeout(1000) == 1000;
     * // Limit max timeout duration
     * assert WatchTimeout.availableTimeout(Long.MAX_VALUE) == WatchTimeout.MAX_MILLIS;
     * }</pre>
     *
     * @param expectedTimeoutMillis timeout duration that a user wants to use, in milliseconds
     * @return timeout duration in milliseconds, between 1 and the {@link #MAX_MILLIS}.
     */
    public static long availableTimeout(long expectedTimeoutMillis) {
        return availableTimeout(expectedTimeoutMillis, 0);
    }

    /**
     * Returns an available timeout duration for a watch request.
     * This method subtracts {@code currentTimeoutMillis} from {@code expectedTimeoutMills}
     * with limitation of max timeout.
     *
     * <p>For example:
     * <pre>{@code
     * assert WatchTimeout.availableTimeout(1000, 100) == 1000;
     * assert WatchTimeout.availableTimeout(1000, 0) == 1000;
     * // Limit max timeout duration
     * assert WatchTimeout.availableTimeout(Long.MAX_VALUE, 1000) == WatchTimeout.MAX_MILLIS - 1000;
     * }</pre>
     *
     * @param expectedTimeoutMillis timeout duration that a user wants to use, in milliseconds
     * @param currentTimeoutMillis timeout duration that is currently used, in milliseconds
     * @return timeout duration in milliseconds, between 1 and the {@link #MAX_MILLIS}.
     */
    public static long availableTimeout(long expectedTimeoutMillis, long currentTimeoutMillis) {
        checkArgument(expectedTimeoutMillis > 0,
                      "expectedTimeoutMillis: %s (expected: > 0)", expectedTimeoutMillis);
        checkArgument(currentTimeoutMillis >= 0,
                      "currentTimeoutMillis: %s (expected: >= 0)", currentTimeoutMillis);

        if (currentTimeoutMillis >= MAX_MILLIS) {
            return 0;
        }

        final long maxAvailableTimeoutMillis = MAX_MILLIS - currentTimeoutMillis;
        return Math.min(expectedTimeoutMillis, maxAvailableTimeoutMillis);
    }

    private WatchTimeout() {}
}
