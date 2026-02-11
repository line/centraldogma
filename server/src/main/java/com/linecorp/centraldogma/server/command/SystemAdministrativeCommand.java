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

import org.jspecify.annotations.Nullable;

import com.linecorp.centraldogma.common.Author;

abstract class SystemAdministrativeCommand<T> extends RootCommand<T> {
    SystemAdministrativeCommand(CommandType commandType, @Nullable Long timestamp,
                                @Nullable Author author) {
        super(commandType, timestamp, author);
    }
}
