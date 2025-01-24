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
import static com.linecorp.centraldogma.server.internal.mirror.DefaultMirroringServicePlugin.mirrorConfig;
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.mirrorFile;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.model.Cron;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

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
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.MirrorDto;
import com.linecorp.centraldogma.internal.api.v1.MirrorRequest;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.ZoneConfig;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresProjectRole;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresRepositoryRole;
import com.linecorp.centraldogma.server.internal.mirror.MirrorRunner;
import com.linecorp.centraldogma.server.internal.mirror.MirrorSchedulingService;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectApiManager;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorAccessController;
import com.linecorp.centraldogma.server.mirror.MirrorListener;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * Annotated service object for managing mirroring service.
 */
@ProducesJson
public class MirroringServiceV1 extends AbstractService {

    private static final Logger logger = LoggerFactory.getLogger(MirroringServiceV1.class);

    // TODO(ikhoon):
    //  - Write documentation for the REST API specification
    //  - Add Java APIs to the CentralDogma client

    private final ProjectApiManager projectApiManager;
    private final MirrorRunner mirrorRunner;
    private final Map<String, Object> mirrorZoneConfig;
    @Nullable
    private final ZoneConfig zoneConfig;
    private final MirrorAccessController accessController;

    public MirroringServiceV1(ProjectApiManager projectApiManager, CommandExecutor executor,
                              MirrorRunner mirrorRunner, CentralDogmaConfig config,
                              MirrorAccessController accessController) {
        super(executor);
        this.projectApiManager = projectApiManager;
        this.mirrorRunner = mirrorRunner;
        zoneConfig = config.zone();
        mirrorZoneConfig = mirrorZoneConfig(config);
        this.accessController = accessController;
    }

    private static Map<String, Object> mirrorZoneConfig(CentralDogmaConfig config) {
        final MirroringServicePluginConfig mirrorConfig = mirrorConfig(config);
        final ImmutableMap.Builder<String, Object> builder = ImmutableMap.builderWithExpectedSize(2);
        final boolean zonePinned = mirrorConfig != null && mirrorConfig.zonePinned();
        builder.put("zonePinned", zonePinned);
        final ZoneConfig zone = config.zone();
        if (zone != null) {
            builder.put("zone", zone);
        }
        return builder.build();
    }

