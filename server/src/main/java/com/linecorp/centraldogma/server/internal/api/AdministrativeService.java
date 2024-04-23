/*
 * Copyright 2018 LINE Corporation
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

import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.UpdateServerStatusRequest.Scope;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresAdministrator;
import com.linecorp.centraldogma.server.management.ServerStatus;
import com.linecorp.centraldogma.server.management.ServerStatusManager;

@ProducesJson
public final class AdministrativeService extends AbstractService {

    private final ServerStatusManager serverStatusManager;

    public AdministrativeService(CommandExecutor executor, ServerStatusManager serverStatusManager) {
        super(executor);
        this.serverStatusManager = serverStatusManager;
    }

    /**
     * GET /status
     *
     * <p>Returns the server status.
     */
    @Get("/status")
    public ServerStatus status() {
        return ServerStatus.of(executor().isWritable(), executor().isStarted());
    }

    /**
     * PUT /status
     *
     * <p>Patches the server status with a JSON patch. Currently used only for entering read-only.
     *
     * <p>If {@link UpdateServerStatusRequest#scope()} is omitted, defaults to {@link Scope#ALL}.
     * If the scope is {@link Scope#ALL}, the new status is propagated to all cluster servers.
     * If the scope is {@link Scope#LOCAL}, the new status is only applied to the current server.
     */
    @Put("/status")
    @Consumes("application/json")
    @RequiresAdministrator
    public CompletableFuture<ServerStatus> updateStatus(UpdateServerStatusRequest statusRequest)
            throws Exception {
        // TODO(trustin): Consider extracting this into common utility or Armeria.
        final ServerStatus oldStatus = status();

        final ServerStatus newStatus = statusRequest.serverStatus();
        if (statusRequest.scope() == Scope.LOCAL) {
            // Validate the new status for the local scope. Other servers may have different status.
            if (oldStatus == newStatus) {
                throw HttpStatusException.of(HttpStatus.NOT_MODIFIED);
            }

            return CompletableFuture.supplyAsync(() -> {
                executor().statusManager().updateStatus(newStatus);
                serverStatusManager.updateStatus(newStatus);
                return status();
            }, serverStatusManager.sequentialExecutor());
        } else {
            return execute(Command.updateServerStatus(newStatus))
                    .thenApply(unused -> status());
        }
    }

    private static CompletableFuture<ServerStatus> rejectStatusPatch(JsonNode patch) {
        throw new IllegalArgumentException("Invalid JSON patch: " + patch);
    }
}
