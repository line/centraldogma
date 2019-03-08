/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.centraldogma.client.armeria.legacy;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.centraldogma.internal.Util.unsafeCast;
import static com.linecorp.centraldogma.internal.Util.validatePathPattern;
import static com.linecorp.centraldogma.internal.Util.validateRepositoryName;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;

import org.apache.thrift.TException;

import com.fasterxml.jackson.core.JsonParseException;
import com.google.common.collect.Iterables;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.thrift.ThriftCompletableFuture;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.client.AbstractCentralDogma;
import com.linecorp.centraldogma.client.RepositoryInfo;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryExecutionException;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.thrift.AuthorConverter;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService;
import com.linecorp.centraldogma.internal.thrift.ChangeConverter;
import com.linecorp.centraldogma.internal.thrift.Comment;
import com.linecorp.centraldogma.internal.thrift.CommitConverter;
import com.linecorp.centraldogma.internal.thrift.DiffFileResult;
import com.linecorp.centraldogma.internal.thrift.EntryConverter;
import com.linecorp.centraldogma.internal.thrift.GetFileResult;
import com.linecorp.centraldogma.internal.thrift.MarkupConverter;
import com.linecorp.centraldogma.internal.thrift.MergeQueryConverter;
import com.linecorp.centraldogma.internal.thrift.Project;
import com.linecorp.centraldogma.internal.thrift.PushResultConverter;
import com.linecorp.centraldogma.internal.thrift.QueryConverter;
import com.linecorp.centraldogma.internal.thrift.Repository;
import com.linecorp.centraldogma.internal.thrift.RevisionConverter;
import com.linecorp.centraldogma.internal.thrift.WatchFileResult;
import com.linecorp.centraldogma.internal.thrift.WatchRepositoryResult;

final class LegacyCentralDogma extends AbstractCentralDogma {

    private final CentralDogmaService.AsyncIface client;

    LegacyCentralDogma(ScheduledExecutorService executor, CentralDogmaService.AsyncIface client) {
        super(executor);
        this.client = requireNonNull(client, "client");
    }

    @Override
    public CompletableFuture<Void> createProject(String projectName) {
        return run(callback -> {
            validateProjectName(projectName);
            client.createProject(projectName, callback);
        });
    }

    @Override
    public CompletableFuture<Void> removeProject(String projectName) {
        return run(callback -> {
            validateProjectName(projectName);
            client.removeProject(projectName, callback);
        });
    }

    @Override
    public CompletableFuture<Void> unremoveProject(String projectName) {
        return run(callback -> {
            validateProjectName(projectName);
            client.unremoveProject(projectName, callback);
        });
    }

    @Override
    public CompletableFuture<Set<String>> listProjects() {
        final CompletableFuture<List<Project>> future = run(client::listProjects);
        return future.thenApply(list -> convertToSet(list, Project::getName));
    }

    @Override
    public CompletableFuture<Set<String>> listRemovedProjects() {
        return run(client::listRemovedProjects);
    }

    @Override
    public CompletableFuture<Void> createRepository(String projectName, String repositoryName) {
        return run(callback -> {
            validateProjectAndRepositoryName(projectName, repositoryName);
            client.createRepository(projectName, repositoryName, callback);
        });
    }

    @Override
    public CompletableFuture<Void> removeRepository(String projectName, String repositoryName) {
        return run(callback -> {
            validateProjectAndRepositoryName(projectName, repositoryName);
            client.removeRepository(projectName, repositoryName, callback);
        });
    }

    @Override
    public CompletableFuture<Void> unremoveRepository(String projectName, String repositoryName) {
        return run(callback -> {
            validateProjectAndRepositoryName(projectName, repositoryName);
            client.unremoveRepository(projectName, repositoryName, callback);
        });
    }

    @Override
    public CompletableFuture<Map<String, RepositoryInfo>> listRepositories(String projectName) {
        final CompletableFuture<List<Repository>> future = run(callback -> {
            validateProjectName(projectName);
            client.listRepositories(projectName, callback);
        });
        return future.thenApply(list -> convertToMap(
                list,
                Function.identity(),
                Repository::getName,
                r -> new RepositoryInfo(
                        r.getName(), RevisionConverter.TO_MODEL.convert(r.getHead().getRevision()))));
    }

