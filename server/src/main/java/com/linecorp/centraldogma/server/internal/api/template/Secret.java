/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.centraldogma.server.internal.api.template;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.metadata.UserAndTimestamp;

@JsonInclude(Include.NON_NULL)
public final class Secret {

    private final String id;
    private final String value;
    @Nullable
    private final String description;

    @Nullable
    private String name;
    @Nullable
    private UserAndTimestamp creation;

    public Secret(String id, String value, @Nullable String description) {
        this.id = requireNonNull(id, "id");
        this.value = requireNonNull(value, "value");
        this.description = description;
    }

    @JsonCreator
    public Secret(@JsonProperty("id") String id,
                  @JsonProperty("name") @Nullable String name,
                  @JsonProperty("value") String value,
                  @JsonProperty("creation") @Nullable UserAndTimestamp creation,
                  @JsonProperty("description") @Nullable String description) {
        this.id = requireNonNull(id, "id");
        this.name = name;
        this.value = requireNonNull(value, "value");
        this.description = description;
        this.creation = creation;
    }

    @JsonProperty("id")
    public String id() {
        return id;
    }

    @Nullable
    @JsonProperty("name")
    public String name() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    @JsonProperty("value")
    public String value() {
        return value;
    }

    @Nullable
    @JsonProperty("description")
    public String description() {
        return description;
    }

    @Nullable
    @JsonProperty("creation")
    public UserAndTimestamp creation() {
        return creation;
    }

    void setCreation(UserAndTimestamp creation) {
        this.creation = creation;
    }

    public Secret withoutValue() {
        return new Secret(id, name, "****", creation, description);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Secret)) {
            return false;
        }

        final Secret variable = (Secret) o;
        return id.equals(variable.id) &&
               Objects.equals(name, variable.name) &&
               value.equals(variable.value) &&
               Objects.equals(description, variable.description) &&
               Objects.equals(creation, variable.creation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, value, description, creation);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("id", id)
                          .add("name", name)
                          .add("value", "****")
                          .add("description", description)
                          .add("creation", creation)
                          .toString();
    }
}
