/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
 * A {@link CentralDogmaException} raised when a watch cannot be answered because a recovery rewrote the
 * repository: the revision it waits for belongs to a history that no longer exists, so it has to watch
 * again.
 */
public class RepositoryRecoveryException extends CentralDogmaException {

    private static final long serialVersionUID = 6273683417359536108L;

    /**
     * Creates a new instance.
     */
    public RepositoryRecoveryException(String message) {
        super(message);
    }
}
