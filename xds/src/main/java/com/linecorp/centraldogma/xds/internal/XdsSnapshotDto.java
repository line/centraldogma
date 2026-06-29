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

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

@JsonInclude(Include.NON_NULL)
public final class XdsSnapshotDto {

    @Nullable
    private final String appId;
    @Nullable
    private final List<String> readableGroups;
    @Nullable
    private final Boolean served;
    @Nullable
    private final String group;
    @Nullable
    private final XdsResourceTypeDto listeners;
    @Nullable
    private final XdsResourceTypeDto routes;
    @Nullable
    private final XdsResourceTypeDto clusters;
    @Nullable
    private final XdsResourceTypeDto endpoints;

    @JsonCreator
    XdsSnapshotDto(@JsonProperty("appId") @Nullable String appId,
                   @JsonProperty("readableGroups") @Nullable List<String> readableGroups,
                   @JsonProperty("served") @Nullable Boolean served,
                   @JsonProperty("group") @Nullable String group,
                   @JsonProperty("listeners") @Nullable XdsResourceTypeDto listeners,
                   @JsonProperty("routes") @Nullable XdsResourceTypeDto routes,
                   @JsonProperty("clusters") @Nullable XdsResourceTypeDto clusters,
                   @JsonProperty("endpoints") @Nullable XdsResourceTypeDto endpoints) {
        this.appId = appId;
        this.readableGroups = readableGroups;
        this.served = served;
        this.group = group;
        this.listeners = listeners;
        this.routes = routes;
        this.clusters = clusters;
        this.endpoints = endpoints;
    }

    @Nullable
    @JsonProperty("appId")
    String appId() {
        return appId;
    }

    @Nullable
    @JsonProperty("readableGroups")
    List<String> readableGroups() {
        return readableGroups;
    }

    @Nullable
    @JsonProperty("served")
    Boolean served() {
        return served;
    }

    @Nullable
    @JsonProperty("group")
    String group() {
        return group;
    }

    @Nullable
    @JsonProperty("listeners")
    XdsResourceTypeDto listeners() {
        return listeners;
    }

    @Nullable
    @JsonProperty("routes")
    XdsResourceTypeDto routes() {
        return routes;
    }

    @Nullable
    @JsonProperty("clusters")
    XdsResourceTypeDto clusters() {
        return clusters;
    }

    @Nullable
    @JsonProperty("endpoints")
    XdsResourceTypeDto endpoints() {
        return endpoints;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("appId", appId)
                          .add("readableGroups", readableGroups)
                          .add("served", served)
                          .add("group", group)
                          .add("listeners", listeners)
                          .add("routes", routes)
                          .add("clusters", clusters)
                          .add("endpoints", endpoints)
                          .toString();
    }
}
