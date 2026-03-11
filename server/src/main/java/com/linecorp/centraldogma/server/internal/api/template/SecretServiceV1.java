/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server.internal.api.template;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.AbstractService;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresProjectRole;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresRepositoryRole;
import com.linecorp.centraldogma.server.internal.storage.repository.git.CrudContext;
import com.linecorp.centraldogma.server.internal.storage.repository.git.CrudOperation;
import com.linecorp.centraldogma.server.internal.storage.repository.git.DefaultCrudOperation;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.metadata.UserAndTimestamp;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.HasRevision;

/**
 * A service that provides CRUD operations for secrets at both project and repository levels.
 *
 * <p>Secret values are masked by the API for security reasons:
 * <ul>
 *   <li>List APIs always mask secret values as {@code "****"}.</li>
 *   <li>GET, create, and update APIs mask secret values for non-system-admin users.</li>
 * </ul>
 */
@ProducesJson
public final class SecretServiceV1 extends AbstractService {

    private static final String SECRETS = "/secrets/";

    private final CrudOperation<Secret> repository;

    public SecretServiceV1(ProjectManager pm, CommandExecutor executor) {
        super(executor);
        repository = new DefaultCrudOperation<>(Secret.class, executor, pm);
    }

    /**
     * GET /projects/{projectName}/secrets
     *
     * <p>Returns the list of the secrets defined in the specified project.
     * Secret values are always masked as {@code "****"}.
     */
    @RequiresProjectRole(ProjectRole.MEMBER)
    @Get("/projects/{projectName}/secrets")
    public CompletableFuture<List<Secret>> list(@Param String projectName) {
        return repository.findAll(secretCrudContext(projectName)).thenApply(secrets -> {
            return secrets.stream()
                          .map(HasRevision::object)
                          // List API should not return the value of the secrets for security reasons. The value
                          // can be retrieved via the GET API for a specific secret.
                          .map(Secret::withoutValue)
                          .collect(toImmutableList());
        });
    }

    /**
     * GET /projects/{projectName}/secrets/{id}
     *
     * <p>Returns the secret for the ID in the project.
     * The secret value is masked as {@code "****"} for non-system-admin users.
     */
    @RequiresProjectRole(ProjectRole.MEMBER)
    @Get("/projects/{projectName}/secrets/{id}")
    public CompletableFuture<Secret> getSecret(@Param String projectName, @Param String id, User user) {
        return repository.find(secretCrudContext(projectName), id).thenApply(secret -> {
            if (secret == null) {
                throw new EntryNotFoundException(
                        "Secret not found: " + id + " (project: " + projectName + ')');
            }
            return maybeMaskValue(secret.object(), user);
        });
    }

    /**
     * POST /projects/{projectName}/secrets
     *
     * <p>Creates a new secret.
     * The secret value in the response is masked as {@code "****"} for non-system-admin users.
     */
    @ConsumesJson
    @StatusCode(201)
    @Post("/projects/{projectName}/secrets")
    @RequiresProjectRole(ProjectRole.OWNER)
    public CompletableFuture<HasRevision<Secret>> createSecret(@Param String projectName,
                                                               Secret newSecret,
                                                               Author author, User user) {
        setNameAndCreation(newSecret, projectName, null, author);
        return repository.save(secretCrudContext(projectName), newSecret.id(), newSecret, author)
                         .thenApply(secret -> maybeMaskValue(secret, user));
    }

    /**
     * PUT /projects/{projectName}/secrets/{id}
     *
     * <p>Update the existing secret.
     * The secret value in the response is masked as {@code "****"} for non-system-admin users.
     */
    @ConsumesJson
    @Put("/projects/{projectName}/secrets/{id}")
    @RequiresProjectRole(ProjectRole.OWNER)
    public CompletableFuture<HasRevision<Secret>> updateSecret(@Param String projectName, @Param String id,
                                                               Secret secret, Author author, User user) {
        checkArgument(id.equals(secret.id()),
                      "ID in the path must be the same as that in the secret object.");
        setNameAndCreation(secret, projectName, null, author);
        return repository.update(secretCrudContext(projectName), id, secret, author)
                         .thenApply(updated -> maybeMaskValue(updated, user));
    }

