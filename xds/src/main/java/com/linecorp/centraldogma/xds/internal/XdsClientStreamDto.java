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

public final class XdsClientStreamDto {

    private final long streamId;
    private final String nodeId;
    private final String nodeCluster;
    private final String appId;
    private final long openedAt;
    private final List<XdsTypeStateDto> types;

    @JsonCreator
    XdsClientStreamDto(@JsonProperty("streamId") long streamId,
                       @JsonProperty("nodeId") String nodeId,
                       @JsonProperty("nodeCluster") String nodeCluster,
                       @JsonProperty("appId") String appId,
                       @JsonProperty("openedAt") long openedAt,
                       @JsonProperty("types") List<XdsTypeStateDto> types) {
        this.streamId = streamId;
        this.nodeId = requireNonNull(nodeId, "nodeId");
        this.nodeCluster = requireNonNull(nodeCluster, "nodeCluster");
        this.appId = requireNonNull(appId, "appId");
        this.openedAt = openedAt;
        this.types = requireNonNull(types, "types");
    }

    @JsonProperty("streamId")
    long streamId() {
        return streamId;
    }

    @JsonProperty("nodeId")
    String nodeId() {
        return nodeId;
    }

    @JsonProperty("nodeCluster")
    String nodeCluster() {
        return nodeCluster;
    }

    @JsonProperty("appId")
    String appId() {
        return appId;
    }

    @JsonProperty("openedAt")
    long openedAt() {
        return openedAt;
    }

    @JsonProperty("types")
    List<XdsTypeStateDto> types() {
        return types;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("streamId", streamId)
                          .add("nodeId", nodeId)
                          .add("nodeCluster", nodeCluster)
                          .add("appId", appId)
                          .add("openedAt", openedAt)
                          .add("types", types)
                          .toString();
    }
}
