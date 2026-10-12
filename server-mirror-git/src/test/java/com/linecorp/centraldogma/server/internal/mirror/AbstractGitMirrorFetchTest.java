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

package com.linecorp.centraldogma.server.internal.mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;

import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectDatabase;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.FetchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableSet;

class AbstractGitMirrorFetchTest {

    private static final String HEAD = Constants.R_HEADS + Constants.MASTER;

    @TempDir
    Path tempDir;

    @Test
    void ordinaryTransportFailureInvalidatesTheFetchCache() throws Exception {
        try (Repository repository = newRepository()) {
            final ObjectId oldHead = createCommit(repository);
            setHead(repository, oldHead);
            final ObjectDatabase objectDatabase = repository.getObjectDatabase();
            objectDatabase.setShallowCommits(ImmutableSet.of(oldHead));

            final GitWithAuth git = mock(GitWithAuth.class);
            when(git.getRepository()).thenReturn(repository);
            final FetchCommand fetch = mock(FetchCommand.class, RETURNS_SELF);
            when(git.fetch()).thenReturn(fetch);
            final TransportException failure =
                    new TransportException("network failure", new IOException("connection reset"));
            final ObjectId partialShallowCommit = ObjectId.fromString(
                    "2222222222222222222222222222222222222222");
            when(fetch.call()).thenAnswer(unused -> {
                objectDatabase.setShallowCommits(ImmutableSet.of(partialShallowCommit));
                throw failure;
            });

            assertThatThrownBy(() -> AbstractGitMirror.fetchRemoteHeadWithRetry(git, HEAD, 100))
                    .isSameAs(failure);
            assertThat(repository.exactRef(HEAD)).isNull();
            assertThat(objectDatabase.getShallowCommits()).isEmpty();
            verify(git, times(1)).fetch();
        }
    }

    @Test
    void failedPackProtocolRetryLeavesTheFetchCacheInvalidated() throws Exception {
        try (Repository repository = newRepository()) {
            final ObjectId oldHead = createCommit(repository);
            setHead(repository, oldHead);
            final ObjectDatabase objectDatabase = repository.getObjectDatabase();
            objectDatabase.setShallowCommits(ImmutableSet.of(oldHead));

            final GitWithAuth git = mock(GitWithAuth.class);
            when(git.getRepository()).thenReturn(repository);
            final FetchCommand firstFetch = mock(FetchCommand.class, RETURNS_SELF);
            final FetchCommand retry = mock(FetchCommand.class, RETURNS_SELF);
            when(git.fetch()).thenReturn(firstFetch, retry);
            final ObjectId partialShallowCommit = ObjectId.fromString(
                    "1111111111111111111111111111111111111111");
            when(firstFetch.call()).thenAnswer(unused -> {
                objectDatabase.setShallowCommits(ImmutableSet.of(partialShallowCommit));
                throw new TransportException(
                        "pack failure", new PackProtocolException("Expected ACK/NAK"));
            });
            final TransportException retryFailure = new TransportException("retry failed");
            when(retry.call()).thenThrow(retryFailure);

            assertThatThrownBy(() -> AbstractGitMirror.fetchRemoteHeadWithRetry(git, HEAD, 100))
                    .isSameAs(retryFailure);
            assertThat(repository.exactRef(HEAD)).isNull();
            assertThat(objectDatabase.getShallowCommits()).isEmpty();
            verify(git, times(2)).fetch();
        }
    }

