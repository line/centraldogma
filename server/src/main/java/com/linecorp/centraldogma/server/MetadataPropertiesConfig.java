/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.centraldogma.server;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;

/**
 * A configuration for the additional metadata properties of projects, repositories and app identities.
 * Each field is a <a href="https://json-schema.org/">JSON Schema</a> that the {@code properties} of the
 * corresponding resource must conform to at creation time.
 */
@JsonInclude(Include.NON_NULL)
public final class MetadataPropertiesConfig {

    @Nullable
    private final JsonNode project;
    @Nullable
    private final JsonNode repo;
    @Nullable
    private final JsonNode appIdentity;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public MetadataPropertiesConfig(@JsonProperty("project") @Nullable JsonNode project,
                                    @JsonProperty("repo") @Nullable JsonNode repo,
                                    @JsonProperty("appIdentity") @Nullable JsonNode appIdentity) {
        this.project = project;
        this.repo = repo;
        this.appIdentity = appIdentity;
    }

    /**
     * Returns the JSON Schema for the properties of a project.
     */
    @Nullable
    @JsonProperty("project")
    public JsonNode project() {
        return project;
    }

    /**
     * Returns the JSON Schema for the properties of a repository.
     */
    @Nullable
    @JsonProperty("repo")
    public JsonNode repo() {
        return repo;
    }

    /**
     * Returns the JSON Schema for the properties of an app identity.
     */
    @Nullable
    @JsonProperty("appIdentity")
    public JsonNode appIdentity() {
        return appIdentity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MetadataPropertiesConfig)) {
            return false;
        }
        final MetadataPropertiesConfig that = (MetadataPropertiesConfig) o;
        return Objects.equals(project, that.project) &&
               Objects.equals(repo, that.repo) &&
               Objects.equals(appIdentity, that.appIdentity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(project, repo, appIdentity);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("project", project)
                          .add("repo", repo)
                          .add("appIdentity", appIdentity)
                          .toString();
    }
}
