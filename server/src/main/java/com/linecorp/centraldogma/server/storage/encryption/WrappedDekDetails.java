/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.storage.encryption;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * Details of a wrapped data encryption key (DEK).
 */
public final class WrappedDekDetails {

    private final String wrappedDek;
    private final int dekVersion;
    private final String kekId;
    private final String projectName;
    private final String repoName;
    private final String creation;

    /**
     * Creates a new instance.
     */
    public WrappedDekDetails(String wrappedDek, int dekVersion, String kekId,
                             String projectName, String repoName) {
        this(wrappedDek, dekVersion, kekId, Instant.now().toString(), projectName, repoName);
    }

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public WrappedDekDetails(@JsonProperty("wrappedDek") String wrappedDek,
                             @JsonProperty("dekVersion") int dekVersion,
                             @JsonProperty("kekId") String kekId,
                             @JsonProperty("creation") String creation,
                             @JsonProperty("projectName") String projectName,
                             @JsonProperty("repoName") String repoName) {
        this.wrappedDek = requireNonNull(wrappedDek, "wrappedDek");
        this.kekId = requireNonNull(kekId, "kekId");
        checkArgument(dekVersion > 0, "dekVersion must be positive: %s", dekVersion);
        this.dekVersion = dekVersion;
        this.creation = requireNonNull(creation, "creation");
        this.projectName = requireNonNull(projectName, "projectName");
        this.repoName = requireNonNull(repoName, "repoName");
    }

    /**
     * Returns the wrapped data encryption key (DEK), encoded in Base64.
     */
    @JsonProperty
    public String wrappedDek() {
        return wrappedDek;
    }

    /**
     * Returns the version of the data encryption key (DEK).
     */
    @JsonProperty
    public int dekVersion() {
        return dekVersion;
    }

    /**
     * Returns the ID of the key encryption key (KEK) used to wrap the DEK.
     */
    @JsonProperty
    public String kekId() {
        return kekId;
    }

    /**
     * Returns the creation time of the wrapped DEK in ISO-8601 format.
     */
    @JsonProperty
    public String creation() {
        return creation;
    }

    /**
     * Returns the project name associated with this wrapped DEK.
     */
    @JsonProperty
    public String projectName() {
        return projectName;
    }

    /**
     * Returns the repository name associated with this wrapped DEK.
     */
    @JsonProperty
    public String repoName() {
        return repoName;
    }

    @Override
    public int hashCode() {
        int result = wrappedDek.hashCode();
        result = 31 * result + dekVersion;
        result = 31 * result + kekId.hashCode();
        result = 31 * result + creation.hashCode();
        result = 31 * result + projectName.hashCode();
        result = 31 * result + repoName.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof WrappedDekDetails)) {
            return false;
        }
        final WrappedDekDetails that = (WrappedDekDetails) obj;
        return dekVersion == that.dekVersion &&
               wrappedDek.equals(that.wrappedDek) &&
               kekId.equals(that.kekId) &&
               creation.equals(that.creation) &&
               projectName.equals(that.projectName) &&
               repoName.equals(that.repoName);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("wrappedDek", "****")
                          .add("dekVersion", dekVersion)
                          .add("kekId", kekId)
                          .add("creation", creation)
                          .add("projectName", projectName)
                          .add("repoName", repoName)
                          .toString();
    }
}
