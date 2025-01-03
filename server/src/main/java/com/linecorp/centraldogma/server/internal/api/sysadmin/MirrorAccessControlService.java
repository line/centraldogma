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

package com.linecorp.centraldogma.server.internal.api.sysadmin;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.AbstractService;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresSystemAdministrator;
import com.linecorp.centraldogma.server.internal.mirror.DefaultMirrorAccessController;
import com.linecorp.centraldogma.server.internal.mirror.MirrorAccessControl;

/**
 * A service which provides the API for managing the mirror access control.
 */
@ProducesJson
@ConsumesJson
@RequiresSystemAdministrator
public final class MirrorAccessControlService extends AbstractService {

    public static final String MIRROR_ACCESS_CONTROL_PATH = "/mirror-access-control/";

    private final DefaultMirrorAccessController accessController;

    public MirrorAccessControlService(CommandExecutor executor,
                                      DefaultMirrorAccessController accessController) {
        super(executor);
        this.accessController = requireNonNull(accessController, "accessController");
    }

    /**
     * GET /mirror/access
     *
     * <p>Returns the list of mirror access control.
     */
    @Get("/mirror/access")
    public CompletableFuture<List<MirrorAccessControl>> list() {
        return accessController.list();
    }

    /**
     * POST /mirror/access
     *
     * <p>Creates a new mirror access control.
     */
    @StatusCode(201)
    @Post("/mirror/access")
    public CompletableFuture<MirrorAccessControl> create(MirrorAccessControlRequest request, Author author) {
        return accessController.add(request, author);
    }

    /**
     * PUT /mirror/access
     *
     * <p>Updates the mirror access control.
     */
    @Put("/mirror/access")
    public CompletableFuture<MirrorAccessControl> update(MirrorAccessControlRequest request, Author author) {
        return accessController.update(request, author);
    }

    /**
     * GET /mirror/access/{id}
     *
     * <p>Returns the mirror access control.
     */
    @Get("/mirror/access/{id}")
    public CompletableFuture<MirrorAccessControl> get(@Param String id) {
        return accessController.get(id);
    }

    /**
     * DELETE /mirror/access/{id}
     *
     * <p>Deletes the mirror access control.
     */
    @Delete("/mirror/access/{id}")
    public CompletableFuture<Void> delete(@Param String id, Author author) {
        return accessController.delete(id, author);
    }
}
