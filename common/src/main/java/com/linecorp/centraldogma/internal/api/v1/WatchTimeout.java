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
    public static final long MAX_MILLIS = TimeUnit.DAYS.toMillis(365);

    /**
     * Returns a reasonable timeout duration for a watch request.
     *
     * @param expectedTimeoutMillis timeout duration that a user wants to use, in milliseconds
     * @param bufferMillis buffer duration which needs to be added, in milliseconds
     * @return timeout duration in milliseconds, between the specified {@code bufferMillis} and
     *         the {@link #MAX_MILLIS}.
     */
    public static long makeReasonable(long expectedTimeoutMillis, long bufferMillis) {
        checkArgument(expectedTimeoutMillis > 0,
                      "expectedTimeoutMillis: %s (expected: > 0)", expectedTimeoutMillis);
        checkArgument(bufferMillis >= 0,
                      "bufferMillis: %s (expected: > 0)", bufferMillis);

        final long timeout = Math.min(expectedTimeoutMillis, MAX_MILLIS);
        if (bufferMillis == 0) {
            return timeout;
        }

        if (timeout > MAX_MILLIS - bufferMillis) {
            return MAX_MILLIS;
        } else {
            return bufferMillis + timeout;
        }
    }

    private WatchTimeout() {}
}
