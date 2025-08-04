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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import javax.crypto.SecretKey;

import com.google.common.base.MoreObjects;

/**
 * A class that holds a {@link SecretKey} along with its version.
 */
public final class SecretKeyWithVersion {

    private final SecretKey secretKey;
    private final int version;

    /**
     * Creates a new instance of {@link SecretKeyWithVersion}.
     */
    public SecretKeyWithVersion(SecretKey secretKey, int version) {
        this.secretKey = requireNonNull(secretKey, "secretKey");
        checkArgument(version >= 1, "version: %s (expected: >= 1)", version);
        this.version = version;
    }

    /**
     * Returns the {@link SecretKey}.
     */
    public SecretKey secretKey() {
        return secretKey;
    }

    /**
     * Returns the version of the {@link SecretKey}.
     */
    public int version() {
        return version;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("secretKey", "****")
                          .add("version", version)
                          .toString();
    }
}
