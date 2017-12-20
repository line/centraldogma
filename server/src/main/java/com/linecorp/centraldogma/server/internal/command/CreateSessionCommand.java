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

import org.apache.shiro.session.mgt.SimpleSession;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.support.shiro.SimpleSessionJsonDeserializer;
import com.linecorp.centraldogma.server.support.shiro.SimpleSessionJsonSerializer;

public final class CreateSessionCommand extends SessionCommand {

    private final SimpleSession session;

    @JsonCreator
    CreateSessionCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                         @JsonProperty("author") @Nullable Author author,
                         @JsonProperty("session")
                         @JsonDeserialize(using = SimpleSessionJsonDeserializer.class)
                         SimpleSession session) {

        super(CommandType.CREATE_SESSION, timestamp, author);
        this.session = requireNonNull(session, "session");
    }

    @JsonProperty("session")
    @JsonSerialize(using = SimpleSessionJsonSerializer.class)
    public SimpleSession session() {
        return session;
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper().add("session", session);
    }
}
