/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import com.linecorp.centraldogma.server.internal.command.DefaultExecutionContext;

/**
 * Provides contextual information about the execution of a {@link Command}.
 */
@SuppressWarnings("InterfaceMayBeAnnotatedFunctional")
public interface ExecutionContext {

    /**
     * Returns an empty {@link ExecutionContext}.
     */
    static ExecutionContext empty() {
        return DefaultExecutionContext.EMPTY;
    }

    /**
     * Returns {@code true} if the command is being executed as part of a replay.
     */
    boolean isReplay();
}
