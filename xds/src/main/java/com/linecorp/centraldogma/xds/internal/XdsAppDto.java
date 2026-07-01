/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.centraldogma.xds.internal;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

public final class XdsAppDto {

    private final String appId;
    private final List<String> readableGroups;

    @JsonCreator
    XdsAppDto(@JsonProperty("appId") String appId,
              @JsonProperty("readableGroups") List<String> readableGroups) {
        this.appId = requireNonNull(appId, "appId");
        this.readableGroups = requireNonNull(readableGroups, "readableGroups");
    }

    @JsonProperty("appId")
    String appId() {
        return appId;
    }

    @JsonProperty("readableGroups")
    List<String> readableGroups() {
        return readableGroups;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("appId", appId)
                          .add("readableGroups", readableGroups)
                          .toString();
    }
}
