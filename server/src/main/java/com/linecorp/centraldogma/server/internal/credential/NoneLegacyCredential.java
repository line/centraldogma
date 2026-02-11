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

package com.linecorp.centraldogma.server.internal.credential;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.credential.LegacyCredential;

public final class NoneLegacyCredential extends AbstractLegacyCredential {

    @JsonCreator
    public NoneLegacyCredential(@JsonProperty("id") String id,
                                @JsonProperty("enabled") @Nullable Boolean enabled) {
        super(id, enabled, "none");
    }

    @Override
    void addProperties(ToStringHelper helper) {
        // No properties to add
    }

    @Override
    public Credential toNewCredential(String name) {
        return new NoneCredential(name);
    }

    @Override
    public LegacyCredential withoutSecret() {
        return this;
    }
}