    @Override
    public CompletableFuture<Set<String>> listRemovedRepositories(String projectName) {
        return run(callback -> {
            validateProjectName(projectName);
            client.listRemovedRepositories(projectName, callback);
        });
    }

    @Override
    public CompletableFuture<Revision> normalizeRevision(String projectName, String repositoryName,
                                                         Revision revision) {
        final CompletableFuture<com.linecorp.centraldogma.internal.thrift.Revision> future = run(callback -> {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(revision, "revision");
            client.normalizeRevision(projectName, repositoryName,
                                     RevisionConverter.TO_DATA.convert(revision), callback);
        });
        return future.thenApply(RevisionConverter.TO_MODEL::convert);
    }

    @Override
    public CompletableFuture<Map<String, EntryType>> listFiles(String projectName, String repositoryName,
                                                               Revision revision, String pathPattern) {
        final CompletableFuture<List<com.linecorp.centraldogma.internal.thrift.Entry>> future =
                run(callback -> {
                    validateProjectAndRepositoryName(projectName, repositoryName);
                    requireNonNull(revision, "revision");
                    validatePathPattern(pathPattern, "pathPattern");

                    client.listFiles(projectName, repositoryName,
                                     RevisionConverter.TO_DATA.convert(revision),
                                     pathPattern, callback);
                });
        return future.thenApply(list -> list.stream().collect(toImmutableMap(
                com.linecorp.centraldogma.internal.thrift.Entry::getPath,
                e -> EntryConverter.convertEntryType(e.getType()))));
    }

    @Override
    public <T> CompletableFuture<Entry<T>> getFile(String projectName, String repositoryName,
                                                   Revision revision, Query<T> query) {

        return normalizeRevision(projectName, repositoryName, revision).thenCompose(normRev -> {
            final CompletableFuture<GetFileResult> future = run(callback -> {
                requireNonNull(query, "query");
                client.getFile(projectName, repositoryName,
                               RevisionConverter.TO_DATA.convert(normRev),
                               QueryConverter.TO_DATA.convert(query), callback);
            });
            return future.thenApply(r -> {
                if (r == null) {
                    return null;
                }

                final Entry<T> converted;
                switch (r.getType()) {
                    case JSON:
                        try {
                            converted = unsafeCast(
                                    Entry.ofJson(normRev, query.path(), Jackson.readTree(r.getContent())));
                        } catch (IOException e) {
                            throw new CompletionException(
                                    "failed to parse the query result: " + query, e);
                        }
                        break;
                    case TEXT:
                        converted = unsafeCast(Entry.ofText(normRev, query.path(), r.getContent()));
                        break;
                    case DIRECTORY:
                        converted = unsafeCast(Entry.ofDirectory(normRev, query.path()));
                        break;
                    default:
                        throw new Error("unknown entry type: " + r.getType());
                }

                return converted;
            });
        });
    }

    @Override
    public CompletableFuture<Map<String, Entry<?>>> getFiles(String projectName, String repositoryName,
                                                             Revision revision, String pathPattern) {

        return normalizeRevision(projectName, repositoryName, revision).thenCompose(normRev -> {
            final CompletableFuture<List<com.linecorp.centraldogma.internal.thrift.Entry>> future =
                    run(callback -> {
                        validatePathPattern(pathPattern, "pathPattern");
                        client.getFiles(projectName, repositoryName,
                                        RevisionConverter.TO_DATA.convert(normRev),
                                        pathPattern, callback);
                    });
            return future.thenApply(list -> convertToMap(list, e -> EntryConverter.convert(normRev, e),
                                                         Entry::path, Function.identity()));
        });
    }

    @Override
    public <T> CompletableFuture<MergedEntry<T>> mergeFiles(String projectName, String repositoryName,
                                                            Revision revision, MergeQuery<T> mergeQuery) {
        final CompletableFuture<com.linecorp.centraldogma.internal.thrift.MergedEntry> future =
                run(callback -> {
                    validateProjectAndRepositoryName(projectName, repositoryName);
                    requireNonNull(revision, "revision");
                    requireNonNull(mergeQuery, "mergeQuery");
                    client.mergeFiles(projectName, repositoryName,
                                      RevisionConverter.TO_DATA.convert(revision),
                                      MergeQueryConverter.TO_DATA.convert(mergeQuery),
                                      callback);
                });
        return future.thenApply(entry -> {
            final EntryType entryType = EntryConverter.convertEntryType(entry.getType());
            assert entryType != null;
            switch (entryType) {
                case JSON:
                    try {
                        @SuppressWarnings("unchecked")
                        final MergedEntry<T> converted = (MergedEntry<T>) MergedEntry.of(
                                RevisionConverter.TO_MODEL.convert(entry.revision),
                                entryType, Jackson.readTree(entry.content), entry.paths);
                        return converted;
                    } catch (IOException e) {
                        throw new CompletionException(
                                "failed to parse the content: " + entry.content, e);
                    }
                default:
                    throw new Error("unsupported entry type: " + entryType);
            }
        });
    }

