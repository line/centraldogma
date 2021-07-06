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

package com.linecorp.centraldogma.server.internal.storage.repository;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.FindOption;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public class RepositoryWrapper implements Repository {

    private final Repository repo;

    protected RepositoryWrapper(Repository repo) {
        this.repo = requireNonNull(repo, "repo");
    }

    @SuppressWarnings("unchecked")
    public final <T extends Repository> T unwrap() {
        return (T) repo;
    }

    @Override
    public Project parent() {
        return unwrap().parent();
    }

    @Override
    public String name() {
        return unwrap().name();
    }

    @Override
    public long creationTimeMillis() {
        return unwrap().creationTimeMillis();
    }

    @Override
    public Author author() {
        return unwrap().author();
    }

    @Override
    public Revision normalizeNow(Revision revision) {
        return unwrap().normalizeNow(revision);
    }

    @Override
    public RevisionRange normalizeNow(Revision from, Revision to) {
        return unwrap().normalizeNow(from, to);
    }

    @Override
    public CompletableFuture<Boolean> exists(Revision revision, String path) {
        return unwrap().exists(revision, path);
    }

    @Override
    public CompletableFuture<Entry<?>> get(Revision revision, String path) {
        return unwrap().get(revision, path);
    }

    @Override
    public <T> CompletableFuture<Entry<T>> get(Revision revision, Query<T> query) {
        return unwrap().get(revision, query);
    }

    @Override
    public CompletableFuture<Entry<?>> getOrNull(Revision revision, String path) {
        return unwrap().getOrNull(revision, path);
    }

    @Override
    public <T> CompletableFuture<Entry<T>> getOrNull(Revision revision, Query<T> query) {
        return unwrap().getOrNull(revision, query);
    }

    @Override
    public CompletableFuture<Map<String, Entry<?>>> find(Revision revision, String pathPattern) {
        return unwrap().find(revision, pathPattern);
    }

    @Override
    public CompletableFuture<Map<String, Entry<?>>> find(Revision revision, String pathPattern,
                                                         Map<FindOption<?>, ?> options) {
        return unwrap().find(revision, pathPattern, options);
    }

    @Override
    public CompletableFuture<List<Commit>> history(Revision from, Revision to, String pathPattern) {
        return unwrap().history(from, to, pathPattern);
    }

    @Override
    public CompletableFuture<List<Commit>> history(Revision from, Revision to,
                                                   String pathPattern, int maxCommits) {
        return unwrap().history(from, to, pathPattern, maxCommits);
    }

    @Override
    public CompletableFuture<Change<?>> diff(Revision from, Revision to, Query<?> query) {
        return unwrap().diff(from, to, query);
    }

    @Override
    public CompletableFuture<Map<String, Change<?>>> diff(Revision from, Revision to, String pathPattern) {
        return unwrap().diff(from, to, pathPattern);
    }

    @Override
    public CompletableFuture<Map<String, Change<?>>> previewDiff(Revision baseRevision,
                                                                 Iterable<Change<?>> changes) {
        return unwrap().previewDiff(baseRevision, changes);
    }

    @Override
    public CompletableFuture<Map<String, Change<?>>> previewDiff(Revision baseRevision, Change<?>... changes) {
        return unwrap().previewDiff(baseRevision, changes);
    }

    @Override
    public CompletableFuture<CommitResult> commit(Revision baseRevision, long commitTimeMillis,
                                                  Author author, String summary, Iterable<Change<?>> changes) {
        return unwrap().commit(baseRevision, commitTimeMillis, author, summary, changes);
    }

    @Override
    public CompletableFuture<CommitResult> commit(Revision baseRevision, long commitTimeMillis,
                                                  Author author, String summary, Change<?>... changes) {
        return unwrap().commit(baseRevision, commitTimeMillis, author, summary, changes);
    }

    @Override
    public CompletableFuture<CommitResult> commit(Revision baseRevision, long commitTimeMillis,
                                                  Author author, String summary, String detail, Markup markup,
                                                  Iterable<Change<?>> changes, boolean normalizing) {
        return unwrap().commit(baseRevision, commitTimeMillis, author, summary, detail, markup, changes,
                               normalizing);
    }

    @Override
    public CompletableFuture<CommitResult> commit(Revision baseRevision, long commitTimeMillis,
                                                  Author author, String summary, String detail, Markup markup,
                                                  Change<?>... changes) {
        return unwrap().commit(baseRevision, commitTimeMillis, author, summary, detail, markup, changes);
    }

    @Override
    public CompletableFuture<Revision> findLatestRevision(Revision lastKnownRevision, String pathPattern) {
        return unwrap().findLatestRevision(lastKnownRevision, pathPattern);
    }

    @Override
    public CompletableFuture<Revision> watch(Revision lastKnownRevision, String pathPattern) {
        return unwrap().watch(lastKnownRevision, pathPattern);
    }

    @Override
    public <T> CompletableFuture<Entry<T>> watch(Revision lastKnownRevision, Query<T> query) {
        return unwrap().watch(lastKnownRevision, query);
    }

    @Override
    public <T> CompletableFuture<MergedEntry<T>> mergeFiles(Revision revision, MergeQuery<T> query) {
        return unwrap().mergeFiles(revision, query);
    }

    @Override
    public void removeOldCommits(int minRetentionCommits, int minRetentionDays) {
        unwrap().removeOldCommits(minRetentionCommits, minRetentionDays);
    }

    @Override
    public String toString() {
        return Util.simpleTypeName(this) + '(' + unwrap() + ')';
    }
}
