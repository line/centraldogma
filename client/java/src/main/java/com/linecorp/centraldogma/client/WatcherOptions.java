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

/**
 * Options used for {@link Watcher}.
 */
public final class WatcherOptions {

    private static final WatcherOptions defaultWatcherOptions = builder().build();

    static WatcherOptions ofDefault() {
        return defaultWatcherOptions;
    }

    /**
     * Returns a newly created {@link WatcherOptionsBuilder}.
     */
    public static WatcherOptionsBuilder builder() {
        return new WatcherOptionsBuilder();
    }

    private final long delayOnSuccessMillis;
    private final long initialDelayMillis;
    private final long maxDelayMillis;
    private final double multiplier;
    private final double jitterRate;

    WatcherOptions(long delayOnSuccessMillis, long initialDelayMillis, long maxDelayMillis,
                   double multiplier, double jitterRate) {
        this.delayOnSuccessMillis = delayOnSuccessMillis;
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.multiplier = multiplier;
        this.jitterRate = jitterRate;
    }

    long delayOnSuccessMillis() {
        return delayOnSuccessMillis;
    }

    long initialDelayMillis() {
        return initialDelayMillis;
    }

    long maxDelayMillis() {
        return maxDelayMillis;
    }

    double multiplier() {
        return multiplier;
    }

    double jitterRate() {
        return jitterRate;
    }
}
