/*
 * Copyright 2026 LY Corporation
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
package com.linecorp.centraldogma.xds.internal;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresSystemAdministrator;

/**
 * Serves read-only, system-administrator-only views of the control plane's runtime state:
 * <ul>
 *   <li>{@code GET /api/v1/xds/clients} — the ACK/NACK state of every xDS client connected to this instance,
 *       as tracked by {@link XdsClientStatusTracker}.</li>
 *   <li>{@code GET /api/v1/xds/snapshot} — the snapshot currently being served (per resource type, version
 *       plus the serialized resources), optionally scoped to a single {@code group}.</li>
 * </ul>
 *
 * <p>This exposes sensitive operational data (connected nodes and internal state), so the whole service is
 * gated behind {@link RequiresSystemAdministrator}.
 */
@ProducesJson
@RequiresSystemAdministrator
public final class XdsControlPlaneStatusService {

    private final XdsClientStatusTracker clientStatusTracker;
    private final ControlPlaneService controlPlaneService;

    XdsControlPlaneStatusService(XdsClientStatusTracker clientStatusTracker,
                                 ControlPlaneService controlPlaneService) {
        this.clientStatusTracker = clientStatusTracker;
        this.controlPlaneService = controlPlaneService;
    }

    /**
     * Returns the ACK/NACK state of every xDS client connected to this control plane instance.
     */
    @Get("/xds/clients")
    public List<XdsClientStreamDto> clients() {
        return clientStatusTracker.clients();
    }

    /**
     * Lists the application identities that have connected to the discovery API, with the groups each can read.
     */
    @Get("/xds/apps")
    public List<XdsAppDto> apps() {
        return controlPlaneService.apps();
    }

    /**
     * Returns a served snapshot. When {@code appId} is set, returns the exact snapshot served to that
     * application identity; otherwise when {@code group} is set, the snapshot scoped to that group; otherwise
     * the full snapshot.
     */
    @Get("/xds/snapshot")
    public CompletableFuture<XdsSnapshotDto> snapshot(@Param @Nullable String group,
                                                      @Param @Nullable String appId) {
        return controlPlaneService.snapshot(group, appId);
    }
}
