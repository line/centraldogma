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
 * A {@link CentralDogmaException} raised when an error related to encryption key occurs.
 */
public final class EncryptionKeyException extends CentralDogmaException {

    private static final long serialVersionUID = 6559199270023178855L;

    /**
     * Creates a new instance.
     */
    public EncryptionKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
