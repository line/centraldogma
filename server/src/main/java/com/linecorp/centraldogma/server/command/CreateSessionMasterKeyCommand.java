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
package com.linecorp.centraldogma.server.command;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.auth.SessionMasterKey;

/**
 * A {@link Command} which is used for creating a new session.
 */
public final class CreateSessionMasterKeyCommand extends SessionCommand {

    private final SessionMasterKey sessionMasterKey;

    @JsonCreator
    CreateSessionMasterKeyCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                                  @JsonProperty("author") @Nullable Author author,
                                  @JsonProperty("sessionMasterKey") SessionMasterKey sessionMasterKey) {
        super(CommandType.CREATE_SESSION_MASTER_KEY, timestamp, author);
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
    ToStringHelper toStringHelper() {
        return super.toStringHelper().add("sessionMasterKey", sessionMasterKey);
    }
}
