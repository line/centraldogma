/*
 * Copyright 2024 LY Corporation
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

package com.linecorp.centraldogma.server.internal.storage.repository.crud;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.HasRevision;

/**
 * A {@link CrudRepository} implementation which stores JSON objects via a {@link CommandExecutor}.
 */
public final class ReplicatingCrudOperation<T> implements CrudOperation<T> {

    // For write operations
    private final CommandExecutor executor;
    // For read operations
    private final StandaloneCrudOperation<T> delegate;
    private final Class<T> entityType;

    public ReplicatingCrudOperation(Class<T> entityType, CommandExecutor executor,
                                   ProjectManager projectManager) {
        this.executor = executor;
        this.entityType = entityType;
        delegate = new StandaloneCrudOperation<>(entityType, projectManager);
    }

    @Override
    public Class<T> entityType() {
        return entityType;
    }

    @Override
    public CompletableFuture<HasRevision<T>> save(CrudContext ctx, String id, T entity, Author author,
                                                  String description) {
        final String path = ctx.getPath(id);
        final Change<JsonNode> change = Change.ofJsonUpsert(path, Jackson.valueToTree(entity));
        final Command<Revision> command =
                Command.push(author, ctx.projectName(), ctx.repoName(), Revision.HEAD, description, "",
                             Markup.MARKDOWN, change);
        return executor.execute(command).thenCompose(revision -> find(ctx.withRevision(revision), id));
    }

    @Override
    public CompletableFuture<HasRevision<T>> find(CrudContext ctx, String id) {
        return delegate.find(ctx, id);
    }

    @Override
    public CompletableFuture<List<HasRevision<T>>> findAll(CrudContext ctx) {
        return delegate.findAll(ctx);
    }

    @Override
    public CompletableFuture<Revision> delete(CrudContext ctx, String id, Author author, String description) {
        final String path = ctx.getPath(id);
        final Change<Void> change = Change.ofRemoval(path);
        final Command<Revision> command =
                Command.push(author, ctx.projectName(), ctx.repoName(), Revision.HEAD, description, "",
                             Markup.MARKDOWN, change);
        return executor.execute(command);
    }

    public static String validateId(String id) {
        checkArgument(!id.isEmpty(), "id is empty.");
        return Util.validateFileName(id, "id");
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("entityType", entityType)
                          .add("executor", executor)
                          .toString();
    }
}
