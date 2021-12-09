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
package com.linecorp.centraldogma.client;

import java.util.concurrent.TimeUnit;

final class WatchConstants {

    static final long DEFAULT_WATCH_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(1);
    static final boolean DEFAULT_WATCH_ERROR_ON_ENTRY_NOT_FOUND = false;
    static final int RECOMMENDED_AWAIT_TIMEOUT_SECONDS = 20;

    private WatchConstants() {}
}
