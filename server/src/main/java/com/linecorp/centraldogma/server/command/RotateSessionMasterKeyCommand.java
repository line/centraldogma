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

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.server.auth.SessionMasterKey;

/**
 * A {@link Command} that rotates the session master key.
 */
public final class RotateSessionMasterKeyCommand extends SessionCommand {

    private final SessionMasterKey sessionMasterKey;

    @JsonCreator
    RotateSessionMasterKeyCommand(@JsonProperty("sessionMasterKey") SessionMasterKey sessionMasterKey) {
        super(CommandType.ROTATE_SESSION_MASTER_KEY, null, null);
        this.sessionMasterKey = requireNonNull(sessionMasterKey, "sessionMasterKey");
    }

    /**
     * Returns the session master key.
     */
    @JsonProperty
    public SessionMasterKey sessionMasterKey() {
        return sessionMasterKey;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof RotateSessionMasterKeyCommand)) {
            return false;
        }

        final RotateSessionMasterKeyCommand that = (RotateSessionMasterKeyCommand) obj;
        return super.equals(that) && sessionMasterKey.equals(that.sessionMasterKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionMasterKey) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("sessionMasterKey", sessionMasterKey);
    }
}
