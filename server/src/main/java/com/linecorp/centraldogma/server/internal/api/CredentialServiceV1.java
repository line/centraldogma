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
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialFile;
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static com.linecorp.centraldogma.internal.CredentialUtil.validateProjectCredentialName;
import static com.linecorp.centraldogma.internal.CredentialUtil.validateRepoCredentialName;

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
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.CredentialUtil;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresProjectRole;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresRepositoryRole;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectApiManager;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * Annotated service object for managing credential service.
 */
@ProducesJson
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
    @RequiresProjectRole(ProjectRole.MEMBER)
    @Get("/projects/{projectName}/credentials")
    public CompletableFuture<List<Credential>> listCredentials(User loginUser,
                                                               @Param String projectName) {
        final CompletableFuture<List<Credential>> future =
                metaRepo(projectName, loginUser).projectCredentials();
        return maybeMaskSecret(loginUser, future);
    }

    private static CompletableFuture<List<Credential>> maybeMaskSecret(
            User loginUser,
            CompletableFuture<List<Credential>> future) {
        if (loginUser.isSystemAdmin()) {
            return future;
        }
        return future.thenApply(credentials -> credentials
                .stream()
                .map(Credential::withoutSecret)
                .collect(toImmutableList()));
    }

    /**
     * GET /projects/{projectName}/credentials/{id}
     *
     * <p>Returns the credential for the ID in the project.
     */
    @RequiresProjectRole(ProjectRole.MEMBER)
    @Get("/projects/{projectName}/credentials/{id}")
    public CompletableFuture<Credential> getCredential(User loginUser,
                                                       @Param String projectName, @Param String id) {
        final CompletableFuture<Credential> future =
                metaRepo(projectName, loginUser).credential(credentialName(projectName, id));
        if (loginUser.isSystemAdmin()) {
            return future;
        }
        return future.thenApply(Credential::withoutSecret);
    }

    /**
     * POST /projects/{projectName}/credentials
     *
     * <p>Creates a new credential.
     */
    @ConsumesJson
    @StatusCode(201)
    @Post("/projects/{projectName}/credentials")
    @RequiresProjectRole(ProjectRole.OWNER)
    public CompletableFuture<PushResultDto> createCredential(@Param String projectName,
                                                             Credential credential, Author author, User user) {
        return createOrUpdate(projectName, credential, author, user, false);
    }

    /**
     * PUT /projects/{projectName}/credentials/{id}
     *
     * <p>Update the existing credential.
     */
    @ConsumesJson
    @Put("/projects/{projectName}/credentials/{id}")
    @RequiresProjectRole(ProjectRole.OWNER)
    public CompletableFuture<PushResultDto> updateCredential(@Param String projectName, @Param String id,
                                                             Credential credential, Author author, User user) {
        checkArgument(id.equals(credential.id()), "The credential ID (%s) can't be updated", id);
        return createOrUpdate(projectName, credential, author, user, true);
    }

    /**
     * DELETE /projects/{projectName}/credentials/{id}
     *
     * <p>Delete the existing credential.
     */
    @Delete("/projects/{projectName}/credentials/{id}")
    @RequiresProjectRole(ProjectRole.OWNER)
    public CompletableFuture<Void> deleteCredential(@Param String projectName,
                                                    @Param String id, Author author, User user) {
        final MetaRepository metaRepository = metaRepo(projectName, user);
        return deleteCredential(projectName, author, metaRepository, credentialName(projectName, id));
    }

    private CompletableFuture<Void> deleteCredential(String projectName, Author author,
                                                     MetaRepository metaRepository,
                                                     String credentialName) {
        return metaRepository.credential(credentialName).thenCompose(credential -> {
            // credential exists.
            final Command<CommitResult> command =
                    Command.push(author, projectName, metaRepository.name(),
                                 Revision.HEAD, "Delete credential: " + credentialName, "",
                                 Markup.PLAINTEXT, Change.ofRemoval(credentialFile(credentialName)));
            return executor().execute(command).thenApply(result -> null);
        });
    }

    private CompletableFuture<PushResultDto> createOrUpdate(String projectName, Credential credential,
                                                            Author author, User user, boolean update) {
        validateProjectCredentialName(projectName, credential.name());
        final CompletableFuture<Command<CommitResult>> future =
                metaRepo(projectName, user).createCredentialPushCommand(credential, author, update);
        return push(future);
    }

    private CompletableFuture<PushResultDto> push(
            CompletableFuture<Command<CommitResult>> future) {
        return future.thenCompose(
                command -> executor().execute(command).thenApply(
                        result -> new PushResultDto(result.revision(), command.timestamp())));
    }

    private MetaRepository metaRepo(String projectName, User user) {
        return projectApiManager.getProject(projectName, user).metaRepo();
    }

    // Repository level credential management APIs.

    /**
     * GET /projects/{projectName}/repos/{repoName}/credentials
     *
     * <p>Returns the list of the credentials in the repository.
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Get("/projects/{projectName}/repos/{repoName}/credentials")
    public CompletableFuture<List<Credential>> listRepoCredentials(User loginUser,
                                                                   @Param String projectName,
                                                                   Repository repository) {
        final CompletableFuture<List<Credential>> future =
                metaRepo(projectName, loginUser).repoCredentials(repository.name());
        return maybeMaskSecret(loginUser, future);
    }

    /**
     * GET /projects/{projectName}/credentials/{id}
     *
     * <p>Returns the credential for the ID in the project.
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Get("/projects/{projectName}/repos/{repoName}/credentials/{id}")
    public CompletableFuture<Credential> getRepoCredential(User loginUser,
                                                           @Param String projectName,
                                                           Repository repository,
                                                           @Param String id) {
        final CompletableFuture<Credential> future =
                metaRepo(projectName, loginUser).credential(credentialName(projectName, repository.name(), id));
        if (loginUser.isSystemAdmin()) {
            return future;
        }
        return future.thenApply(Credential::withoutSecret);
    }

    /**
     * POST /projects/{projectName}/credentials
     *
     * <p>Creates a new credential.
     */
    @ConsumesJson
    @StatusCode(201)
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Post("/projects/{projectName}/repos/{repoName}/credentials")
    public CompletableFuture<PushResultDto> createRepoCredential(@Param String projectName,
                                                                 Repository repository,
                                                                 Credential credential, Author author,
                                                                 User user) {
        return createOrUpdateRepo(projectName, repository.name(), credential, author, user, false);
    }

    /**
     * PUT /projects/{projectName}/credentials/{id}
     *
     * <p>Update the existing credential.
     */
    @ConsumesJson
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Put("/projects/{projectName}/repos/{repoName}/credentials/{id}")
    public CompletableFuture<PushResultDto> updateRepoCredential(@Param String projectName,
                                                                 Repository repository,
                                                                 @Param String id,
                                                                 Credential credential, Author author,
                                                                 User user) {
        checkArgument(id.equals(credential.id()), "The credential ID (%s) can't be updated", id);
        return createOrUpdateRepo(projectName, repository.name(), credential, author, user, true);
    }

    private CompletableFuture<PushResultDto> createOrUpdateRepo(
            String projectName, String repoName, Credential credential,
            Author author, User user, boolean update) {
        validateRepoCredentialName(projectName, repoName, credential.name());
        final CompletableFuture<Command<CommitResult>> future =
                metaRepo(projectName, user).createCredentialPushCommand(repoName, credential, author, update);
        return push(future);
    }

    /**
     * DELETE /projects/{projectName}/credentials/{id}
     *
     * <p>Delete the existing credential.
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Delete("/projects/{projectName}/repos/{repoName}/credentials/{id}")
    public CompletableFuture<Void> deleteRepoCredential(@Param String projectName,
                                                        Repository repository,
                                                        @Param String id, Author author, User user) {
        final MetaRepository metaRepository = metaRepo(projectName, user);
        final String credentialFile = CredentialUtil.repoCredentialFile(repository.name(), id);
        return deleteCredential(projectName, author, metaRepository, credentialFile);
    }
}
