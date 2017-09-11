/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.admin.exception;

/**
 * Central Dogma Admin's root exception.
 */
public class CentralDogmaAdminException extends RuntimeException {
    private static final long serialVersionUID = -4661670895054392687L;

    private final AdminErrorCode errorCode;

    public AdminErrorCode getErrorCode() {
        return errorCode;
    }

    public CentralDogmaAdminException(String message) {
        this(message, AdminErrorCode.INTERNAL_SERVER_ERROR);
    }

    public CentralDogmaAdminException(String message, Throwable cause) {
        this(message, cause, AdminErrorCode.INTERNAL_SERVER_ERROR);
    }

    protected CentralDogmaAdminException(String message, AdminErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    protected CentralDogmaAdminException(String message, Throwable cause, AdminErrorCode errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
