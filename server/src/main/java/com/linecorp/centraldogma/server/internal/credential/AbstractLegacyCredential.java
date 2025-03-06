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

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.server.credential.LegacyCredential;

abstract class AbstractLegacyCredential implements LegacyCredential {

    private final String id;
    private final boolean enabled;
    // TODO(ikhoon): Consider changing 'type' to an enum.
    private final String type;

    AbstractLegacyCredential(String id, @Nullable Boolean enabled, String type) {
        this.id = requireNonNull(id, "id");
        this.enabled = firstNonNull(enabled, true);
        // JsonTypeInfo is ignored when serializing collections.
        // As a workaround, manually set the type hint to serialize.
        this.type = requireNonNull(type, "type");
    }

    @Override
    public final String id() {
        return id;
    }

    @JsonProperty("type")
    public final String type() {
        return type;
    }

    @Override
    public final boolean enabled() {
        return enabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final AbstractLegacyCredential that = (AbstractLegacyCredential) o;
        return enabled == that.enabled &&
               id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode() * 31 + Boolean.hashCode(enabled);
    }

    @Override
    public final String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper(this);
        helper.add("id", id);
        helper.add("type", type);
        helper.add("enabled", enabled);
        addProperties(helper);
        return helper.toString();
    }

    abstract void addProperties(ToStringHelper helper);
}
