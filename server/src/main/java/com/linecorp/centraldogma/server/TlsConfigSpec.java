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

package com.linecorp.centraldogma.server;

import java.io.InputStream;

import javax.annotation.Nullable;

import com.google.errorprone.annotations.MustBeClosed;

/**
 * TLS configuration spec.
 */
public interface TlsConfigSpec {
    /**
     * Returns an {@link InputStream} of the certificate chain.
     */
    @MustBeClosed
    InputStream keyCertChainInputStream();

    /**
     * Returns an {@link InputStream} of the private key.
     */
    @MustBeClosed
    InputStream keyInputStream();

    /**
     * Returns a password for the private key file. Return {@code null} if no password is set.
     */
    @Nullable
    String keyPassword();
}