    /**
     * GET /projects/{projectName}/mirrors
     *
     * <p>Returns the list of the mirrors in the project.
     */
    @RequiresProjectRole(ProjectRole.OWNER)
    @Get("/projects/{projectName}/mirrors")
    public CompletableFuture<List<MirrorDto>> listProjectMirrors(@Param String projectName) {
        final CompletableFuture<List<Mirror>> future = metaRepo(projectName).mirrors(true);
        return convertToMirrorDtos(projectName, future);
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/mirrors
     *
     * <p>Returns the list of the mirrors in the repository.
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Get("/projects/{projectName}/repos/{repoName}/mirrors")
    public CompletableFuture<List<MirrorDto>> listRepoMirrors(@Param String projectName,
                                                              Repository repository) {
        final CompletableFuture<List<Mirror>> future = metaRepo(projectName).mirrors(repository.name(), true);
        return convertToMirrorDtos(projectName, future);
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/mirrors/{id}
     *
     * <p>Returns the mirror of the ID in the project mirror list.
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Get("/projects/{projectName}/repos/{repoName}/mirrors/{id}")
    public CompletableFuture<MirrorDto> getMirror(@Param String projectName,
                                                  Repository repository,
                                                  @Param String id) {
        return metaRepo(projectName).mirror(repository.name(), id).thenCompose(mirror -> {
            return accessController.isAllowed(mirror.remoteRepoUri()).thenApply(allowed -> {
                return convertToMirrorDto(projectName, mirror, allowed);
            });
        });
    }

    /**
     * POST /projects/{projectName}/repos/{repoName}/mirrors
     *
     * <p>Creates a new mirror.
     */
    @Post("/projects/{projectName}/repos/{repoName}/mirrors")
    @ConsumesJson
    @StatusCode(201)
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    public CompletableFuture<PushResultDto> createMirror(@Param String projectName,
                                                         Repository repository,
                                                         MirrorRequest newMirror,
                                                         Author author) {
        return createOrUpdate(projectName, repository.name(), newMirror, author, false);
    }

    /**
     * PUT /projects/{projectName}/repos/{repoName}/mirrors
     *
     * <p>Update the exising mirror.
     */
    @ConsumesJson
    @Put("/projects/{projectName}/repos/{repoName}/mirrors/{id}")
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    public CompletableFuture<PushResultDto> updateMirror(@Param String projectName,
                                                         Repository repository,
                                                         MirrorRequest mirror,
                                                         @Param String id, Author author) {
        checkArgument(id.equals(mirror.id()), "The mirror ID (%s) can't be updated", id);
        return createOrUpdate(projectName, repository.name(), mirror, author, true);
    }

    /**
     * DELETE /projects/{projectName}/repos/{repoName}/mirrors/{id}
     *
     * <p>Delete the existing mirror.
     */
    @Delete("/projects/{projectName}/repos/{repoName}/mirrors/{id}")
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    public CompletableFuture<Void> deleteMirror(@Param String projectName,
                                                Repository repository,
                                                @Param String id, Author author) {
        final MetaRepository metaRepository = metaRepo(projectName);
        final String repoName = repository.name();
        return metaRepository.mirror(repoName, id).thenCompose(mirror -> {
            // mirror exists.
            final Command<CommitResult> command =
                    Command.push(author, projectName, metaRepository.name(),
                                 Revision.HEAD, "Delete mirror: " + id + " in " + repoName, "",
                                 Markup.PLAINTEXT, Change.ofRemoval(mirrorFile(repoName, id)));
            return executor().execute(command).thenApply(result -> null);
        });
    }

    private CompletableFuture<PushResultDto> createOrUpdate(
            String projectName, String repoName, MirrorRequest newMirror,
            Author author, boolean update) {
        final MetaRepository metaRepo = metaRepo(projectName);
        return metaRepo.createMirrorPushCommand(repoName, newMirror, author, zoneConfig, update).thenCompose(
                command -> {
                    return executor().execute(command).thenApply(result -> {
                        metaRepo.mirror(repoName, newMirror.id(), result.revision())
                                .handle((mirror, cause) -> {
                                    if (cause != null) {
                                        // This should not happen in normal cases.
                                        logger.warn("Failed to get the mirror: {}", newMirror.id(), cause);
                                        return null;
                                    }
                                    return notifyMirrorEvent(mirror, update);
                                });
                        return new PushResultDto(result.revision(), command.timestamp());
                    });
                });
    }

    private Void notifyMirrorEvent(Mirror mirror, boolean update) {
        try {
            final MirrorListener listener = MirrorSchedulingService.mirrorListener();
            if (update) {
                listener.onUpdate(mirror, accessController);
            } else {
                listener.onCreate(mirror, accessController);
            }
        } catch (Throwable ex) {
            logger.warn("Failed to notify the mirror listener. (mirror: {})", mirror, ex);
        }
        return null;
    }

    /**
     * POST /projects/{projectName}/repos/{repoName}/mirrors/{mirrorId}/run
     *
     * <p>Runs the mirroring task immediately.
     */
    // Mirroring may be a long-running task, so we need to increase the timeout.
    @RequestTimeout(value = 5, unit = TimeUnit.MINUTES)
    @Post("/projects/{projectName}/repos/{repoName}/mirrors/{mirrorId}/run")
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    public CompletableFuture<MirrorResult> runMirror(@Param String projectName,
                                                     Repository repository,
                                                     @Param String mirrorId,
                                                     User user) throws Exception {
        return mirrorRunner.run(projectName, repository.name(), mirrorId, user);
    }

    /**
     * GET /mirror/config
     *
     * <p>Returns the configuration of the mirroring service.
     */
    @Get("/mirror/config")
    public Map<String, Object> config() {
        // TODO(ikhoon): Add more configurations if necessary.
        return mirrorZoneConfig;
    }

    private CompletableFuture<List<MirrorDto>> convertToMirrorDtos(
            String projectName, CompletableFuture<List<Mirror>> future) {
        return future.thenCompose(mirrors -> {
            final ImmutableList<String> remoteUris = mirrors.stream().map(
                    mirror -> mirror.remoteRepoUri().toString()).collect(
                    toImmutableList());
            return accessController.isAllowed(remoteUris).thenApply(acl -> {
                return mirrors.stream()
                              .map(mirror -> convertToMirrorDto(projectName, mirror, acl))
                              .collect(toImmutableList());
            });
        });
    }

    private static MirrorDto convertToMirrorDto(String projectName, Mirror mirror, Map<String, Boolean> acl) {
        final boolean allowed = acl.get(mirror.remoteRepoUri().toString());
        return convertToMirrorDto(projectName, mirror, allowed);
    }

    private static MirrorDto convertToMirrorDto(String projectName, Mirror mirror, boolean allowed) {
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
                             remoteRepoUri.getAuthority() + remoteRepoUri.getPath(),
                             mirror.remotePath(),
                             mirror.remoteBranch(),
                             mirror.gitignore(),
                             mirror.credential().name(), mirror.zone(), allowed);
    }

    private MetaRepository metaRepo(String projectName) {
        return projectApiManager.getProject(projectName).metaRepo();
    }
}
