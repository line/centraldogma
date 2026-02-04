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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.repository.CrudRepository;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.HasRevision;

/**
 * A {@link CrudRepository} implementation which stores JSON objects in a Git repository.
 */
public final class GitCrudRepository<T> implements CrudRepository<T> {

    private final CrudOperation<T> crudOperation;
    private final CrudContext ctx;

    public GitCrudRepository(Class<T> entityType, CommandExecutor executor, ProjectManager projectManager,
                             String projectName, String repoName, String targetPath) {
        crudOperation = new DefaultCrudOperation<>(entityType, executor, projectManager);
        ctx = new CrudContext(projectName, repoName, targetPath, Revision.HEAD);
    }

    @Override
    public CompletableFuture<HasRevision<T>> save(String id, T entity, Author author, String description) {
        return crudOperation.save(ctx, id, entity, author, description);
    }

    @Override
    public CompletableFuture<HasRevision<T>> find(String id) {
        return crudOperation.find(ctx, id);
    }

    @Override
    public CompletableFuture<List<HasRevision<T>>> findAll() {
        return crudOperation.findAll(ctx);
    }

    @Override
    public CompletableFuture<Revision> delete(String id, Author author, String description) {
        return crudOperation.delete(ctx, id, author, description);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("crudOperation", crudOperation)
                          .add("ctx", ctx)
                          .toString();
    }
}
