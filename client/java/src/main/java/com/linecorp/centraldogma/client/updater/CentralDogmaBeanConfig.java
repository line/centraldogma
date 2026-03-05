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

import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;

/**
 * Provides the necessary information to {@link CentralDogmaBeanFactory} so that the bean properties are
 * mirrored from a file in Central Dogma.
 *
 * @see CentralDogmaBean
 */
public final class CentralDogmaBeanConfig {

    static final CentralDogmaBeanConfig EMPTY = new CentralDogmaBeanConfigBuilder().build();

    private final String project;
    private final String repository;
    private final String path;
    private final String jsonPath;

    /**
     * Creates a new instance.
     *
     * @param project the Central Dogma project name
     * @param repository the Central Dogma repository name
     * @param path the path of the file in Central Dogma
     * @param jsonPath the JSON path expression that will be evaluated when retrieving the file
     */
    public CentralDogmaBeanConfig(@Nullable String project, @Nullable String repository,
                                  @Nullable String path, @Nullable String jsonPath) {

        this.project = Strings.emptyToNull(project);
        this.repository = Strings.emptyToNull(repository);
        this.path = Strings.emptyToNull(path);
        this.jsonPath = Strings.emptyToNull(jsonPath);

        if (this.path != null) {
            validateFilePath(this.path, "path");
        }
    }

    /**
     * Returns the Central Dogma project name.
     *
     * @return {@link Optional#empty()} if the project name is unspecified
     */
    public Optional<String> project() {
        return Optional.ofNullable(project);
    }

    /**
     * Returns the Central Dogma repository name.
     *
     * @return {@link Optional#empty()} if the repository name is unspecified
     */
    public Optional<String> repository() {
        return Optional.ofNullable(repository);
    }

    /**
     * Returns the path of the file in Central Dogma.
     *
     * @return {@link Optional#empty()} if the path is unspecified
     */
    public Optional<String> path() {
        return Optional.ofNullable(path);
    }

    /**
     * Returns the JSON path expression that will be evaluated when retrieving the file.
     *
     * @return {@link Optional#empty()} if the JSON path expression is unspecified
     */
    public Optional<String> jsonPath() {
        return Optional.ofNullable(jsonPath);
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
        return Objects.equals(project, other.project) &&
               Objects.equals(repository, other.repository) &&
               Objects.equals(path, other.path) &&
               Objects.equals(jsonPath, other.jsonPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(project, repository, path, jsonPath);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("project", project)
                          .add("repository", repository)
                          .add("path", path)
                          .add("jsonPath", jsonPath)
                          .toString();
    }
}
