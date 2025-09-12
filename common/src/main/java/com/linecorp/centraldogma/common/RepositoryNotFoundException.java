/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.centraldogma.common;

import static java.util.Objects.requireNonNull;

/**
 * A {@link CentralDogmaException} that is raised when attempted to access a non-existent repository.
 */
public class RepositoryNotFoundException extends CentralDogmaException {

    private static final long serialVersionUID = -510760590602000467L;

    /**
     * Creates a new instance.
     */
    public static RepositoryNotFoundException of(String projectName, String repositoryName) {
        requireNonNull(projectName, "projectName");
        requireNonNull(repositoryName, "repositoryName");
        return new RepositoryNotFoundException("repository not found: " + projectName + '/' + repositoryName);
    }

    /**
     * Creates a new instance.
     */
    public RepositoryNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates a new instance.
     *
     * @param message the detail message
     * @param writableStackTrace whether or not the stack trace should be writable
     */
    public RepositoryNotFoundException(String message, boolean writableStackTrace) {
        super(message, writableStackTrace);
    }
}
