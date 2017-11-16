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

package com.linecorp.centraldogma.server.internal.command;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;

public final class SavePluginCommand extends ProjectCommand<Void> {

    private final String pluginName;
    private final String path;

    @JsonCreator
    SavePluginCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                      @JsonProperty("author") @Nullable Author author,
                      @JsonProperty("projectName") String projectName,
                      @JsonProperty("pluginName") String pluginName,
                      @JsonProperty("path") String path) {

        super(CommandType.SAVE_PLUGIN, timestamp, author, projectName);
        this.pluginName = requireNonNull(pluginName, "pluginName");
        this.path = requireNonNull(path, "path");
    }

    @JsonProperty
    public String pluginName() {
        return pluginName;
    }

    @JsonProperty
    public String path() {
        return path;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof SavePluginCommand)) {
            return false;
        }

        final SavePluginCommand that = (SavePluginCommand) obj;
        return super.equals(obj) &&
               pluginName.equals(that.pluginName) &&
               path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pluginName, path) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper().add("pluginName", pluginName).add("path", path);
    }
}
