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
package com.linecorp.centraldogma.server.credential;

/**
 * The type of {@link Credential}.
 */
public enum CredentialType {

    /**
     * A credential that consists of a username and a password.
     */
    PASSWORD,

    /**
     * A credential that consists of a public and private key.
     */
    SSH_KEY,

    /**
     * A credential that consists of an access token.
     */
    ACCESS_TOKEN,

    /**
     * A none credential.
     */
    NONE
}