    /**
     * DELETE /projects/{projectName}/secrets/{id}
     *
     * <p>Delete the existing secret.
     */
    @Delete("/projects/{projectName}/secrets/{id}")
    @RequiresProjectRole(ProjectRole.OWNER)
    public CompletableFuture<Void> deleteSecret(@Param String projectName,
                                                @Param String id, Author author) {
        return repository.delete(secretCrudContext(projectName), id, author).thenAccept(unused -> {});
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/secrets
     *
     * <p>Returns the list of the secrets in the repository.
     * Secret values are always masked as {@code "****"}.
     */
    @RequiresRepositoryRole(RepositoryRole.READ)
    @Get("/projects/{projectName}/repos/{repoName}/secrets")
    public CompletableFuture<List<Secret>> listRepoSecrets(@Param String projectName,
                                                           @Param String repoName) {
        return repository.findAll(secretCrudContext(projectName, repoName)).thenApply(secrets -> {
            return secrets.stream()
                          .map(HasRevision::object)
                          // List API should not return the value of the secrets for security reasons. The value
                          // can be retrieved via the GET API for a specific secret.
                          .map(Secret::withoutValue)
                          .collect(toImmutableList());
        });
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/secrets/{id}
     *
     * <p>Returns the secret for the ID in the repository.
     * The secret value is masked as {@code "****"} for non-system-admin users.
     */
    @RequiresRepositoryRole(RepositoryRole.READ)
    @Get("/projects/{projectName}/repos/{repoName}/secrets/{id}")
    public CompletableFuture<Secret> getRepoSecret(@Param String projectName,
                                                   @Param String repoName,
                                                   @Param String id,
                                                   User user) {
        return repository.find(secretCrudContext(projectName, repoName), id).thenApply(secret -> {
            if (secret == null) {
                throw new EntryNotFoundException(
                        "Secret not found: " + id + " (project: " + projectName +
                        ", repository: " + repoName + ')');
            }
            return maybeMaskValue(secret.object(), user);
        });
    }

    /**
     * POST /projects/{projectName}/repos/{repoName}/secrets
     *
     * <p>Creates a new secret in the repository.
     * The secret value in the response is masked as {@code "****"} for non-system-admin users.
     */
    @ConsumesJson
    @StatusCode(201)
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Post("/projects/{projectName}/repos/{repoName}/secrets")
    public CompletableFuture<HasRevision<Secret>> createRepoSecret(
            @Param String projectName, @Param String repoName,
            Secret newSecret, Author author, User user) {
        setNameAndCreation(newSecret, projectName, repoName, author);
        return repository.save(secretCrudContext(projectName, repoName), newSecret.id(), newSecret, author)
                         .thenApply(secret -> maybeMaskValue(secret, user));
    }

    /**
     * PUT /projects/{projectName}/repos/{repoName}/secrets/{id}
     *
     * <p>Update the existing secret in the repository.
     * The secret value in the response is masked as {@code "****"} for non-system-admin users.
     */
    @ConsumesJson
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Put("/projects/{projectName}/repos/{repoName}/secrets/{id}")
    public CompletableFuture<HasRevision<Secret>> updateRepoSecret(@Param String projectName,
                                                                   @Param String repoName,
                                                                   @Param String id,
                                                                   Secret secret, Author author, User user) {
        checkArgument(id.equals(secret.id()),
                      "ID in the path must be the same as that in the secret object.");
        setNameAndCreation(secret, projectName, repoName, author);
        return repository.update(secretCrudContext(projectName, repoName), id, secret, author)
                         .thenApply(updated -> maybeMaskValue(updated, user));
    }

    /**
     * DELETE /projects/{projectName}/repos/{repoName}/secrets/{id}
     *
     * <p>Delete the existing secret.
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Delete("/projects/{projectName}/repos/{repoName}/secrets/{id}")
    public CompletableFuture<Void> deleteRepoSecret(@Param String projectName,
                                                    @Param String repoName,
                                                    @Param String id, Author author) {
        return repository.delete(secretCrudContext(projectName, repoName), id, author)
                         .thenAccept(unused -> {});
    }

    private static Secret setNameAndCreation(Secret secret, String projectName, @Nullable String repoName,
                                             Author author) {
        String name = "projects/" + projectName;
        if (repoName != null) {
            name += "/repos/" + repoName;
        }
        name += "/secrets/" + secret.id();
        secret.setName(name);
        secret.setCreation(UserAndTimestamp.of(author));
        return secret;
    }

    private static HasRevision<Secret> maybeMaskValue(HasRevision<Secret> secret, User user) {
        if (user.isSystemAdmin()) {
            return secret;
        } else {
            // Hide the value of the secret for non-admin users.
            return HasRevision.of(secret.object().withoutValue(), secret.revision());
        }
    }

    private static Secret maybeMaskValue(Secret secret, User user) {
        if (user.isSystemAdmin()) {
            return secret;
        } else {
            // Hide the value of the secret for non-admin users.
            return secret.withoutValue();
        }
    }

    static CrudContext secretCrudContext(String projectName) {
        return secretCrudContext(projectName, null, Revision.HEAD);
    }

    static CrudContext secretCrudContext(String projectName, Revision revision) {
        return secretCrudContext(projectName, null, revision);
    }

    private static CrudContext secretCrudContext(String projectName, @Nullable String repoName) {
        return secretCrudContext(projectName, repoName, Revision.HEAD);
    }

    static CrudContext secretCrudContext(String projectName, @Nullable String repoName,
                                         Revision revision) {
        final String targetPath;
        if (repoName == null) {
            targetPath = SECRETS;
        } else {
            targetPath = "/repos/" + repoName + SECRETS;
        }
        return new CrudContext(projectName, Project.REPO_DOGMA, targetPath, revision);
    }
}
