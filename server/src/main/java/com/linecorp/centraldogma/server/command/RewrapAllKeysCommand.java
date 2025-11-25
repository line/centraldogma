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
package com.linecorp.centraldogma.server.command;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.EncryptionConfig;

/**
 * A {@link Command} that rewraps all wrapped data encryption keys (WDEKs) and session master keys
 * with the {@link EncryptionConfig#kekId()} specified in the configuration.
 */
public final class RewrapAllKeysCommand extends RootCommand<Void> {

    @JsonCreator
    RewrapAllKeysCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                         @JsonProperty("author") @Nullable Author author) {
        super(CommandType.REWRAP_ALL_KEYS, timestamp, author);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof RewrapAllKeysCommand)) {
            return false;
        }

        final RewrapAllKeysCommand that = (RewrapAllKeysCommand) obj;
        return super.equals(that);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
