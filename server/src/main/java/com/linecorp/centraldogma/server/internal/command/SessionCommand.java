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

import javax.annotation.Nullable;

import com.linecorp.centraldogma.common.Author;

public abstract class SessionCommand extends AbstractCommand<Void> {
    SessionCommand(CommandType type, @Nullable Long timestamp, @Nullable Author author) {
        super(type, timestamp, author);
    }

    @Override
    public final String executionPath() {
        return "/_sessions";
    }
}
