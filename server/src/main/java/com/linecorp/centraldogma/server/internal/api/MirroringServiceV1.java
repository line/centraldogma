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
 */

package com.linecorp.centraldogma.server.internal.api;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.mirrorFile;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.cronutils.model.Cron;

import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.armeria.server.annotation.decorator.RequestTimeout;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.MirrorDto;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresReadPermission;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresWritePermission;
import com.linecorp.centraldogma.server.internal.mirror.MirrorRunner;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectApiManager;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;

/**
 * Annotated service object for managing mirroring service.
 */
@ProducesJson
public class MirroringServiceV1 extends AbstractService {

    // TODO(ikhoon):
    //  - Write documentation for the REST API specification
    //  - Add Java APIs to the CentralDogma client

    private final ProjectApiManager projectApiManager;
    private final MirrorRunner mirrorRunner;

    public MirroringServiceV1(ProjectApiManager projectApiManager, CommandExecutor executor,
                              MirrorRunner mirrorRunner) {
        super(executor);
        this.projectApiManager = projectApiManager;
        this.mirrorRunner = mirrorRunner;
    }

    /**
     * GET /projects/{projectName}/mirrors
     *
     * <p>Returns the list of the mirrors in the project.
     */
    @RequiresReadPermission(repository = Project.REPO_META)
    @Get("/projects/{projectName}/mirrors")
    public CompletableFuture<List<MirrorDto>> listMirrors(@Param String projectName) {
        return metaRepo(projectName).mirrors(true).thenApply(mirrors -> {
            return mirrors.stream()
                          .map(mirror -> convertToMirrorDto(projectName, mirror))
                          .collect(toImmutableList());
        });
    }

    /**
     * GET /projects/{projectName}/mirrors/{id}
     *
     * <p>Returns the mirror of the ID in the project mirror list.
     */
    @RequiresReadPermission(repository = Project.REPO_META)
    @Get("/projects/{projectName}/mirrors/{id}")
    public CompletableFuture<MirrorDto> getMirror(@Param String projectName, @Param String id) {
        return metaRepo(projectName).mirror(id).thenApply(mirror -> {
            return convertToMirrorDto(projectName, mirror);
        });
    }

    /**
     * POST /projects/{projectName}/mirrors
     *
     * <p>Creates a new mirror.
     */
    @RequiresWritePermission(repository = Project.REPO_META)
    @Post("/projects/{projectName}/mirrors")
    @ConsumesJson
    @StatusCode(201)
    public CompletableFuture<PushResultDto> createMirror(@Param String projectName, MirrorDto newMirror,
                                                         Author author) {
        return createOrUpdate(projectName, newMirror, author, false);
    }

    /**
     * PUT /projects/{projectName}/mirrors
     *
     * <p>Update the exising mirror.
     */
    @RequiresWritePermission(repository = Project.REPO_META)
    @Put("/projects/{projectName}/mirrors/{id}")
    @ConsumesJson
    public CompletableFuture<PushResultDto> updateMirror(@Param String projectName, MirrorDto mirror,
                                                         @Param String id, Author author) {
        checkArgument(id.equals(mirror.id()), "The mirror ID (%s) can't be updated", id);
        return createOrUpdate(projectName, mirror, author, true);
    }

    /**
     * DELETE /projects/{projectName}/mirrors/{id}
     *
     * <p>Delete the existing mirror.
     */
    @RequiresWritePermission(repository = Project.REPO_META)
    @Delete("/projects/{projectName}/mirrors/{id}")
    public CompletableFuture<Void> deleteMirror(@Param String projectName,
                                                @Param String id, Author author) {
        final MetaRepository metaRepository = metaRepo(projectName);
        return metaRepository.mirror(id).thenCompose(mirror -> {
            // mirror exists.
            final Command<CommitResult> command =
                    Command.push(author, projectName, metaRepository.name(),
                                 Revision.HEAD, "Delete mirror: " + id, "",
                                 Markup.PLAINTEXT, Change.ofRemoval(mirrorFile(id)));
            return executor().execute(command).thenApply(result -> null);
        });
    }

    private CompletableFuture<PushResultDto> createOrUpdate(String projectName,
                                                            MirrorDto newMirror,
                                                            Author author, boolean update) {
        return metaRepo(projectName).createMirrorPushCommand(newMirror, author, update).thenCompose(command -> {
            return executor().execute(command).thenApply(result -> {
                return new PushResultDto(result.revision(), command.timestamp());
            });
        });
    }

    /**
     * POST /projects/{projectName}/mirrors/{mirrorId}/run
     *
     * <p>Runs the mirroring task immediately.
     */
    @RequiresWritePermission(repository = Project.REPO_META)
    // Mirroring may be a long-running task, so we need to increase the timeout.
    @RequestTimeout(value = 5, unit = TimeUnit.MINUTES)
    @Post("/projects/{projectName}/mirrors/{mirrorId}/run")
    public CompletableFuture<MirrorResult> runMirror(@Param String projectName, @Param String mirrorId,
                                                     User user) throws Exception {
        return mirrorRunner.run(projectName, mirrorId, user);
    }

    private static MirrorDto convertToMirrorDto(String projectName, Mirror mirror) {
        final URI remoteRepoUri = mirror.remoteRepoUri();
        final Cron schedule = mirror.schedule();
        final String scheduleStr = schedule != null ? schedule.asString() : null;
        return new MirrorDto(mirror.id(),
                             mirror.enabled(), projectName,
                             scheduleStr,
                             mirror.direction().name(),
                             mirror.localRepo().name(),
                             mirror.localPath(),
                             remoteRepoUri.getScheme(),
                             remoteRepoUri.getHost() + remoteRepoUri.getPath(),
                             mirror.remotePath(),
                             mirror.remoteBranch(),
                             mirror.gitignore(),
                             mirror.credential().id());
    }

    private MetaRepository metaRepo(String projectName) {
        return projectApiManager.getProject(projectName).metaRepo();
    }
}
