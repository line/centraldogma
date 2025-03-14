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
 *
 */

package com.linecorp.centraldogma.common;

/**
 * A {@link CentralDogmaException} that is raised when attempted to apply a text patch which cannot be applied
 * without a conflict.
 */
public final class TextPatchConflictException extends ChangeConflictException {
    private static final long serialVersionUID = -6150468151945332532L;

    /**
     * Creates a new instance.
     */
    public TextPatchConflictException(String message) {
        super(message);
    }

    /**
     * Creates a new instance with the specified {@code cause}.
     */
    public TextPatchConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
