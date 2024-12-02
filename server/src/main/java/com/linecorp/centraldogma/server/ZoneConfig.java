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
 *
 */

package com.linecorp.centraldogma.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.server.CentralDogmaConfig.convertValue;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * A configuration class for the zone.
 */
public final class ZoneConfig {

    private final String currentZone;
    private final List<String> allZones;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public ZoneConfig(@JsonProperty("currentZone") String currentZone,
                      @JsonProperty("allZones") List<String> allZones) {
        requireNonNull(currentZone, "currentZone");
        requireNonNull(allZones, "allZones");
        this.currentZone = convertValue(currentZone, "zone.currentZone");
        this.allZones = allZones;
        checkArgument(allZones.contains(currentZone), "The current zone: %s, (expected: one of %s)",
                      currentZone, allZones);
    }

    /**
     * Returns the current zone.
     */
    @JsonProperty("currentZone")
    public String currentZone() {
        return currentZone;
    }

    /**
     * Returns all zones.
     */
    @JsonProperty("allZones")
    public List<String> allZones() {
        return allZones;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZoneConfig)) {
            return false;
        }
        final ZoneConfig that = (ZoneConfig) o;
        return currentZone.equals(that.currentZone) &&
               allZones.equals(that.allZones);
    }

    @Override
    public int hashCode() {
        return Objects.hash(currentZone, allZones);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("currentZone", currentZone)
                          .add("allZones", allZones)
                          .toString();
    }
}
