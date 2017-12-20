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
package com.linecorp.centraldogma.server.internal.command;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;

public final class RemoveSessionCommand extends SessionCommand {

    private final String sessionId;

    @JsonCreator
    RemoveSessionCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                         @JsonProperty("author") @Nullable Author author,
                         @JsonProperty("sessionId") String sessionId) {
        super(CommandType.REMOVE_SESSION, timestamp, author);
        this.sessionId = requireNonNull(sessionId, "sessionId");
    }

    @JsonProperty("sessionId")
    public String sessionId() {
        return sessionId;
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper().add("sessionId", sessionId);
    }
}
