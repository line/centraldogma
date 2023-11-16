/*
 * Copyright 2023 LINE Corporation
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

class ForcePushCommand<T> extends AdministrativeCommand<T> {

    private final Command<T> delegate;

    ForcePushCommand(Command<T> delegate) {
        super(CommandType.FORCE_PUSH, delegate.timestamp(), delegate.author());
        this.delegate = delegate;
    }

    Command<T> unwrap() {
        return delegate;
    }
}