    @Override
    public CompletableFuture<List<Commit>> getHistory(String projectName,
                                                      String repositoryName,
                                                      Revision from,
                                                      Revision to,
                                                      String pathPattern) {
        final CompletableFuture<List<com.linecorp.centraldogma.internal.thrift.Commit>> future =
                run(callback -> {
                    validateProjectAndRepositoryName(projectName, repositoryName);
                    requireNonNull(from, "from");
                    requireNonNull(to, "to");
                    validatePathPattern(pathPattern, "pathPattern");

                    client.getHistory(projectName, repositoryName,
                                      RevisionConverter.TO_DATA.convert(from),
                                      RevisionConverter.TO_DATA.convert(to),
                                      pathPattern, callback);
                });
        return future.thenApply(list -> convertToList(list, CommitConverter.TO_MODEL::convert));
    }

    @Override
    public <T> CompletableFuture<Change<T>> getDiff(String projectName, String repositoryName,
                                                    Revision from, Revision to, Query<T> query) {
        final CompletableFuture<DiffFileResult> future = run(callback -> {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(from, "from");
            requireNonNull(to, "to");
            requireNonNull(query, "query");
            client.diffFile(projectName, repositoryName,
                            RevisionConverter.TO_DATA.convert(from),
                            RevisionConverter.TO_DATA.convert(to),
                            QueryConverter.TO_DATA.convert(query), callback);
        });
        return future.thenApply(r -> {
            if (r == null) {
                return null;
            }

            final Change<T> converted;
            switch (r.getType()) {
                case UPSERT_JSON:
                    converted = unsafeCast(Change.ofJsonUpsert(query.path(), r.getContent()));
                    break;
                case UPSERT_TEXT:
                    converted = unsafeCast(Change.ofTextUpsert(query.path(), r.getContent()));
                    break;
                case REMOVE:
                    converted = unsafeCast(Change.ofRemoval(query.path()));
                    break;
                case RENAME:
                    converted = unsafeCast(Change.ofRename(query.path(), r.getContent()));
                    break;
                case APPLY_JSON_PATCH:
                    converted = unsafeCast(Change.ofJsonPatch(query.path(), r.getContent()));
                    break;
                case APPLY_TEXT_PATCH:
                    converted = unsafeCast(Change.ofTextPatch(query.path(), r.getContent()));
                    break;
                default:
                    throw new Error("unknown change type: " + r.getType());
            }

            return converted;
        });
    }

    @Override
    public CompletableFuture<List<Change<?>>> getDiffs(String projectName, String repositoryName,
                                                       Revision from, Revision to, String pathPattern) {
        final CompletableFuture<List<com.linecorp.centraldogma.internal.thrift.Change>> future =
                run(callback -> {
                    validateProjectAndRepositoryName(projectName, repositoryName);
                    requireNonNull(from, "from");
                    requireNonNull(to, "to");
                    validatePathPattern(pathPattern, "pathPattern");
                    client.getDiffs(projectName, repositoryName,
                                    RevisionConverter.TO_DATA.convert(from),
                                    RevisionConverter.TO_DATA.convert(to), pathPattern, callback);
                });
        return future.thenApply(list -> convertToList(list, ChangeConverter.TO_MODEL::convert));
    }

    @Override
    public CompletableFuture<List<Change<?>>> getPreviewDiffs(String projectName, String repositoryName,
                                                              Revision baseRevision,
                                                              Iterable<? extends Change<?>> changes) {
        final CompletableFuture<List<com.linecorp.centraldogma.internal.thrift.Change>> future =
                run(callback -> {
                    validateProjectAndRepositoryName(projectName, repositoryName);
                    requireNonNull(baseRevision, "baseRevision");
                    requireNonNull(changes, "changes");
                    client.getPreviewDiffs(
                            projectName, repositoryName,
                            RevisionConverter.TO_DATA.convert(baseRevision),
                            convertToList(changes, ChangeConverter.TO_DATA::convert), callback);
                });
        return future.thenApply(LegacyCentralDogma::convertToChangesModel);
    }

