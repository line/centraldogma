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

package com.linecorp.centraldogma.common;

/**
 * A {@link CentralDogmaException} that is raised when a request has timed out.
 */
public final class ApiRequestTimeoutException extends CentralDogmaException {

    private static final long serialVersionUID = -1921146517835662972L;

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public ApiRequestTimeoutException(String message) {
        super(message);
    }

    /**
     * Creates a new instance with the specified {@code message} and {@code cause}.
     */
    public ApiRequestTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
