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

package com.linecorp.centraldogma.server.internal.api.variable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonParseException;

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
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.AbstractService;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresProjectRole;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresRepositoryRole;
import com.linecorp.centraldogma.server.internal.storage.repository.git.CrudContext;
import com.linecorp.centraldogma.server.internal.storage.repository.git.CrudOperation;
import com.linecorp.centraldogma.server.internal.storage.repository.git.GitCrudOperation;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.HasRevision;

@ProducesJson
public final class VariableServiceV1 extends AbstractService {

    private static final String VARIABLES = "/variables/";

    private final CrudOperation<Variable> repository;

    public VariableServiceV1(ProjectManager pm, CommandExecutor executor) {
        super(executor);
        repository = new GitCrudOperation<>(Variable.class, executor, pm);
    }

    /**
     * GET /projects/{projectName}/variables
     *
     * <p>Returns the list of the variables defined in the specified project.
     */
    @RequiresProjectRole(ProjectRole.MEMBER)
    @Get("/projects/{projectName}/variables")
    public CompletableFuture<List<Variable>> list(@Param String projectName) {
        return repository.findAll(crudContext(projectName))
                         .thenApply(variables -> variables.stream()
                                                          .map(HasRevision::object)
                                                          .collect(toImmutableList()));
    }

    /**
     * GET /projects/{projectName}/variables/{id}
     *
     * <p>Returns the variable for the ID in the project.
     */
    @RequiresProjectRole(ProjectRole.MEMBER)
    @Get("/projects/{projectName}/variables/{id}")
    public CompletableFuture<Variable> getVariable(@Param String projectName, @Param String id) {
        return repository.find(crudContext(projectName), id).thenApply(variable -> {
            if (variable == null) {
                throw new EntryNotFoundException(
                        "Variable not found: " + id + " (project: " + projectName + ')');
            }
            return variable.object();
        });
    }

    /**
     * POST /projects/{projectName}/variables
     *
     * <p>Creates a new variable.
     */
    @ConsumesJson
    @StatusCode(201)
    @Post("/projects/{projectName}/variables")
    @RequiresProjectRole(ProjectRole.OWNER)
    public CompletableFuture<HasRevision<Variable>> createVariable(@Param String projectName,
                                                                   Variable newVariable,
                                                                   Author author) {
        validateVariable(newVariable);
        setName(newVariable, projectName, null);
        return repository.save(crudContext(projectName), newVariable.id(), newVariable, author);
    }

    /**
     * PUT /projects/{projectName}/variables/{id}
     *
     * <p>Update the existing variable.
     */
    @ConsumesJson
    @Put("/projects/{projectName}/variables/{id}")
    @RequiresProjectRole(ProjectRole.OWNER)
    public CompletableFuture<HasRevision<Variable>> updateVariable(@Param String projectName, @Param String id,
                                                                   Variable variable, Author author) {
        checkArgument(id.equals(variable.id()),
                      "ID in the path must be the same as that in the variable object.");
        validateVariable(variable);
        setName(variable, projectName, null);
        return repository.update(crudContext(projectName), id, variable, author);
    }

    /**
     * DELETE /projects/{projectName}/variables/{id}
     *
     * <p>Delete the existing variable.
     */
    @Delete("/projects/{projectName}/variables/{id}")
    @RequiresProjectRole(ProjectRole.OWNER)
    public CompletableFuture<Void> deleteVariable(@Param String projectName,
                                                  @Param String id, Author author) {
        return repository.delete(crudContext(projectName), id, author).thenAccept(unused -> {});
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/variables
     *
     * <p>Returns the list of the variables in the repository.
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Get("/projects/{projectName}/repos/{repoName}/variables")
    public CompletableFuture<List<Variable>> listRepoVariables(@Param String projectName,
                                                               @Param String repoName) {
        return repository.findAll(crudContext(projectName, repoName))
                         .thenApply(variables -> variables.stream()
                                                          .map(HasRevision::object)
                                                          .collect(toImmutableList()));
    }

    /**
     * GET /projects/{projectName}/variables/{id}
     *
     * <p>Returns the variable for the ID in the project.
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Get("/projects/{projectName}/repos/{repoName}/variables/{id}")
    public CompletableFuture<Variable> getRepoVariable(@Param String projectName,
                                                       @Param String repoName,
                                                       @Param String id) {
        return repository.find(crudContext(projectName, repoName), id).thenApply(variable -> {
            if (variable == null) {
                throw new EntryNotFoundException(
                        "Variable not found: " + id + " (project: " + projectName + ')');
            }
            return variable.object();
        });
    }

    /**
     * POST /projects/{projectName}/variables
     *
     * <p>Creates a new variable.
     */
    @ConsumesJson
    @StatusCode(201)
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Post("/projects/{projectName}/repos/{repoName}/variables")
    public CompletableFuture<HasRevision<Variable>> createRepoVariable(
            @Param String projectName,
            @Param String repoName,
            Variable newVariable,
            Author author) {
        validateVariable(newVariable);
        setName(newVariable, projectName, repoName);
        return repository.save(crudContext(projectName, repoName), newVariable.id(), newVariable, author);
    }

    /**
     * PUT /projects/{projectName}/variables/{id}
     *
     * <p>Update the existing variable.
     */
    @ConsumesJson
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Put("/projects/{projectName}/repos/{repoName}/variables/{id}")
    public CompletableFuture<HasRevision<Variable>> updateRepoVariable(@Param String projectName,
                                                                       @Param String repoName,
                                                                       @Param String id,
                                                                       Variable variable, Author author) {
        checkArgument(id.equals(variable.id()),
                      "ID in the path must be the same as that in the variable object.");
        validateVariable(variable);
        setName(variable, projectName, repoName);
        return repository.update(crudContext(projectName, repoName), id, variable, author);
    }

    /**
     * DELETE /projects/{projectName}/repos/{repoName}/variables/{id}
     *
     * <p>Delete the existing variable.
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Delete("/projects/{projectName}/repos/{repoName}/variables/{id}")
    public CompletableFuture<Void> deleteRepoVariable(@Param String projectName,
                                                      @Param String repoName,
                                                      @Param String id, Author author) {
        return repository.delete(crudContext(projectName, repoName), id, author)
                         .thenAccept(unused -> {});
    }

    private static Variable setName(Variable variable, String projectName, @Nullable String repoName) {
        String name = "projects/" + projectName;
        if (repoName != null) {
            name += "/repos/" + repoName;
        }
        name += "/variables/" + variable.id();
        variable.setName(name);
        return variable;
    }

    private static void validateVariable(Variable variable) {
        if (variable.type() == VariableType.JSON) {
            try {
                Jackson.readTree(variable.value());
            } catch (JsonParseException e) {
                throw new IllegalArgumentException("Invalid JSON value for variable: " + variable.id(), e);
            }
        }
    }

    public static CrudContext crudContext(String projectName) {
        return crudContext(projectName, null, Revision.HEAD);
    }

    public static CrudContext crudContext(String projectName, Revision revision) {
        return crudContext(projectName, null, revision);
    }

    public static CrudContext crudContext(String projectName, @Nullable String repoName) {
        return crudContext(projectName, repoName, Revision.HEAD);
    }

    public static CrudContext crudContext(String projectName, @Nullable String repoName, Revision revision) {
        final String targetPath;
        if (repoName == null) {
            targetPath = VARIABLES;
        } else {
            targetPath = "/repos/" + repoName + VARIABLES;
        }
        return new CrudContext(projectName, Project.REPO_DOGMA, targetPath, revision);
    }
}