    @Override
    public CompletableFuture<PushResult> push(String projectName, String repositoryName, Revision baseRevision,
                                              String summary, String detail, Markup markup,
                                              Iterable<? extends Change<?>> changes) {
        return push(projectName, repositoryName, baseRevision,
                    Author.UNKNOWN, summary, detail, markup, changes);
    }

    @Override
    public CompletableFuture<PushResult> push(String projectName, String repositoryName, Revision baseRevision,
                                              Author author, String summary, String detail, Markup markup,
                                              Iterable<? extends Change<?>> changes) {
        final CompletableFuture<com.linecorp.centraldogma.internal.thrift.Commit> future = run(callback -> {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(baseRevision, "baseRevision");
            requireNonNull(author, "author");
            requireNonNull(summary, "summary");
            requireNonNull(detail, "detail");
            requireNonNull(markup, "markup");
            requireNonNull(changes, "changes");
            checkArgument(!Iterables.isEmpty(changes), "changes is empty.");
            client.push(projectName, repositoryName,
                        RevisionConverter.TO_DATA.convert(baseRevision),
                        AuthorConverter.TO_DATA.convert(author), summary,
                        new Comment(detail).setMarkup(MarkupConverter.TO_DATA.convert(markup)),
                        convertToList(changes, ChangeConverter.TO_DATA::convert),
                        callback);
        });
        return future.thenApply(PushResultConverter.TO_MODEL::convert);
    }

    @Override
    public CompletableFuture<Revision> watchRepository(String projectName, String repositoryName,
                                                       Revision lastKnownRevision,
                                                       String pathPattern,
                                                       long timeoutMillis) {
        final CompletableFuture<WatchRepositoryResult> future = run(callback -> {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(lastKnownRevision, "lastKnownRevision");
            validatePathPattern(pathPattern, "pathPattern");
            client.watchRepository(projectName, repositoryName,
                                   RevisionConverter.TO_DATA.convert(lastKnownRevision),
                                   pathPattern, timeoutMillis,
                                   callback);
        });
        return future.thenApply(r -> {
            if (r == null) {
                return null;
            }

            return RevisionConverter.TO_MODEL.convert(r.getRevision());
        });
    }

    @Override
    public <T> CompletableFuture<Entry<T>> watchFile(String projectName, String repositoryName,
                                                     Revision lastKnownRevision, Query<T> query,
                                                     long timeoutMillis) {

        final CompletableFuture<WatchFileResult> future = run(callback -> {
            validateProjectAndRepositoryName(projectName, repositoryName);
            requireNonNull(lastKnownRevision, "lastKnownRevision");
            requireNonNull(query, "query");
            client.watchFile(projectName, repositoryName,
                             RevisionConverter.TO_DATA.convert(lastKnownRevision),
                             QueryConverter.TO_DATA.convert(query),
                             timeoutMillis, callback);
        });
        return future.thenApply(r -> {
            if (r == null) {
                return null;
            }

            final Revision revision = RevisionConverter.TO_MODEL.convert(r.getRevision());
            if (revision == null) {
                return null;
            }

            final Entry<T> converted;
            switch (r.getType()) {
                case JSON:
                    try {
                        converted = unsafeCast(Entry.ofJson(revision, query.path(), r.getContent()));
                    } catch (JsonParseException e) {
                        throw new CompletionException("failed to parse the query result: " + query, e);
                    }
                    break;
                case TEXT:
                    converted = unsafeCast(Entry.ofText(revision, query.path(), r.getContent()));
                    break;
                case DIRECTORY:
                    converted = unsafeCast(Entry.ofDirectory(revision, query.path()));
                    break;
                default:
                    throw new Error("unknown entry type: " + r.getType());
            }

            return converted;
        });
    }

    private static void validateProjectName(String projectName) {
        Util.validateProjectName(projectName, "projectName");
    }

