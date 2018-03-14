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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.annotation.ConsumeType;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.RequestObject;
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
        return new ServerStatus(executor().isStarted());
    }

    /**
     * PATCH /status
     *
     * <p>Patches the server status with a JSON patch. Currently used only for entering read-only.
     */
    @Patch("/status")
    @ConsumeType("application/json-patch+json")
    @Decorator(AdministratorsOnly.class)
    public ServerStatus updateStatus(@RequestObject JsonNode patch) throws Exception {
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
        if (!writableNode.isBoolean()) {
            return rejectStatusPatch(patch);
        }

        if (writableNode.asBoolean()) {
            if (!oldStatus.writable) {
                // TODO(trustin): Implement exiting read-only mode.
                throw HttpStatusException.of(HttpStatus.NOT_IMPLEMENTED);
            }
        } else {
            if (oldStatus.writable) {
                logger.warn("Entering read-only mode ..");
                executor().stop();
                logger.info("Entered read-only mode");
                return status();
            }
        }

        throw HttpStatusException.of(HttpStatus.NOT_MODIFIED);
    }

    private static ServerStatus rejectStatusPatch(JsonNode patch) {
        throw new IllegalArgumentException("Invalid JSON patch: " + patch);
    }

    // TODO(trustin): Add more properties, e.g. method, host name, isLeader and config.
    private static final class ServerStatus {
        @JsonProperty
        final boolean writable;

        ServerStatus(boolean writable) {
            this.writable = writable;
        }
    }
}
