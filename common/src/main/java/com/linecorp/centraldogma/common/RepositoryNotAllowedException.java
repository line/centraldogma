/*
 * Copyright 2021 LINE Corporation
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

/**
 * A {@link CentralDogmaException} that is raised when attempted to create a file in the repository which is
 * not allowed.
 */
public class RepositoryNotAllowedException extends CentralDogmaException {

    private static final long serialVersionUID = -8681517517157384962L;

    /**
     * Creates a new instance.
     */
    public RepositoryNotAllowedException() {
        super();
    }

    /**
     * Creates a new instance.
     */
    public RepositoryNotAllowedException(String message) {
        super(message);
    }

    /**
     * Creates a new instance.
     */
    public RepositoryNotAllowedException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance.
     */
    public RepositoryNotAllowedException(Throwable cause) {
        super(cause);
    }

    /**
     * Creates a new instance.
     *
     * @param message the detail message
     * @param writableStackTrace whether or not the stack trace should be writable
     */
    public RepositoryNotAllowedException(String message, boolean writableStackTrace) {
        super(message, writableStackTrace);
    }

    /**
     * Creates a new instance.
     */
    protected RepositoryNotAllowedException(String message, Throwable cause, boolean enableSuppression,
                                            boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
