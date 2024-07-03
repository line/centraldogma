/*
 * Copyright 2017 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * Graceful shutdown timeout.
 */
public final class GracefulShutdownTimeout {
    private final long quietPeriodMillis;
    private final long timeoutMillis;

    /**
     * Creates a new instance.
     *
     * @param quietPeriodMillis the number of milliseconds to wait for active requests to go end before
     *                          shutting down. {@code 0} means the server will stop right away without waiting.
     * @param timeoutMillis the number of milliseconds to wait before shutting down the server regardless of
     *                      active requests. This should be set to a time greater than {@code quietPeriodMillis}
     *                      to ensure the server shuts down even if there is a stuck request.
     */
    public GracefulShutdownTimeout(
            @JsonProperty(value = "quietPeriodMillis", required = true) long quietPeriodMillis,
            @JsonProperty(value = "timeoutMillis", required = true) long timeoutMillis) {
        checkArgument(quietPeriodMillis >= 0,
                      "quietPeriodMillis: %s (expected: >= 0)", quietPeriodMillis);
        checkArgument(timeoutMillis >= 0,
                      "timeoutMillis: %s (expected: >= 0)", timeoutMillis);
        this.quietPeriodMillis = quietPeriodMillis;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Returns the quiet period of graceful shutdown process, in milliseconds.
     */
    @JsonProperty("quietPeriodMillis")
    public long quietPeriodMillis() {
        return quietPeriodMillis;
    }

    /**
     * Returns the timeout of graceful shutdown process, in milliseconds.
     */
    @JsonProperty("timeoutMillis")
    public long timeoutMillis() {
        return timeoutMillis;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("quietPeriodMillis", quietPeriodMillis)
                          .add("timeoutMillis", timeoutMillis).toString();
    }
}
