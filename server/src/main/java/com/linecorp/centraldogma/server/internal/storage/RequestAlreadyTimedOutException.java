/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.storage;

/**
 * Indicates that the request has already timed out.
 */
public class RequestAlreadyTimedOutException extends IllegalStateException {

    private static final long serialVersionUID = -7292042179379070882L;

    public RequestAlreadyTimedOutException() {
    }

    public RequestAlreadyTimedOutException(String message) {
        super(message);
    }

    public RequestAlreadyTimedOutException(String message, Throwable cause) {
        super(message, cause);
    }

    public RequestAlreadyTimedOutException(Throwable cause) {
        super(cause);
    }
}
