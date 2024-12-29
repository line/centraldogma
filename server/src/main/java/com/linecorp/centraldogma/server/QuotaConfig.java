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
package com.linecorp.centraldogma.server;

import static org.apache.curator.shaded.com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * {@link CentralDogma} API quota configuration.
 */
public final class QuotaConfig implements QuotaConfigSpec {

    private final int requestQuota;
    private final int timeWindowSeconds;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public QuotaConfig(@JsonProperty("requestQuota") int requestQuota,
                       @JsonProperty("timeWindowSeconds") int timeWindowSeconds) {
        checkArgument(requestQuota > 0, "requestQuota: %s (expected: > 0)", requestQuota);
        checkArgument(timeWindowSeconds > 0, "timeWindowSeconds: %s (expected: > 0)", timeWindowSeconds);

        this.requestQuota = requestQuota;
        this.timeWindowSeconds = timeWindowSeconds;
    }

    @JsonProperty
    @Override
    public int timeWindowSeconds() {
        return timeWindowSeconds;
    }

    @JsonProperty
    @Override
    public int requestQuota() {
        return requestQuota;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof QuotaConfig)) {
            return false;
        }

        final QuotaConfig that = (QuotaConfig) o;
        return requestQuota == that.requestQuota && timeWindowSeconds == that.timeWindowSeconds;
    }

    @Override
    public int hashCode() {
        return requestQuota * 31 + timeWindowSeconds;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("requestQuota", requestQuota)
                          .add("timeWindowSeconds", timeWindowSeconds)
                          .toString();
    }
}
