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

package com.linecorp.centraldogma.server.internal.storage.repository;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.storage.repository.HasRevision;

/**
 * A repository that provides CRUD operations.
 */
public interface CrudRepository<T> {

    CompletableFuture<HasRevision<T>> save(String id, T entity, Author author, String description);

    default CompletableFuture<HasRevision<T>> save(String id, T entity, Author author) {
        return save(id, entity, author, "Create '" + id + '\'');
    }

    default CompletableFuture<HasRevision<T>> save(String id, T entity) {
        return save(id, entity, AuthUtil.currentAuthor());
    }

    default CompletableFuture<HasRevision<T>> save(HasId<T> entity) {
        return save(entity, AuthUtil.currentAuthor());
    }

    default CompletableFuture<HasRevision<T>> save(HasId<T> entity, Author author) {
        return save(entity.id(), entity.object(), author);
    }

    default CompletableFuture<HasRevision<T>> update(String id, T entity, Author author, String description) {
        return find(id).thenCompose(old -> {
            if (old == null) {
                throw new EntryNotFoundException("Cannot update a non-existent entity. (ID: " + id + ')');
            }
            return save(id, entity, author, description);
        });
    }

    default CompletableFuture<HasRevision<T>> update(String id, T entity, Author author) {
        return update(id, entity, author, "Update '" + id + '\'');
    }

    default CompletableFuture<HasRevision<T>> update(String id, T entity) {
        return update(id, entity, AuthUtil.currentAuthor());
    }

    default CompletableFuture<HasRevision<T>> update(HasId<T> entity, Author author) {
        return update(entity.id(), entity.object(), author);
    }

    default CompletableFuture<HasRevision<T>> update(HasId<T> entity) {
        return update(entity, AuthUtil.currentAuthor());
    }

    /**
     * Retrieves the entity with the specified {@code id}.
     * The returned {@link CompletableFuture} will be completed with {@code null} if there's no such entity.
     */
    CompletableFuture<HasRevision<T>> find(String id);

    CompletableFuture<List<HasRevision<T>>> findAll();

    CompletableFuture<Revision> delete(String id, Author author, String description);

    default CompletableFuture<Revision> delete(String id, Author author) {
        return delete(id, author, "Delete '" + id + '\'');
    }

    default CompletableFuture<Revision> delete(String id) {
        return delete(id, AuthUtil.currentAuthor());
    }
}
