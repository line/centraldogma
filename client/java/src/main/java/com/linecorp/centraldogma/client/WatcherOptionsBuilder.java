/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.centraldogma.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class WatcherOptionsBuilder {

    private static final long DEFAULT_DELAY_ON_SUCCESS_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final long DEFAULT_MAX_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final double DEFAULT_MULTIPLIER = 2.0;
    private static final double DEFAULT_JITTER_RATE = 0.2;

    private long delayOnSuccessMillis = DEFAULT_DELAY_ON_SUCCESS_MILLIS;
    private long initialDelayMillis = DEFAULT_DELAY_ON_SUCCESS_MILLIS * 2;
    private long maxDelayMillis = DEFAULT_MAX_DELAY_MILLIS;
    private double multiplier = DEFAULT_MULTIPLIER;
    private double jitterRate = DEFAULT_JITTER_RATE;

    WatcherOptionsBuilder() {}

    public WatcherOptionsBuilder delayOnSuccess(Duration delayOnSuccess) {
        requireNonNull(delayOnSuccess, "delayOnSuccess");
        checkArgument(!delayOnSuccess.isNegative(),
                      "delayOnSuccess: %s (expected: >= 0)", delayOnSuccess);
        return delayOnSuccessMillis(delayOnSuccess.toMillis());
    }

    public WatcherOptionsBuilder delayOnSuccessMillis(long delayOnSuccessMillis) {
        this.delayOnSuccessMillis = delayOnSuccessMillis;
        checkArgument(delayOnSuccessMillis >= 0,
                      "delayOnSuccessMillis: %s (expected: >= 0)", delayOnSuccessMillis);
        return this;
    }

    public WatcherOptionsBuilder backoffOnFailure(long initialDelayMillis, long maxDelayMillis,
                                                  double multiplier) {
        checkArgument(initialDelayMillis >= 0, "initialDelayMillis: %s (expected: >= 0)", initialDelayMillis);
        checkArgument(initialDelayMillis <= maxDelayMillis, "maxDelayMillis: %s (expected: >= %s)",
                      maxDelayMillis, initialDelayMillis);
        checkArgument(multiplier > 1.0, "multiplier: %s (expected: > 1.0)", multiplier);
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.multiplier = multiplier;
        return this;
    }

    public WatcherOptionsBuilder jitterRate(double jitterRate) {
        checkArgument(0.0 <= jitterRate && jitterRate <= 1.0,
                      "jitterRate: %s (expected: >= 0.0 and <= 1.0)", jitterRate);
        this.jitterRate = jitterRate;
        return this;
    }

    public WatcherOptions build() {
        return new WatcherOptions(delayOnSuccessMillis, initialDelayMillis, maxDelayMillis,
                                  multiplier, jitterRate);
    }
}
