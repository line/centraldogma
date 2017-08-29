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

import static com.linecorp.centraldogma.common.Util.validateFilePath;
import static java.util.Objects.requireNonNull;

public class CentralDogmaBeanConfigBuilder {
    private String project;
    private String repository;
    private String path;
    private String jsonPath = "$";

    public CentralDogmaBeanConfigBuilder() {
    }

    public CentralDogmaBeanConfigBuilder(CentralDogmaBeanConfig config) {
        project = config.project().get();
        repository = config.repository().get();
        path = config.path().get();
        jsonPath = config.jsonPath().get();
    }

    public CentralDogmaBeanConfigBuilder(CentralDogmaBean config) {
        project = config.project();
        repository = config.repository();
        path = config.path();
        jsonPath = config.jsonPath();
    }

    public CentralDogmaBeanConfigBuilder add(CentralDogmaBeanConfig config) {
        project(config.project().orElse(project));
        repository(config.repository().orElse(repository));
        path(config.path().orElse(path));
        jsonPath(config.jsonPath().orElse(jsonPath));
        return this;
    }

    public CentralDogmaBeanConfigBuilder project(String project) {
        this.project = requireNonNull(project, "project");
        return this;
    }

    public CentralDogmaBeanConfigBuilder repository(String repository) {
        this.repository = requireNonNull(repository, "repository");
        return this;
    }

    public CentralDogmaBeanConfigBuilder path(String path) {
        this.path = validateFilePath(path, "path");
        return this;
    }

    public CentralDogmaBeanConfigBuilder jsonPath(String jsonPath) {
        this.jsonPath = requireNonNull(jsonPath, "jsonPath");
        return this;
    }

    public CentralDogmaBeanConfig build() {
        return new CentralDogmaBeanConfig(project, repository, path, jsonPath);
    }
}
