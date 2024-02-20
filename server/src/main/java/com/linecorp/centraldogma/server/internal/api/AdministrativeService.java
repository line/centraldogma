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
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatchException;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresAdministrator;
import com.linecorp.centraldogma.server.management.ServerStatus;

@ProducesJson
public final class AdministrativeService extends AbstractService {

    public AdministrativeService(CommandExecutor executor) {
        super(executor);
    }

    /**
     * GET /status
     *
     * <p>Returns the server status.
     */
    @Get("/status")
    public ServerStatus status() {
        return new ServerStatus(executor().isWritable(), executor().isStarted());
    }

    /**
     * PATCH /status
     *
     * <p>Patches the server status with a JSON patch. Currently used only for entering read-only.
     */
    @Patch("/status")
    @Consumes("application/json-patch+json")
    @RequiresAdministrator
    public CompletableFuture<ServerStatus> updateStatus(ServiceRequestContext ctx,
                                                        JsonNode patch) throws Exception {
        // TODO(trustin): Consider extracting this into common utility or Armeria.
        final ServerStatus oldStatus = status();
        final JsonNode oldValue = Jackson.valueToTree(oldStatus);
        final JsonNode newValue;
        try {
            newValue = JsonPatch.fromJson(patch).apply(oldValue);
        } catch (JsonPatchException e) {
            // Failed to apply the given JSON patch.
            return rejectStatusPatch(patch);
        }

        if (!newValue.isObject()) {
            return rejectStatusPatch(patch);
        }

        final JsonNode writableNode = newValue.get("writable");
        final JsonNode replicatingNode = newValue.get("replicating");
        if (!writableNode.isBoolean() || !replicatingNode.isBoolean()) {
            return rejectStatusPatch(patch);
        }

        //    writable       | replicating   |  result
        //-------------------+---------------+-----------------------------------
        //    true -> true   | true -> true  | not_modified exception
        //                   | true -> false | bad_request exception
        //-------------------+---------------+-----------------------------------
        //    true -> false  | true -> true  | setWritable(false)
        //                   | true -> false | setWritable(false) & stop executor.
        //-------------------+---------------+-----------------------------------
        //    false -> true  | true -> true  | setWritable(true)
        //                   | false -> true | setWritable(true) & start executor.
        //-------------------+---------------+-----------------------------------
        //    false -> false | true -> false | Stop executor.
        //                   | false -> true | Start executor.
        final boolean writable = writableNode.asBoolean();
        final boolean replicating = replicatingNode.asBoolean();
        if (writable && !replicating) {
            return HttpApiUtil.throwResponse(ctx, HttpStatus.BAD_REQUEST,
                                             "'replicating' must be 'true' if 'writable' is 'true'.");
        }
        if (oldStatus.writable() == writable && oldStatus.replicating() == replicating) {
            throw HttpStatusException.of(HttpStatus.NOT_MODIFIED);
        }

        return execute(Command.updateServerStatus(writable, replicating))
                .thenApply(unused -> status());
    }

    private static CompletableFuture<ServerStatus> rejectStatusPatch(JsonNode patch) {
        throw new IllegalArgumentException("Invalid JSON patch: " + patch);
    }
}
