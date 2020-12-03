/*
 * Copyright 2020 LINE Corporation
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

import javax.annotation.Nullable;

/**
 * A {@link CentralDogmaException} that is raised when a client is attempting to send requests more than
 * quota limits.
 */
public class TooManyRequestsException extends CentralDogmaException {

    private static final long serialVersionUID = 1712601138432866984L;

    @Nullable
    private String type;

    /**
     * Creates a new instance.
     */
    public TooManyRequestsException() {}

    /**
     * Creates a new instance.
     */
    public TooManyRequestsException(String type, String path, double permitsPerSecond) {
        this('\'' + path + "' (quota limit: " + permitsPerSecond + "/sec)");
        this.type = requireNonNull(type, "type");
    }

    /**
     * Creates a new instance.
     */
    public TooManyRequestsException(String message) {
        super(message);
    }

    /**
     * Creates a new instance.
     */
    public TooManyRequestsException(Throwable cause) {
        super(cause);
    }

    /**
     * Creates a new instance.
     */
    public TooManyRequestsException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance.
     *
     * @param message the detail message
     * @param writableStackTrace whether or not the stack trace should be writable
     */
    public TooManyRequestsException(String message, boolean writableStackTrace) {
        super(message, writableStackTrace);
    }

    /**
     * Creates a new instance.
     */
    protected TooManyRequestsException(String message, Throwable cause, boolean enableSuppression,
                                       boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    /**
     * Returns the {@code type} specified when creating this {@link Exception}.
     */
    @Nullable
    public String type() {
        return type;
    }
}

