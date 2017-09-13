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
import static java.util.Objects.requireNonNull;

import com.google.common.base.Strings;

/**
 * Builds a {@link CentralDogmaBeanConfig}.
 */
public final class CentralDogmaBeanConfigBuilder {

    private String project;
    private String repository;
    private String path;
    private String jsonPath;

    /**
     * Creates a new builder whose all properties are unspecified.
     */
    public CentralDogmaBeanConfigBuilder() {}

    /**
     * Creates a new builder from an existing {@link CentralDogmaBeanConfig}. This method is a shortcut of
     * {@code new CentralDogmaBeanConfigBuilder().merge(config)}
     */
    public CentralDogmaBeanConfigBuilder(CentralDogmaBeanConfig config) {
        merge(config);
    }

    /**
     * Creates a new builder from the properties of a {@link CentralDogmaBean} annotation.
     */
    public CentralDogmaBeanConfigBuilder(CentralDogmaBean config) {
        project = Strings.emptyToNull(config.project());
        repository = Strings.emptyToNull(config.repository());
        path = Strings.emptyToNull(config.path());
        jsonPath = Strings.emptyToNull(config.jsonPath());
    }

    /**
     * Merges the properties of the specified {@link CentralDogmaBeanConfig} into this builder.
     */
    public CentralDogmaBeanConfigBuilder merge(CentralDogmaBeanConfig config) {
        config.project().ifPresent(this::project);
        config.repository().ifPresent(this::repository);
        config.path().ifPresent(this::path);
        config.jsonPath().ifPresent(this::jsonPath);
        return this;
    }

    /**
     * Sets the Central Dogma project name.
     */
    public CentralDogmaBeanConfigBuilder project(String project) {
        this.project = requireNonNull(project, "project");
        return this;
    }

    /**
     * Sets the Central Dogma repository name.
     */
    public CentralDogmaBeanConfigBuilder repository(String repository) {
        this.repository = requireNonNull(repository, "repository");
        return this;
    }

    /**
     * Sets the path of the file in Central Dogma.
     */
    public CentralDogmaBeanConfigBuilder path(String path) {
        this.path = validateFilePath(path, "path");
        return this;
    }

    /**
     * Sets the JSON path expression that will be evaluated when retrieving the file.
     */
    public CentralDogmaBeanConfigBuilder jsonPath(String jsonPath) {
        this.jsonPath = requireNonNull(jsonPath, "jsonPath");
        return this;
    }

    /**
     * Returns a newly-created {@link CentralDogmaBeanConfig}.
     */
    public CentralDogmaBeanConfig build() {
        return new CentralDogmaBeanConfig(project, repository, path, jsonPath);
    }
}