    @Test
    void packProtocolFailureResetsTheCacheAndRetries() throws Exception {
        try (Repository repository = newRepository()) {
            final ObjectId oldHead = createCommit(repository);
            setHead(repository, oldHead);
            repository.getObjectDatabase().setShallowCommits(ImmutableSet.of(oldHead));

            final GitWithAuth git = mock(GitWithAuth.class);
            when(git.getRepository()).thenReturn(repository);
            final FetchCommand firstFetch = mock(FetchCommand.class, RETURNS_SELF);
            final FetchCommand retry = mock(FetchCommand.class, RETURNS_SELF);
            when(git.fetch()).thenReturn(firstFetch, retry);
            when(firstFetch.call()).thenThrow(
                    new TransportException("pack failure", new PackProtocolException("Expected ACK/NAK")));
            final FetchResult retryResult = mock(FetchResult.class);
            when(retry.call()).thenReturn(retryResult);

            assertThat(AbstractGitMirror.fetchRemoteHeadWithRetry(git, HEAD, 100)).isSameAs(retryResult);
            assertThat(repository.exactRef(HEAD)).isNull();
            assertThat(repository.getObjectDatabase().getShallowCommits()).isEmpty();
            verify(git, times(2)).fetch();
        }
    }

    @Test
    void unboundedFetchRetriesWithInitialDepthAfterResettingCache() throws Exception {
        try (Repository repository = newRepository()) {
            final ObjectId oldHead = createCommit(repository);
            setHead(repository, oldHead);
            repository.getObjectDatabase().setShallowCommits(ImmutableSet.of(oldHead));

            final GitWithAuth git = mock(GitWithAuth.class);
            when(git.getRepository()).thenReturn(repository);
            final FetchCommand firstFetch = mock(FetchCommand.class, RETURNS_SELF);
            final FetchCommand retry = mock(FetchCommand.class, RETURNS_SELF);
            when(git.fetch()).thenReturn(firstFetch, retry);
            when(firstFetch.call()).thenThrow(
                    new TransportException("pack failure", new PackProtocolException("Expected ACK/NAK")));
            final FetchResult retryResult = mock(FetchResult.class);
            when(retry.call()).thenReturn(retryResult);

            assertThat(AbstractGitMirror.fetchRemoteHeadWithRetry(git, HEAD, 0)).isSameAs(retryResult);
            verify(retry).setDepth(100);
        }
    }

    @Test
    void runtimeRetryFailureLeavesTheFetchCacheInvalidated() throws Exception {
        try (Repository repository = newRepository()) {
            final ObjectId oldHead = createCommit(repository);
            setHead(repository, oldHead);
            repository.getObjectDatabase().setShallowCommits(ImmutableSet.of(oldHead));

            final GitWithAuth git = mock(GitWithAuth.class);
            when(git.getRepository()).thenReturn(repository);
            final FetchCommand firstFetch = mock(FetchCommand.class, RETURNS_SELF);
            final FetchCommand retry = mock(FetchCommand.class, RETURNS_SELF);
            when(git.fetch()).thenReturn(firstFetch, retry);
            when(firstFetch.call()).thenThrow(
                    new TransportException("pack failure", new PackProtocolException("Expected ACK/NAK")));
            final JGitInternalException retryFailure = new JGitInternalException("retry failed");
            when(retry.call()).thenThrow(retryFailure);

            assertThatThrownBy(() -> AbstractGitMirror.fetchRemoteHeadWithRetry(git, HEAD, 100))
                    .isSameAs(retryFailure);
            assertThat(repository.exactRef(HEAD)).isNull();
            assertThat(repository.getObjectDatabase().getShallowCommits()).isEmpty();
            verify(git, times(2)).fetch();
        }
    }

    private Repository newRepository() throws IOException {
        final Repository repository =
                new FileRepositoryBuilder().setGitDir(tempDir.resolve("cache.git").toFile()).setBare().build();
        repository.create(true);
        return repository;
    }

    private static ObjectId createCommit(Repository repository) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            final ObjectId treeId = inserter.insert(Constants.OBJ_TREE, new byte[0]);
            final PersonIdent ident =
                    new PersonIdent("Mirror", "mirror@localhost.localdomain", Instant.EPOCH, ZoneOffset.UTC);
            final CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("Initial commit");
            final ObjectId commitId = inserter.insert(commit);
            inserter.flush();
            return commitId;
        }
    }

    private static void setHead(Repository repository, ObjectId commitId) throws IOException {
        final RefUpdate update = repository.updateRef(HEAD);
        update.setNewObjectId(commitId);
        assertThat(update.update()).isEqualTo(RefUpdate.Result.NEW);
    }
}
