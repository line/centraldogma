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

package com.linecorp.centraldogma.server.internal.api;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.converter.HttpApiRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.HttpApiResponseConverter;

/**
 * A base service class for HTTP API.
 */
@RequestConverter(HttpApiRequestConverter.class)
@RequestConverter(JacksonRequestConverterFunction.class)
// Do not need jacksonResponseConverterFunction because HttpApiResponseConverter handles the JSON data.
@ResponseConverter(HttpApiResponseConverter.class)
public class AbstractService {

    private final CommandExecutor executor;

    protected AbstractService(CommandExecutor executor) {
        this.executor = requireNonNull(executor, "executor");
    }

    public final CommandExecutor executor() {
        return executor;
    }

    public <T> CompletableFuture<T> execute(Command<T> command) {
        return executor().execute(command);
    }
}
