/*
 * Copyright 2023 LINE Corporation
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
 *
 */

package com.linecorp.centraldogma.server.internal.api;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.MirrorDto;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresRole;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.ProjectRole;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;

/**
 * Annotated service object for managing mirroring service.
 */
@ProducesJson
@RequiresRole(roles = ProjectRole.OWNER)
@ExceptionHandler(HttpApiExceptionHandler.class)
public class MirroringServiceV1 extends AbstractService {

    private final MetadataService mds;

    public MirroringServiceV1(ProjectManager projectManager, CommandExecutor executor, MetadataService mds) {
        super(projectManager, executor);
        this.mds = mds;
    }

    /**
     * GET /projects/{projectName}/mirrors
     *
     * <p>Returns the list of the mirrors in the project.
     */
    @Get("/projects/{projectName}/mirrors")
    public CompletableFuture<List<MirrorDto>> listMirrors(@Param String projectName) {
        return metaRepo(projectName).mirrors(true).thenApply(mirrors -> {
            return mirrors.stream()
                          .map(mirror -> convertToMirrorDto(projectName, mirror))
                          .collect(toImmutableList());
        });
    }

    /**
     * POST /projects/{projectName}/mirrors
     *
     * <p>Creates a new mirror.
     */
    @Post("/projects/{projectName}/mirrors")
    @ConsumesJson
    @StatusCode(201)
    public CompletableFuture<Revision> createMirror(@Param String projectName, MirrorDto newMirror,
                                                    Author author) {
        return metaRepo(projectName).saveMirror(newMirror, author);
    }

    /**
     * GET /projects/{projectName}/mirrors/id
     *
     * <p>Returns the mirror at the specified index in the project mirror list.
     */
    @Get("/projects/{projectName}/mirrors/{id}")
    public CompletableFuture<MirrorDto> getMirror(@Param String projectName, @Param String id) {

        return metaRepo(projectName).mirror(id).thenApply(mirror -> {
            return convertToMirrorDto(projectName, mirror);
        });
    }

    /**
     * PUT /projects/{projectName}/mirrors
     *
     * <p>Update the exising mirror.
     */
    @Put("/projects/{projectName}/mirrors")
    @ConsumesJson
    public CompletableFuture<Revision> updateMirror(@Param String projectName, MirrorDto mirror, Author author) {
        return metaRepo(projectName).updateMirror(mirror, author);
    }

    private static MirrorDto convertToMirrorDto(String projectName, Mirror mirror) {
        final URI remoteRepoUri = mirror.remoteRepoUri();
        return new MirrorDto(mirror.id(),
                             mirror.enabled(), projectName,
                             mirror.schedule().asString(),
                             mirror.direction().name(),
                             mirror.localRepo().name(),
                             mirror.localPath(),
                             remoteRepoUri.getScheme(),
                             remoteRepoUri.getHost() + remoteRepoUri.getPath(),
                             mirror.remotePath(),
                             firstNonNull(mirror.remoteBranch(), "master"),
                             mirror.gitignore(),
                             mirror.credential().id());
    }

    private MetaRepository metaRepo(String projectName) {
        return projectManager().get(projectName).metaRepo();
    }
}