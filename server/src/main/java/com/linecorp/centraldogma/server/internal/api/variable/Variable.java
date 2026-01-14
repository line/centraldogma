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

package com.linecorp.centraldogma.server.internal.api.variable;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

@JsonInclude(Include.NON_NULL)
public final class Variable {

    private final String id;
    private final VariableType type;
    @Nullable
    private String name;
    private final String value;

    public Variable(String id, VariableType type, String value) {
        this.id = requireNonNull(id, "id");
        this.type = requireNonNull(type, "type");
        this.value = requireNonNull(value, "value");
    }

    @JsonCreator
    public Variable(@JsonProperty("id") String id,
                    @JsonProperty("type") VariableType type,
                    @JsonProperty("name") @Nullable String name,
                    @JsonProperty("value") String value) {
        this.id = requireNonNull(id, "id");
        this.type = requireNonNull(type, "type");
        this.name = name;
        this.value = requireNonNull(value, "value");
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

    @JsonProperty("type")
    public VariableType type() {
        return type;
    }

    @JsonProperty("value")
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Variable)) {
            return false;
        }

        final Variable variable = (Variable) o;
        return id.equals(variable.id) &&
               Objects.equals(name, variable.name) &&
               type == variable.type &&
               value.equals(variable.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, type, value);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("id", id)
                          .add("name", name)
                          .add("type", type)
                          .add("value", value)
                          .toString();
    }
}
