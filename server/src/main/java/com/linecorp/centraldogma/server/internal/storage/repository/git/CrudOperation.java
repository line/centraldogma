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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.storage.repository.HasId;
import com.linecorp.centraldogma.server.storage.repository.HasRevision;

public interface CrudOperation<T> {

    CompletableFuture<HasRevision<T>> save(CrudContext ctx, String id, T entity, Author author,
                                           String description);

    default CompletableFuture<HasRevision<T>> save(CrudContext ctx, String id, T entity,
                                                   Author author) {
        return save(ctx, id, entity, author, "Create '" + id + '\'');
    }

    default CompletableFuture<HasRevision<T>> save(CrudContext ctx, String id, T entity) {
        return save(ctx, id, entity, AuthUtil.currentAuthor());
    }

    default CompletableFuture<HasRevision<T>> save(CrudContext ctx, HasId<T> entity) {
        return save(ctx, entity, AuthUtil.currentAuthor());
    }

    default CompletableFuture<HasRevision<T>> save(CrudContext ctx, HasId<T> entity, Author author) {
        return save(ctx, entity.id(), entity.object(), author);
    }

    default CompletableFuture<HasRevision<T>> update(CrudContext ctx, String id, T entity, Author author,
                                                     String description) {
        return find(ctx, id).thenCompose(old -> {
            if (old == null) {
                throw new EntryNotFoundException("Cannot update a non-existent entity. (ID: " + id + ')');
            }
            return save(ctx, id, entity, author, description);
        });
    }

    default CompletableFuture<HasRevision<T>> update(CrudContext ctx, String id, T entity, Author author) {
        return update(ctx, id, entity, author, "Update '" + id + '\'');
    }

    default CompletableFuture<HasRevision<T>> update(CrudContext ctx, String id, T entity) {
        return update(ctx, id, entity, AuthUtil.currentAuthor());
    }

    default CompletableFuture<HasRevision<T>> update(CrudContext ctx, HasId<T> entity, Author author) {
        return update(ctx, entity.id(), entity.object(), author);
    }

    default CompletableFuture<HasRevision<T>> update(CrudContext ctx, HasId<T> entity) {
        return update(ctx, entity, AuthUtil.currentAuthor());
    }

    /**
     * Retrieves the entity with the specified {@code id}.
     * The returned {@link CompletableFuture} will be completed with {@code null} if there's no such entity.
     */
    CompletableFuture<HasRevision<T>> find(CrudContext ctx, String id);

    CompletableFuture<List<HasRevision<T>>> findAll(CrudContext ctx);

    CompletableFuture<Revision> delete(CrudContext ctx, String id, Author author, String description);

    default CompletableFuture<Revision> delete(CrudContext ctx, String id, Author author) {
        return delete(ctx, id, author, "Delete '" + id + '\'');
    }

    default CompletableFuture<Revision> delete(CrudContext ctx, String id) {
        return delete(ctx, id, AuthUtil.currentAuthor());
    }
}
