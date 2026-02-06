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

import org.jspecify.annotations.Nullable;

/**
 * A {@link CentralDogmaException} that is raised when attempted to push a commit without effective changes.
 */
public class RedundantChangeException extends CentralDogmaException {

    private static final long serialVersionUID = 8739464985038079688L;
    @Nullable
    private final Revision headRevision;

    /**
     * Creates a new instance.
     */
    public RedundantChangeException(String message) {
        super(message);
        headRevision = null;
    }

    /**
     * Creates a new instance.
     */
    public RedundantChangeException(Revision headRevision, String message) {
        super(message);
        this.headRevision = requireNonNull(headRevision, "headRevision");
    }

    /**
     * Creates a new instance.
     *
     * @param message the detail message
     * @param writableStackTrace whether or not the stack trace should be writable
     */
    public RedundantChangeException(String message, boolean writableStackTrace) {
        super(message, writableStackTrace);
        headRevision = null;
    }

    /**
     * Returns the head revision of the repository when this exception was raised.
     */
    @Nullable
    public Revision headRevision() {
        return headRevision;
    }
}
