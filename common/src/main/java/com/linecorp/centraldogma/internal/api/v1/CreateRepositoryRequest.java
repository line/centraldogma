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

package com.linecorp.centraldogma.internal.api.v1;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.centraldogma.internal.Util.validateRepositoryName;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * A request to create a new repository.
 */
public class CreateRepositoryRequest {

    private final String name;
    private final boolean encryptionEnabled;

    @JsonCreator
    public CreateRepositoryRequest(@JsonProperty("name") String name,
                                   @JsonProperty("encryptionEnabled") @Nullable Boolean encryptionEnabled) {
        this.name = validateRepositoryName(name, "name");
        this.encryptionEnabled = firstNonNull(encryptionEnabled, false);
    }

    @JsonProperty
    public String name() {
        return name;
    }

    @JsonProperty
    public boolean encryptionEnabled() {
        return encryptionEnabled;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name())
                          .add("encryptionEnabled", encryptionEnabled)
                          .toString();
    }
}
