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

package com.linecorp.centraldogma.server;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.management.ManagementService;

/**
 * A configuration spec for the {@link ManagementService}.
 */
public interface ManagementConfigSpec {
    String DEFAULT_PROTOCOL = "http";
    String DEFAULT_PATH = "/internal/management";

    /**
     * Returns the protocol of the management service.
     */
    SessionProtocol protocol();

    /**
     * Returns the address of the management service.
     */
    @Nullable
    String address();

    /**
     * Returns the port of the management service.
     */
    int port();

    /**
     * Returns the path of the management service.
     */
    String path();
}
