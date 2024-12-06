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

package com.linecorp.centraldogma.server.storage.project;

import static com.linecorp.centraldogma.server.storage.project.ProjectUtil.internalRepos;
import static java.util.Objects.requireNonNull;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Ascii;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

/**
 * A top-level element in Central Dogma storage model. A project has {@code "dogma"} and {@code "meta"}
 * repositories by default which contain project configuration files accessible by system administrators
 * and project owners respectively.
 */
public interface Project {
    /**
     * The repository that contains project configuration files, which are accessible by system administrators.
     */
    String REPO_DOGMA = "dogma";

    /**
     * The repository that contains project configuration files, which are accessible by project owners.
     */
    String REPO_META = "meta";

    /**
     * Returns the name of this project.
     */
    String name();

    /**
     * Returns the creation time of this project, in milliseconds.
     */
    long creationTimeMillis();

    /**
     * Returns the author who initially created this project.
     */
    Author author();

    /**
     * Returns the {@link MetaRepository} of this project.
     */
    MetaRepository metaRepo();

    /**
     * Returns the {@link RepositoryManager} of this project.
     */
    RepositoryManager repos();

    /**
     * Returns the {@link ProjectMetadata} of this project.
     * {@code null} if the project is internal.
     */
    @Nullable
    ProjectMetadata metadata();

    /**
     * Returns the list of internal repositories which are {@link #REPO_DOGMA} and {@link #REPO_META}.
     */
    static List<String> internalRepos() {
        return internalRepos;
    }

    /**
     * Returns {@code true} if the specified repository name is reserved by Central Dogma.
     */
    static boolean isReservedRepoName(String repoName) {
        requireNonNull(repoName, "repoName");
        repoName = Ascii.toLowerCase(repoName);
        return internalRepos().contains(repoName);
    }
}
