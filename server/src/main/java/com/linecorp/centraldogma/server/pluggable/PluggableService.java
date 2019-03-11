/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.centraldogma.server.pluggable;

import java.util.concurrent.CompletionStage;

/**
 * An interface which defines callbacks invoked when a pluggable service is supposed to be started/stopped.
 * Note that currently only {@link LeaderService} is available to be plugged-in.
 *
 * @see LeaderService
 */
public interface PluggableService {
    /**
     * Invoked when this service is supposed to be started.
     */
    CompletionStage<Void> start();

    /**
     * Invoked when this service is supposed to be stopped.
     */
    CompletionStage<Void> stop();
}
