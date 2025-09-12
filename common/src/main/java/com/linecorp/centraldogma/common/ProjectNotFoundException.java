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
 * A {@link CentralDogmaException} that is raised when attempted to access a non-existent project.
 */
public class ProjectNotFoundException extends CentralDogmaException {

    private static final long serialVersionUID = 6885733290564357055L;

    /**
     * Creates a new instance.
     */
    public static ProjectNotFoundException of(String projectName) {
        requireNonNull(projectName, "projectName");
        return new ProjectNotFoundException("project not found: " + projectName);
    }

    /**
     * Creates a new instance.
     */
    public ProjectNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates a new instance.
     *
     * @param message the detail message
     * @param writableStackTrace whether or not the stack trace should be writable
     */
    public ProjectNotFoundException(String message, boolean writableStackTrace) {
        super(message, writableStackTrace);
    }
}
