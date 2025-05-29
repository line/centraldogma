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
package com.linecorp.centraldogma.server.storage.encryption;

import com.linecorp.centraldogma.server.storage.StorageException;

/**
 * A {@link StorageException} that is raised when fails to encrypt or decrypt data in the storage.
 */
public class EncryptionStorageException extends StorageException {

    private static final long serialVersionUID = -7523824004792099213L;

    /**
     * Creates a new instance.
     */
    public EncryptionStorageException() {}

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public EncryptionStorageException(String message) {
        super(message);
    }

    /**
     * Creates a new instance with the specified {@code cause}.
     */
    public EncryptionStorageException(Throwable cause) {
        super(cause);
    }

    /**
     * Creates a new instance with the specified {@code message} and {@code cause}.
     */
    public EncryptionStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance with the specified {@code message}, {@code cause}, suppression enabled or
     * disabled, and writable stack trace enabled or disabled.
     */
    protected EncryptionStorageException(String message, Throwable cause,
                                         boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
