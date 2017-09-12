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
package com.linecorp.centraldogma.client.updater;

import static com.linecorp.centraldogma.internal.Util.validateFilePath;

import java.util.Optional;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

/**
 * Settings for mirroring properties with CentralDogma.
 */
public final class CentralDogmaBeanConfig {
    public static final CentralDogmaBeanConfig DEFAULT = new CentralDogmaBeanConfigBuilder()
            .build();

    /**
     *  Name of the project that properties synchronize with CentralDogma.
     */
    private final Optional<String> project;

    /**
     *  Name of the repository that properties synchronize with CentralDogma.
     */
    private final Optional<String> repository;

    /**
     * Name of the path synchronize with Central Dogma.
     */
    private final Optional<String> path;

    /**
     * A JSONPath expression that will be executed when retriving the data from Central Dogma.
     */
    private final Optional<String> jsonPath;

    public CentralDogmaBeanConfig(@Nullable String project, @Nullable String repository,
                                  @Nullable String path, @Nullable String jsonPath) {
        this.project = Optional.ofNullable(project);
        this.repository = Optional.ofNullable(repository);
        this.path = Optional.ofNullable(path);
        this.jsonPath = Optional.ofNullable(jsonPath);

        if (path != null) {
            validateFilePath(path, "path");
        }
    }

    public Optional<String> project() {
        return project;
    }

    public Optional<String> repository() {
        return repository;
    }

    public Optional<String> path() {
        return path;
    }

    public Optional<String> jsonPath() {
        return jsonPath;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof CentralDogmaBeanConfig)) {
            return false;
        }
        final CentralDogmaBeanConfig other = (CentralDogmaBeanConfig) o;
        return project.equals(other.project) &&
               repository.equals(other.repository) &&
               path.equals(other.path) &&
               jsonPath.equals(other.jsonPath);
    }

    @Override
    public int hashCode() {
        return project.hashCode() * 31 + repository.hashCode() * 31 + path.hashCode() * 31 + jsonPath
                .hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("project", project.get())
                          .add("repository", repository.get())
                          .add("path", path.get())
                          .add("jsonPath", jsonPath.get())
                          .toString();
    }
}
