/*
 * Copyright 2024 LINE Corporation
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
 * A {@link CentralDogmaException} that is raised when a client does not have the required permission
 * for an operation.
 */
public final class PermissionException extends CentralDogmaException {
    private static final long serialVersionUID = -1034292242865864558L;

    /**
     * Creates a new instance.
     */
    public PermissionException() {}

    /**
     * Creates a new instance.
     */
    public PermissionException(String message) {
        super(message);
    }

    /**
     * Creates a new instance.
     */
    public PermissionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance.
     */
    public PermissionException(Throwable cause) {
        super(cause);
    }
}
