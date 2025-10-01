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
 * A {@link StorageException} that is raised when an encryption entry doesn't exist.
 */
public class EncryptionEntryNoExistException extends EncryptionStorageException {

    private static final long serialVersionUID = -7606175275282424174L;

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public EncryptionEntryNoExistException(String message) {
        super(message);
    }
}
