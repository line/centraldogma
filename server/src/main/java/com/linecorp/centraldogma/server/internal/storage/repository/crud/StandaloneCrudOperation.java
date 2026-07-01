/*
 * Copyright 2026 LY Corporation
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

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.HasRevision;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public final class StandaloneCrudOperation<T> implements CrudOperation<T> {

    // TODO(ikhoon): Implement RocksDB based CrudOperation

    private final ProjectManager projectManager;
    private final Class<T> entityType;

    public StandaloneCrudOperation(Class<T> entityType, ProjectManager projectManager) {
        this.entityType = entityType;
        this.projectManager = projectManager;
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
        final Repository repository = repository(ctx);
        return repository.commit(Revision.HEAD, System.currentTimeMillis(), author, description, change)
                         .thenCompose(result -> {
                             return repository.get(result.revision(), path).thenApply(this::entryToValue);
                         });
    }

    @Override
    public CompletableFuture<HasRevision<T>> find(CrudContext ctx, String id) {
        final String path = ctx.getPath(id);
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
        final String path = ctx.getPath(id);
        final Change<Void> change = Change.ofRemoval(path);
        return repository(ctx).commit(Revision.HEAD, System.currentTimeMillis(), author, description, change)
                              .thenApply(CommitResult::revision);
    }

    private Repository repository(CrudContext ctx) {
        return projectManager.get(ctx.projectName()).repos().get(ctx.repoName());
    }
}
