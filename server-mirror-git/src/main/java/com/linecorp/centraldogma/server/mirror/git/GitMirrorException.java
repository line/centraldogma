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

package com.linecorp.centraldogma.server.mirror.git;

import com.linecorp.centraldogma.common.MirrorException;

/**
 * A {@link MirrorException} raised when failed to mirror a Git repository.
 */
public class GitMirrorException extends MirrorException {

    private static final long serialVersionUID = 4510614751276168395L;

    /**
     * Creates a new instance.
     */
    public GitMirrorException() {}

    /**
     * Creates a new instance.
     */
    public GitMirrorException(String message) {
        super(message);
    }

    /**
     * Creates a new instance.
     */
    public GitMirrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
