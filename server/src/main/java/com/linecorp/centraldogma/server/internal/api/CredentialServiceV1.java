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
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresRole;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectApiManager;
import com.linecorp.centraldogma.server.metadata.ProjectRole;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;

/**
 * Annotated service object for managing credential service.
 */
@ProducesJson
@RequiresRole(roles = ProjectRole.OWNER)
@ExceptionHandler(HttpApiExceptionHandler.class)
public class CredentialServiceV1 extends AbstractService {

    private final ProjectApiManager projectApiManager;

    public CredentialServiceV1(ProjectApiManager projectApiManager, CommandExecutor executor) {
        super(executor);
        this.projectApiManager = projectApiManager;
    }

    /**
     * GET /projects/{projectName}/credentials
     *
     * <p>Returns the list of the credentials in the project.
     */
    @Get("/projects/{projectName}/credentials")
    public CompletableFuture<List<MirrorCredential>> listCredentials(@Param String projectName) {
        return metaRepo(projectName).credentials();
    }

    /**
     * GET /projects/{projectName}/credentials/{id}
     *
     * <p>Returns the credential for the ID in the project.
     */
    @Get("/projects/{projectName}/credentials/{id}")
    public CompletableFuture<MirrorCredential> getCredentialById(@Param String projectName, @Param String id) {
        return metaRepo(projectName).credential(id);
    }

    /**
     * POST /projects/{projectName}/credentials
     *
     * <p>Creates a new credential.
     */
    @Post("/projects/{projectName}/credentials")
    @ConsumesJson
    @StatusCode(201)
    public CompletableFuture<PushResultDto> createCredential(@Param String projectName,
                                                             MirrorCredential credential, Author author) {
        return createOrUpdate(projectName, credential, author, false);
    }

    /**
     * PUT /projects/{projectName}/credentials
     *
     * <p>Update the existing credential.
     */
    @Put("/projects/{projectName}/credentials")
    @ConsumesJson
    public CompletableFuture<PushResultDto> updateCredential(@Param String projectName,
                                                             MirrorCredential credential, Author author) {
        return createOrUpdate(projectName, credential, author, true);
    }

    private CompletableFuture<PushResultDto> createOrUpdate(String projectName,
                                                            MirrorCredential credential,
                                                            Author author, boolean update) {
        return metaRepo(projectName).createPushCommand(credential, author, update).thenCompose(command -> {
            return executor().execute(command).thenApply(result -> {
                return new PushResultDto(result.revision(), command.timestamp());
            });
        });
    }

    private MetaRepository metaRepo(String projectName) {
        return projectApiManager.getProject(projectName).metaRepo();
    }
}
