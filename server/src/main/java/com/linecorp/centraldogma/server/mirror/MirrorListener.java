/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.centraldogma.server.mirror;

import java.util.ServiceLoader;

import javax.annotation.Nullable;

/**
 * A listener which is notified when a {@link Mirror} operation is started, completed or failed.
 *
 * <p>Implement this interface and register it using {@link ServiceLoader} to override the default behavior:
 * <ul>
 *     <li>{@link #onStart(Mirror)}: Logs an info message for the start of {@link Mirror}.</li>
 *     <li>{@link #onComplete(Mirror, MirrorResult)}: Does nothing.</li>
 *     <li>{@link #onError(Mirror, Throwable)}: Logs a warning message for the error of {@link Mirror}.</li>
 * </ul>
 */
@Nullable
public interface MirrorListener {

    /**
     * Invoked when the {@link Mirror} operation is started.
     */
    void onStart(Mirror mirror);

    /**
     * Invoked when the {@link Mirror} operation is completed.
     */
    void onComplete(Mirror mirror, MirrorResult result);

    /**
     * Invoked when the {@link Mirror} operation is failed.
     */
    void onError(Mirror mirror, Throwable cause);
}