    private static void validateProjectAndRepositoryName(String projectName, String repositoryName) {
        validateProjectName(projectName);
        validateRepositoryName(repositoryName, "repositoryName");
    }

    @Nullable
    private static <T, U> List<T> convertToList(@Nullable Iterable<U> c, Function<U, T> mapper) {
        return convertToCollection(c, mapper, toImmutableList());
    }

    @Nullable
    private static <T, U> Set<T> convertToSet(@Nullable Iterable<U> c, Function<U, T> mapper) {
        return convertToCollection(c, mapper, toImmutableSet());
    }

    @Nullable
    private static <T, U, V extends Collection<T>> V convertToCollection(
            @Nullable Iterable<U> c, Function<U, T> mapper, Collector<T, ?, V> collector) {
        if (c == null) {
            return null;
        }

        final Stream<U> stream;
        if (c instanceof Collection) {
            stream = ((Collection<U>) c).stream();
        } else {
            stream = StreamSupport.stream(c.spliterator(), false);
        }

        return stream.map(mapper).collect(collector);
    }

    @Nullable
    private static <T, U, V, W> Map<T, U> convertToMap(
            @Nullable Collection<V> c, Function<V, W> entryMapper,
            Function<W, T> keyMapper, Function<W, U> valueMapper) {
        if (c == null) {
            return null;
        }
        return c.stream().map(entryMapper).collect(toImmutableMap(keyMapper, valueMapper));
    }

    @Nullable
    private static List<Change<?>> convertToChangesModel(
            List<com.linecorp.centraldogma.internal.thrift.Change> changes) {
        return convertToList(changes, ChangeConverter.TO_MODEL::convert);
    }

    private static <T> CompletableFuture<T> run(ThriftCall<T> call) {
        final ThriftCompletableFuture<T> future = new ThriftCompletableFuture<>();
        try {
            call.apply(future);
            return future.exceptionally(cause -> Exceptions.throwUnsafely(convertCause(cause)));
        } catch (Exception e) {
            return CompletableFutures.exceptionallyCompletedFuture(convertCause(e));
        }
    }

    private static Throwable convertCause(Throwable cause) {
        final Throwable peeledCause = Exceptions.peel(cause);
        final Throwable convertedCause;

        if (peeledCause instanceof com.linecorp.centraldogma.internal.thrift.CentralDogmaException) {
            final String message = peeledCause.getMessage();
            switch (((com.linecorp.centraldogma.internal.thrift.CentralDogmaException) peeledCause)
                    .getErrorCode()) {
                case UNIMPLEMENTED:
                    convertedCause = new CentralDogmaException("unimplemented", false);
                    break;
                case INTERNAL_SERVER_ERROR:
                    convertedCause = new CentralDogmaException("internal server error", false);
                    break;
                case BAD_REQUEST:
                    convertedCause = new CentralDogmaException("bad request", false);
                    break;
                case PROJECT_NOT_FOUND:
                    convertedCause = new ProjectNotFoundException(message, false);
                    break;
                case PROJECT_EXISTS:
                    convertedCause = new ProjectExistsException(message, false);
                    break;
                case REPOSITORY_NOT_FOUND:
                    convertedCause = new RepositoryNotFoundException(message, false);
                    break;
                case REPOSITORY_EXISTS:
                    convertedCause = new RepositoryExistsException(message, false);
                    break;
                case REVISION_NOT_FOUND:
                    convertedCause = new RevisionNotFoundException(message, false);
                    break;
                case REVISION_EXISTS:
                    convertedCause = new ChangeConflictException(message, false);
                    break;
                case ENTRY_NOT_FOUND:
                    convertedCause = new EntryNotFoundException(message, false);
                    break;
                case REDUNDANT_CHANGE:
                    convertedCause = new RedundantChangeException(message, false);
                    break;
                case CHANGE_CONFLICT:
                    convertedCause = new ChangeConflictException(message, false);
                    break;
                case QUERY_FAILURE:
                    convertedCause = new QueryExecutionException(message, false);
                    break;
                case SHUTTING_DOWN:
                    convertedCause = new ShuttingDownException(message, false);
                    break;
                default:
                    throw new Error();
            }
        } else {
            convertedCause = peeledCause;
        }
        return convertedCause;
    }

    @FunctionalInterface
    private interface ThriftCall<T> {
        void apply(ThriftCompletableFuture<T> callback) throws TException;
    }
}
