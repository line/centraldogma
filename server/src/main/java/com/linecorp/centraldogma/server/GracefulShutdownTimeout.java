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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

public final class GracefulShutdownTimeout {
    private final long quietPeriodMillis;
    private final long timeoutMillis;

    public GracefulShutdownTimeout(
            @JsonProperty(value = "quietPeriodMillis", required = true) long quietPeriodMillis,
            @JsonProperty(value = "timeoutMillis", required = true) long timeoutMillis) {
        this.quietPeriodMillis = quietPeriodMillis;
        this.timeoutMillis = timeoutMillis;
    }

    @JsonProperty("quietPeriodMillis")
    public long quietPeriodMillis() {
        return quietPeriodMillis;
    }

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
