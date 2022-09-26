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

package com.linecorp.centraldogma.server.internal.mirror;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;

@VisibleForTesting
public final class MirrorState {

    private final String sourceRevision;

    @JsonCreator
    MirrorState(@JsonProperty(value = "sourceRevision", required = true) String sourceRevision) {
        this.sourceRevision = requireNonNull(sourceRevision, "sourceRevision");
    }

    @JsonProperty("sourceRevision")
    public String sourceRevision() {
        return sourceRevision;
    }
}
