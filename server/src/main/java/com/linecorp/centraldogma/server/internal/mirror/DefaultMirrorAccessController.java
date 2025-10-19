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

package com.linecorp.centraldogma.server.internal.mirror;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Streams;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.internal.api.sysadmin.MirrorAccessControlRequest;
import com.linecorp.centraldogma.server.internal.storage.repository.CrudRepository;
import com.linecorp.centraldogma.server.internal.storage.repository.HasRevision;
import com.linecorp.centraldogma.server.metadata.UserAndTimestamp;
import com.linecorp.centraldogma.server.mirror.MirrorAccessController;

public final class DefaultMirrorAccessController implements MirrorAccessController {

    private static final Logger logger = LoggerFactory.getLogger(DefaultMirrorAccessController.class);

    private static final UuidGenerator idGenerator = new UuidGenerator();

    @Nullable
    private CrudRepository<MirrorAccessControl> repository;

    public void setRepository(CrudRepository<MirrorAccessControl> repository) {
        checkState(this.repository == null, "repository is already set.");
        this.repository = repository;
    }

    private CrudRepository<MirrorAccessControl> repository() {
        checkState(repository != null, "repository is not set.");
        return repository;
    }

    public CompletableFuture<MirrorAccessControl> add(MirrorAccessControlRequest request, Author author) {
        return repository().save(MirrorAccessControl.from(request, author), author)
                           .thenApply(HasRevision::object);
    }

    public CompletableFuture<MirrorAccessControl> update(MirrorAccessControlRequest request, Author author) {
        return repository().update(MirrorAccessControl.from(request, author), author)
                           .thenApply(HasRevision::object);
    }

    public CompletableFuture<MirrorAccessControl> get(String id) {
        return repository().find(id).thenApply(HasRevision::object);
    }

    public CompletableFuture<List<MirrorAccessControl>> list() {
        return repository().findAll().thenApply(list -> list.stream()
                                                            .map(HasRevision::object)
                                                            .collect(toImmutableList()));
    }

    @Override
    public CompletableFuture<Boolean> allow(String targetPattern, String reason, int order) {
        final Author author = Author.SYSTEM;
        final MirrorAccessControl accessControl =
                new MirrorAccessControl(idGenerator.generateId().toString(), targetPattern, true,
                                        reason, order, UserAndTimestamp.of(author));
        logger.info("Allowing the target pattern: {}", accessControl);
        // If there is a duplicate target pattern, the order will be considered first.
        // If the order is the same, the latest one will be considered first.
        return repository().save(accessControl, author).thenApply(unused -> true);
    }

    @Override
    public CompletableFuture<Boolean> disallow(String targetPattern, String reason, int order) {
        final Author author = Author.SYSTEM;
        final MirrorAccessControl accessControl =
                new MirrorAccessControl(idGenerator.generateId().toString(), targetPattern, false,
                                        reason, order, UserAndTimestamp.of(author));
        logger.info("Disallowing the target pattern: {}", accessControl);
        return repository().save(accessControl, author).thenApply(unused -> true);
    }

    @Override
    public CompletableFuture<Boolean> isAllowed(String repoUri) {
        return repository().findAll().thenApply(acl -> {
            if (acl.isEmpty()) {
                // If there is no access control, it is allowed by default.
                return true;
            }

            final List<HasRevision<MirrorAccessControl>> sorted =
                    acl.stream()
                       .sorted(AccessControlComparator.INSTANCE)
                       .collect(toImmutableList());
            for (HasRevision<MirrorAccessControl> entity : sorted) {
                try {
                    if (matchesRepoUri(repoUri, entity)) {
                        return entity.object().allow();
                    }
                } catch (Exception e) {
                    logger.warn("Failed to match the target pattern: {}", entity.object().targetPattern(), e);
                    continue;
                }
            }
            // If there is no matching pattern, it is allowed by default.
            return true;
        });
    }

    @Override
    public CompletableFuture<Map<String, Boolean>> isAllowed(Iterable<String> repoUris) {
        return repository().findAll().thenApply(acl -> {
            if (acl.isEmpty()) {
                // If there is no access control, it is allowed by default.
                return Streams.stream(repoUris)
                              .distinct()
                              .collect(toImmutableMap(uri -> uri, uri -> true));
            }

            final List<HasRevision<MirrorAccessControl>> sorted =
                    acl.stream()
                       .sorted(AccessControlComparator.INSTANCE)
                       .collect(toImmutableList());
            return Streams.stream(repoUris).distinct().collect(toImmutableMap(uri -> uri, uri -> {
                for (HasRevision<MirrorAccessControl> entity : sorted) {
                    if (matchesRepoUri(uri, entity)) {
                        return entity.object().allow();
                    }
                }
                // If there is no matching pattern, it is allowed by default.
                return true;
            }));
        });
    }

    private static boolean matchesRepoUri(String repoUri, HasRevision<MirrorAccessControl> entity) {
        final String targetPattern = entity.object().targetPattern();
        try {
            return repoUri.equals(targetPattern) || repoUri.matches(targetPattern);
        } catch (Exception e) {
            logger.warn("Failed to match the target pattern: {}", targetPattern, e);
            return false;
        }
    }

    public CompletableFuture<Void> delete(String id, Author author) {
        return repository().delete(id, author, "Delete '" + id + '\'')
                           .thenAccept(unused -> {
                           });
    }

    private enum AccessControlComparator implements Comparator<HasRevision<MirrorAccessControl>> {
        INSTANCE;

        @Override
        public int compare(HasRevision<MirrorAccessControl> o1, HasRevision<MirrorAccessControl> o2) {
            // A lower order comes first.
            final int result = Integer.compare(o1.object().order(), o2.object().order());
            if (result != 0) {
                return result;
            }
            // A recent creation comes first.
            return o2.object().creation().timestamp().compareTo(o1.object().creation().timestamp());
        }
    }
}
