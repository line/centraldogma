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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatchException;
import com.linecorp.centraldogma.server.internal.api.auth.AdministratorsOnly;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

public final class AdministrativeService extends AbstractService {

    private static final Logger logger = LoggerFactory.getLogger(AdministrativeService.class);

    public AdministrativeService(ProjectManager projectManager, CommandExecutor executor) {
        super(projectManager, executor);
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
    @Decorator(AdministratorsOnly.class)
    public CompletableFuture<ServerStatus> updateStatus(JsonNode patch) throws Exception {
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

        final boolean writable = writableNode.asBoolean();
        final boolean replicating = replicatingNode.asBoolean();
        if (writable && !replicating) {
            return HttpApiUtil.throwResponse(HttpStatus.BAD_REQUEST,
                                             "'replicating' must be 'true' if 'writable' is 'true'.");
        }

        if (oldStatus.writable) {
            if (!writable) { // writable -> unwritable
                executor().setWritable(false);
                if (replicating) {
                    logger.warn("Entered read-only mode with replication enabled");
                    return CompletableFuture.completedFuture(status());
                } else {
                    logger.warn("Entering read-only mode with replication disabled ..");
                    return executor().stop().handle((unused, cause) -> {
                        if (cause != null) {
                            logger.warn("Failed to stop the command executor:", cause);
                        } else {
                            logger.info("Entered read-only mode with replication disabled");
                        }
                        return status();
                    });
                }
            }
        } else if (writable) { // unwritable -> writable
            logger.warn("Leaving read-only mode ..");
            executor().setWritable(true);
            return executor().start().handle((unused, cause) -> {
                if (cause != null) {
                    logger.warn("Failed to leave read-only mode:", cause);
                } else {
                    logger.info("Left read-only mode");
                }
                return status();
            });
        }

        throw HttpStatusException.of(HttpStatus.NOT_MODIFIED);
    }

    private static CompletableFuture<ServerStatus> rejectStatusPatch(JsonNode patch) {
        throw new IllegalArgumentException("Invalid JSON patch: " + patch);
    }

    // TODO(trustin): Add more properties, e.g. method, host name, isLeader and config.
    private static final class ServerStatus {
        @JsonProperty
        final boolean writable;
        @JsonProperty
        final boolean replicating;

        ServerStatus(boolean writable, boolean replicating) {
            assert !writable || replicating; // replicating must be true if writable is true.
            this.writable = writable;
            this.replicating = replicating;
        }
    }
}
