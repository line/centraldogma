/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.storage.repository.cache;

import static com.linecorp.centraldogma.common.Author.SYSTEM;
import static com.linecorp.centraldogma.common.EntryType.JSON;
import static com.linecorp.centraldogma.common.Revision.HEAD;
import static com.linecorp.centraldogma.common.Revision.INIT;
import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ONE_WITH_CONTENT;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergeSource;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.DiffResultType;
import com.linecorp.centraldogma.server.storage.repository.Repository;

class CachingRepositoryTest {

    @Mock
    private Repository delegateRepo;

    @Test
    void identityQuery() {
        final CachingRepository repo = setMockNames(newCachingRepo());
        final Query<String> query = Query.ofText("/baz.txt");

        final Entry<String> result = Entry.ofText(new Revision(10), "/baz.txt", "qux");
        final Map<String, Entry<?>> entries = ImmutableMap.of("/baz.txt", result);

        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(new Revision(10));
        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(HEAD);

        // Uncached
        when(delegateRepo.find(any(), any(), any())).thenReturn(completedFuture(entries));
        assertThat(repo.get(HEAD, query).join()).isEqualTo(result);
        verify(delegateRepo).find(new Revision(10), "/baz.txt", FIND_ONE_WITH_CONTENT);
        verifyNoMoreDelegateInteractions();

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.get(HEAD, query).join()).isEqualTo(result);
        assertThat(repo.get(new Revision(10), query).join()).isEqualTo(result);
        verify(delegateRepo, never()).find(any(), any(), any());
        verifyNoMoreDelegateInteractions();
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonPathQuery() throws JsonParseException {
        final CachingRepository repo = setMockNames(newCachingRepo());
        final Query<JsonNode> query = Query.ofJsonPath("/baz.json", "$.a");
        final Entry<JsonNode> result = Entry.ofJson(new Revision(10), query.path(), "{\"a\": \"b\"}");
        final Entry<JsonNode> unexpected = Entry.ofJson(new Revision(10), "/foo.json", "{\"bar\": 1}");
        final Entry<JsonNode> queryResult = Entry.ofJson(new Revision(10), query.path(), "\"b\"");
        final Map<String, Entry<?>> entries = ImmutableMap.of("/baz.json", result,
                                                              "/foo.json", unexpected);

        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(new Revision(10));
        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(HEAD);

        // Uncached
        when(delegateRepo.find(any(), eq(query.path()), eq(FIND_ONE_WITH_CONTENT)))
                .thenReturn(completedFuture(entries));
        assertThat(repo.get(HEAD, query).join()).isEqualTo(queryResult);
        verify(delegateRepo).find(new Revision(10), query.path(), FIND_ONE_WITH_CONTENT);
        verifyNoMoreDelegateInteractions();

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.get(HEAD, query).join()).isEqualTo(queryResult);
        assertThat(repo.get(new Revision(10), query).join()).isEqualTo(queryResult);
        verify(delegateRepo, never()).getOrNull(any(), any(Query.class));
        verifyNoMoreDelegateInteractions();
    }

    @Test
    void mergeQuery() throws JsonProcessingException {
        final CachingRepository repo = setMockNames(newCachingRepo());
        final MergeQuery<JsonNode> query = MergeQuery.ofJson(MergeSource.ofRequired("/foo.json"),
                                                             MergeSource.ofRequired("/bar.json"));
        final MergedEntry<JsonNode> queryResult = MergedEntry.of(new Revision(10), JSON,
                                                                 Jackson.readTree("{\"a\": \"bar\"}"),
                                                                 "/foo.json", "/bar.json");

        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(new Revision(10));
        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(HEAD);

        // Uncached
        when(delegateRepo.mergeFiles(new Revision(10), query)).thenReturn(completedFuture(queryResult));
        assertThat(repo.mergeFiles(HEAD, query).join()).isEqualTo(queryResult);
        verify(delegateRepo).mergeFiles(new Revision(10), query);
        verifyNoMoreDelegateInteractions();

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.mergeFiles(HEAD, query).join()).isEqualTo(queryResult);
        assertThat(repo.mergeFiles(new Revision(10), query).join()).isEqualTo(queryResult);
        verify(delegateRepo, never()).find(any(), any(), any());
        verifyNoMoreDelegateInteractions();
    }

    @Test
    void identityQueryMissingEntry() {
        final CachingRepository repo = setMockNames(newCachingRepo());
        final Query<String> query = Query.ofText("/baz.txt");

        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(new Revision(10));
        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(HEAD);

        // Uncached
        when(delegateRepo.find(any(), any(), any())).thenReturn(completedFuture(ImmutableMap.of()));
        assertThat(repo.getOrNull(HEAD, query).join()).isNull();
        verify(delegateRepo).find(new Revision(10), "/baz.txt", FIND_ONE_WITH_CONTENT);
        verifyNoMoreDelegateInteractions();

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.getOrNull(HEAD, query).join()).isNull();
        assertThat(repo.getOrNull(new Revision(10), query).join()).isNull();
        verify(delegateRepo, never()).find(any(), any(), any());
        verifyNoMoreDelegateInteractions();
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonPathQueryMissingEntry() {
        final CachingRepository repo = setMockNames(newCachingRepo());
        final Query<JsonNode> query = Query.ofJsonPath("/baz.json", "$.a");

        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(new Revision(10));
        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(HEAD);

        // Uncached
        when(delegateRepo.find(any(), anyString(), anyMap())).thenReturn(completedFuture(ImmutableMap.of()));
        assertThat(repo.getOrNull(HEAD, query).join()).isNull();
        verify(delegateRepo).find(new Revision(10), query.path(), FIND_ONE_WITH_CONTENT);
        verifyNoMoreDelegateInteractions();

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.getOrNull(HEAD, query).join()).isNull();
        assertThat(repo.getOrNull(new Revision(10), query).join()).isNull();
        verify(delegateRepo, never()).getOrNull(any(), any(Query.class));
        verifyNoMoreDelegateInteractions();
    }

    /**
     * The generation has to be part of both halves of the key contract: equals() alone would still collide
     * in the same bucket, and hashCode() alone would leave two keys that compare equal.
     */
    @Test
    void aCacheKeyIsScopedToItsCacheGeneration() {
        final Repository repo = mock(Repository.class);
        final CacheableFindCall first = new CacheableFindCall(repo, new Revision(10), "/**",
                                                              ImmutableMap.of());
        final CacheableFindCall sameGeneration = new CacheableFindCall(repo, new Revision(10), "/**",
                                                                       ImmutableMap.of());
        assertThat(first).isEqualTo(sameGeneration);
        assertThat(first.hashCode()).isEqualTo(sameGeneration.hashCode());

        when(repo.cacheGeneration()).thenReturn(1);
        final CacheableFindCall nextGeneration = new CacheableFindCall(repo, new Revision(10), "/**",
                                                                       ImmutableMap.of());
        assertThat(first).isNotEqualTo(nextGeneration);
        assertThat(first.hashCode()).isNotEqualTo(nextGeneration.hashCode());
    }

    /**
     * A recovery rewrites the history in place, so the same revision may hold different content
     * afterwards. Nothing cached before the rewrite may be served after it.
     */
    @Test
    void aCacheGenerationBumpMakesCachedEntriesUnreachable() {
        final CachingRepository repo = setMockNames(newCachingRepo());
        final Map<String, Entry<?>> before =
                ImmutableMap.of("/baz.txt", Entry.ofText(new Revision(10), "/baz.txt", "before"));
        final Map<String, Entry<?>> after =
                ImmutableMap.of("/baz.txt", Entry.ofText(new Revision(10), "/baz.txt", "after"));

        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(new Revision(10));
        when(delegateRepo.find(any(), any(), any())).thenReturn(completedFuture(before));
        assertThat(repo.find(new Revision(10), "/**", ImmutableMap.of()).join()).isEqualTo(before);

        // Same revision, same query, but the history it was read from is gone.
        clearInvocations(delegateRepo);
        when(delegateRepo.cacheGeneration()).thenReturn(1);
        when(delegateRepo.find(any(), any(), any())).thenReturn(completedFuture(after));
        assertThat(repo.find(new Revision(10), "/**", ImmutableMap.of()).join()).isEqualTo(after);
        verify(delegateRepo).find(new Revision(10), "/**", ImmutableMap.of());
    }

    @Test
    void find() {
        final CachingRepository repo = setMockNames(newCachingRepo());
        final Map<String, Entry<?>> entries =
                ImmutableMap.of("/baz.txt", Entry.ofText(new Revision(10), "/baz.txt", "qux"));

        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(new Revision(10));
        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(HEAD);

        // Uncached
        when(delegateRepo.find(any(), any(), any())).thenReturn(completedFuture(entries));
        assertThat(repo.find(HEAD, "/**", ImmutableMap.of()).join()).isEqualTo(entries);
        verify(delegateRepo).find(new Revision(10), "/**", ImmutableMap.of());
        verifyNoMoreDelegateInteractions();

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.find(HEAD, "/**", ImmutableMap.of()).join()).isEqualTo(entries);
        assertThat(repo.find(new Revision(10), "/**", ImmutableMap.of()).join()).isEqualTo(entries);
        verify(delegateRepo, never()).find(any(), any(), any());
        verifyNoMoreDelegateInteractions();
    }

    @Test
    void history() {
        final CachingRepository repo = setMockNames(newCachingRepo());
        final List<Commit> commits = ImmutableList.of(
                new Commit(new Revision(3), SYSTEM, "third", "", Markup.MARKDOWN),
                new Commit(new Revision(3), SYSTEM, "second", "", Markup.MARKDOWN),
                new Commit(new Revision(3), SYSTEM, "first", "", Markup.MARKDOWN));

        doReturn(new RevisionRange(3, 1)).when(delegateRepo).normalizeNow(HEAD, INIT);
        doReturn(new RevisionRange(3, 1)).when(delegateRepo).normalizeNow(HEAD, new Revision(-3));
        doReturn(new RevisionRange(3, 1)).when(delegateRepo).normalizeNow(new Revision(3), new Revision(-3));
        doReturn(new RevisionRange(3, 1)).when(delegateRepo).normalizeNow(new Revision(3), INIT);

        // Uncached
        when(delegateRepo.history(any(), any(), any(), anyInt())).thenReturn(completedFuture(commits));
        assertThat(repo.history(HEAD, INIT, "/**", Integer.MAX_VALUE).join()).isEqualTo(commits);
        verify(delegateRepo).history(new Revision(3), INIT, "/**", 3);
        verifyNoMoreDelegateInteractions();

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.history(HEAD, new Revision(-3), "/**", 3).join()).isEqualTo(commits);
        assertThat(repo.history(HEAD, INIT, "/**", 4).join()).isEqualTo(commits);
        assertThat(repo.history(new Revision(3), new Revision(-3), "/**", 5).join()).isEqualTo(commits);
        assertThat(repo.history(new Revision(3), INIT, "/**", 6).join()).isEqualTo(commits);
        verify(delegateRepo, never()).history(any(), any(), any(), anyInt());
        verifyNoMoreDelegateInteractions();
    }

    @Test
    void singleDiff() {
        final CachingRepository repo = setMockNames(newCachingRepo());
        final Query<String> query = Query.ofText("/foo.txt");
        final Change<String> change = Change.ofTextUpsert(query.path(), "bar");

        doReturn(new RevisionRange(10, 1)).when(delegateRepo).normalizeNow(HEAD, INIT);
        doReturn(new RevisionRange(10, 1)).when(delegateRepo).normalizeNow(new Revision(10), INIT);
        doReturn(new RevisionRange(10, 1)).when(delegateRepo).normalizeNow(new Revision(-1), new Revision(-10));
        doReturn(new RevisionRange(10, 1)).when(delegateRepo).normalizeNow(new Revision(10), new Revision(-10));

        // Uncached
        when(delegateRepo.diff(any(), any(), any(Query.class))).thenReturn(completedFuture(change));
        assertThat(repo.diff(HEAD, INIT, query).join()).isEqualTo(change);
        verify(delegateRepo).diff(INIT, new Revision(10), query);
        verifyNoMoreDelegateInteractions();

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.diff(HEAD, new Revision(-10), query).join()).isEqualTo(change);
        assertThat(repo.diff(HEAD, INIT, query).join()).isEqualTo(change);
        assertThat(repo.diff(new Revision(10), new Revision(-10), query).join()).isEqualTo(change);
        assertThat(repo.diff(new Revision(10), INIT, query).join()).isEqualTo(change);
        verify(delegateRepo, never()).diff(any(), any(), any(Query.class));
        verifyNoMoreDelegateInteractions();
    }

    @Test
    void multiDiff() {
        final CachingRepository repo = setMockNames(newCachingRepo());
        final Map<String, Change<?>> changes = ImmutableMap.of(
                "/foo.txt", Change.ofTextUpsert("/foo.txt", "bar"));

        doReturn(new RevisionRange(10, 1)).when(delegateRepo).normalizeNow(HEAD, INIT);
        doReturn(new RevisionRange(10, 1)).when(delegateRepo).normalizeNow(new Revision(10), INIT);
        doReturn(new RevisionRange(10, 1)).when(delegateRepo).normalizeNow(new Revision(-1), new Revision(-10));
        doReturn(new RevisionRange(10, 1)).when(delegateRepo).normalizeNow(new Revision(10), new Revision(-10));

        // Uncached
        when(delegateRepo.diff(any(), any(), any(String.class), any())).thenReturn(completedFuture(changes));
        assertThat(repo.diff(HEAD, INIT, "/**").join()).isEqualTo(changes);
        verify(delegateRepo).diff(INIT, new Revision(10), "/**", DiffResultType.NORMAL);
        verifyNoMoreDelegateInteractions();

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.diff(HEAD, new Revision(-10), "/**").join()).isEqualTo(changes);
        assertThat(repo.diff(HEAD, INIT, "/**").join()).isEqualTo(changes);
        assertThat(repo.diff(new Revision(10), new Revision(-10), "/**").join()).isEqualTo(changes);
        assertThat(repo.diff(new Revision(10), INIT, "/**").join()).isEqualTo(changes);
        verify(delegateRepo, never()).diff(any(), any(), any(Query.class), any());
        verifyNoMoreDelegateInteractions();
    }

    @Test
    void findLatestRevision() {
        final CachingRepository repo = setMockNames(newCachingRepo());
        doReturn(new RevisionRange(INIT, new Revision(2))).when(delegateRepo).normalizeNow(INIT, HEAD);

        // Uncached
        when(delegateRepo.findLatestRevision(any(), any(), anyBoolean())).thenReturn(
                completedFuture(new Revision(2)));
        assertThat(repo.findLatestRevision(INIT, "/**", false).join()).isEqualTo(new Revision(2));
        verify(delegateRepo).findLatestRevision(INIT, "/**", false);
        verifyNoMoreDelegateInteractions();

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.findLatestRevision(INIT, "/**", false).join()).isEqualTo(new Revision(2));
        verify(delegateRepo, never()).findLatestRevision(any(), any(), anyBoolean());
        verifyNoMoreDelegateInteractions();
    }

    @Test
    void findLatestRevisionNull() {
        final CachingRepository repo = setMockNames(newCachingRepo());
        doReturn(new RevisionRange(INIT, new Revision(2))).when(delegateRepo).normalizeNow(INIT, HEAD);

        // Uncached
        when(delegateRepo.findLatestRevision(any(), any(), anyBoolean())).thenReturn(completedFuture(null));
        assertThat(repo.findLatestRevision(INIT, "/**", false).join()).isNull();
        verify(delegateRepo).findLatestRevision(INIT, "/**", false);
        verifyNoMoreDelegateInteractions();

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.findLatestRevision(INIT, "/**", false).join()).isNull();
        verify(delegateRepo, never()).findLatestRevision(any(), any(), anyBoolean());
        verifyNoMoreDelegateInteractions();
    }

    @Test
    void finaLatestRevisionHead() {
        final Repository repo = newCachingRepo();
        final Revision actualHeadRev = new Revision(2);
        doReturn(new RevisionRange(actualHeadRev, actualHeadRev))
                .when(delegateRepo).normalizeNow(HEAD, HEAD);

        assertThat(repo.findLatestRevision(HEAD, "/**", false).join()).isNull();
        verify(delegateRepo, never()).findLatestRevision(any(), any(), anyBoolean());
        verifyNoMoreDelegateInteractions();
    }

    @Test
    void watchFastPath() {
        final CachingRepository repo = setMockNames(newCachingRepo());
        doReturn(new RevisionRange(INIT, new Revision(2))).when(delegateRepo).normalizeNow(INIT, HEAD);

        // Uncached
        when(delegateRepo.findLatestRevision(any(), any(), anyBoolean())).thenReturn(
                completedFuture(new Revision(2)));
        assertThat(repo.watch(INIT, "/**", false).join()).isEqualTo(new Revision(2));
        verify(delegateRepo).findLatestRevision(INIT, "/**", false);
        verify(delegateRepo, never()).watch(any(), any(String.class), anyBoolean());
        verifyNoMoreDelegateInteractions();

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.watch(INIT, "/**", false).join()).isEqualTo(new Revision(2));
        verify(delegateRepo, never()).findLatestRevision(any(), any(), anyBoolean());
        verify(delegateRepo, never()).watch(any(), any(String.class), anyBoolean());
        verifyNoMoreDelegateInteractions();
    }

    @Test
    void watchSlowPath() {
        final CachingRepository repo = setMockNames(newCachingRepo());
        doReturn(new RevisionRange(INIT, new Revision(2))).when(delegateRepo).normalizeNow(INIT, HEAD);

        final CompletableFuture<Revision> delegateWatchFuture = new CompletableFuture<>();
        when(delegateRepo.findLatestRevision(any(), any(), anyBoolean())).thenReturn(completedFuture(null));
        when(delegateRepo.watch(any(), any(String.class), anyBoolean())).thenReturn(delegateWatchFuture);

        // Make sure the future returned by CachingRepository.watch() depends on
        // the future returned by delegateRepo.watch().
        final CompletableFuture<Revision> watchFuture = repo.watch(INIT, "/**", false);
        assertThat(watchFuture).isNotDone();
        delegateWatchFuture.complete(new Revision(3));
        assertThat(watchFuture.join()).isSameAs(delegateWatchFuture.join());

        verify(delegateRepo).findLatestRevision(INIT, "/**", false);
        verify(delegateRepo).watch(INIT, "/**", false);
        verifyNoMoreDelegateInteractions();
    }

    private CachingRepository newCachingRepo() {
        final CachingRepository cachingRepo = new CachingRepository(
                delegateRepo, new RepositoryCache("maximumSize=1000", NoopMeterRegistry.get()));

        verifyNoMoreDelegateInteractions();
        clearInvocations(delegateRepo);

        return cachingRepo;
    }

    private static CachingRepository setMockNames(CachingRepository mockRepo) {
        final Project project = mock(Project.class);
        when(mockRepo.parent()).thenReturn(project);
        when(project.name()).thenReturn("mock_proj");
        when(mockRepo.name()).thenReturn("mock_repo");
        return mockRepo;
    }

    /**
     * Asserts that the delegate saw nothing beyond the reads already verified. Every cache key carries
     * {@link Repository#cacheGeneration()}, so that read is expected on any call, cached or not.
     */
    private void verifyNoMoreDelegateInteractions() {
        verify(delegateRepo, atLeast(0)).cacheGeneration();
        verifyNoMoreInteractions(delegateRepo);
    }
}
