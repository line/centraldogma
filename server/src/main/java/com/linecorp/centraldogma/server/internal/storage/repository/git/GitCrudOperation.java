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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.repository.CrudRepository;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.HasRevision;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * A {@link CrudRepository} implementation which stores JSON objects in a Git repository.
 */
public final class GitCrudOperation<T> implements CrudOperation<T> {

    // For write operations
    private final CommandExecutor executor;
    // For read operations
    private final ProjectManager projectManager;
    private final Class<T> entityType;

    public GitCrudOperation(Class<T> entityType, CommandExecutor executor, ProjectManager projectManager) {
        this.executor = executor;
        this.projectManager = projectManager;
        this.entityType = entityType;
    }

    @Override
    public CompletableFuture<HasRevision<T>> save(CrudContext ctx, String id, T entity, Author author,
                                                  String description) {
        final String path = getPath(ctx, id);
        final Change<JsonNode> change = Change.ofJsonUpsert(path, Jackson.valueToTree(entity));
        final Command<Revision> command =
                Command.push(author, ctx.projectName(), ctx.repoName(), Revision.HEAD, description, "",
                             Markup.MARKDOWN, change);
        return executor.execute(command).thenCompose(revision -> {
            return repository(ctx).get(revision, path).thenApply(this::entryToValue);
        });
    }

    @Override
    public CompletableFuture<HasRevision<T>> find(CrudContext ctx, String id) {
        final String path = getPath(ctx, id);
        return repository(ctx).getOrNull(ctx.revision(), path).thenApply(this::entryToValue);
    }

    @Override
    public CompletableFuture<List<HasRevision<T>>> findAll(CrudContext ctx) {
        return repository(ctx).find(ctx.revision(), ctx.targetPath() + "*.json")
                              .thenApply(entries -> {
                                  if (entries.isEmpty()) {
                                      return ImmutableList.of();
                                  }
                                  return entries.values().stream()
                                                .map(this::entryToValue)
                                                .collect(toImmutableList());
                              });
    }

    @Override
    public CompletableFuture<Revision> delete(CrudContext ctx, String id, Author author, String description) {
        final String path = getPath(ctx, id);
        final Change<Void> change = Change.ofRemoval(path);
        final Command<Revision> command =
                Command.push(author, ctx.projectName(), ctx.repoName(), Revision.HEAD, description, "",
                             Markup.MARKDOWN, change);
        return executor.execute(command);
    }

    private Repository repository(CrudContext ctx) {
        return projectManager.get(ctx.projectName()).repos().get(ctx.repoName());
    }

    @Nullable
    private HasRevision<T> entryToValue(@Nullable Entry<?> entry) {
        if (entry == null) {
            return null;
        }
        try {
            return HasRevision.of(Jackson.treeToValue(entry.contentAsJson(), entityType), entry.revision());
        } catch (JsonParseException | JsonMappingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getPath(CrudContext ctx, String id) {
        validateId(id);
        return ctx.targetPath() + id + ".json";
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
                          .add("projectManager", projectManager)
                          .toString();
    }
}
