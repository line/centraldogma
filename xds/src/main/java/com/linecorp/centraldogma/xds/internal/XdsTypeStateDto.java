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

final class XdsTypeStateDto {

    private final String typeUrl;
    private final String status;
    private final String ackedVersion;
    private final String servedVersion;
    private final String nackReason;
    private final String lastNonce;
    private final long lastSeen;
    private final List<String> resourceNames;

    @JsonCreator
    XdsTypeStateDto(@JsonProperty("typeUrl") String typeUrl,
                    @JsonProperty("status") String status,
                    @JsonProperty("ackedVersion") String ackedVersion,
                    @JsonProperty("servedVersion") String servedVersion,
                    @JsonProperty("nackReason") String nackReason,
                    @JsonProperty("lastNonce") String lastNonce,
                    @JsonProperty("lastSeen") long lastSeen,
                    @JsonProperty("resourceNames") List<String> resourceNames) {
        this.typeUrl = requireNonNull(typeUrl, "typeUrl");
        this.status = requireNonNull(status, "status");
        this.ackedVersion = requireNonNull(ackedVersion, "ackedVersion");
        this.servedVersion = requireNonNull(servedVersion, "servedVersion");
        this.nackReason = requireNonNull(nackReason, "nackReason");
        this.lastNonce = requireNonNull(lastNonce, "lastNonce");
        this.lastSeen = lastSeen;
        this.resourceNames = requireNonNull(resourceNames, "resourceNames");
    }

    @JsonProperty("typeUrl")
    String typeUrl() {
        return typeUrl;
    }

    @JsonProperty("status")
    String status() {
        return status;
    }

    @JsonProperty("ackedVersion")
    String ackedVersion() {
        return ackedVersion;
    }

    @JsonProperty("servedVersion")
    String servedVersion() {
        return servedVersion;
    }

    @JsonProperty("nackReason")
    String nackReason() {
        return nackReason;
    }

    @JsonProperty("lastNonce")
    String lastNonce() {
        return lastNonce;
    }

    @JsonProperty("lastSeen")
    long lastSeen() {
        return lastSeen;
    }

    @JsonProperty("resourceNames")
    List<String> resourceNames() {
        return resourceNames;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("typeUrl", typeUrl)
                          .add("status", status)
                          .add("ackedVersion", ackedVersion)
                          .add("servedVersion", servedVersion)
                          .add("nackReason", nackReason)
                          .add("lastNonce", lastNonce)
                          .add("lastSeen", lastSeen)
                          .add("resourceNames", resourceNames)
                          .toString();
    }
}
