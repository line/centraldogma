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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
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
import com.linecorp.centraldogma.internal.jackson.Jackson;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

import io.micrometer.core.instrument.MeterRegistry;

class CachingRepositoryTest {

    @Mock
    private Repository delegateRepo;

    @Test
    void identityQuery() {
        final Repository repo = setMockNames(newCachingRepo());
        final Query<String> query = Query.ofText("/baz.txt");

        final Entry<String> result = Entry.ofText(new Revision(10), "/baz.txt", "qux");
        final Map<String, Entry<?>> entries = ImmutableMap.of("/baz.txt", result);

        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(new Revision(10));
        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(HEAD);

        // Uncached
        when(delegateRepo.find(any(), any(), any())).thenReturn(completedFuture(entries));
        assertThat(repo.get(HEAD, query).join()).isEqualTo(result);
        verify(delegateRepo).find(new Revision(10), "/baz.txt", FIND_ONE_WITH_CONTENT);
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.get(HEAD, query).join()).isEqualTo(result);
        assertThat(repo.get(new Revision(10), query).join()).isEqualTo(result);
        verify(delegateRepo, never()).find(any(), any(), any());
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonPathQuery() throws JsonParseException {
        final Repository repo = setMockNames(newCachingRepo());
        final Query<JsonNode> query = Query.ofJsonPath("/baz.json", "$.a");
        final Entry<JsonNode> queryResult = Entry.ofJson(new Revision(10), query.path(), "{\"a\": \"b\"}");

        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(new Revision(10));
        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(HEAD);

        // Uncached
        when(delegateRepo.getOrNull(any(), any(Query.class))).thenReturn(completedFuture(queryResult));
        assertThat(repo.get(HEAD, query).join()).isEqualTo(queryResult);
        verify(delegateRepo).getOrNull(new Revision(10), query);
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.get(HEAD, query).join()).isEqualTo(queryResult);
        assertThat(repo.get(new Revision(10), query).join()).isEqualTo(queryResult);
        verify(delegateRepo, never()).getOrNull(any(), any(Query.class));
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    void mergeQuery() throws JsonParseException {
        final Repository repo = setMockNames(newCachingRepo());
        final MergeQuery<JsonNode> query = MergeQuery.ofJson(MergeSource.ofRequired("/foo.json"),
                                                             MergeSource.ofRequired("/bar.json"));
        final MergedEntry<JsonNode> queryResult = MergedEntry.of(new Revision(10), JSON,
                                                                 Jackson.ofJson().readTree("{\"a\": \"bar\"}"),
                                                                 "/foo.json", "/bar.json");

        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(new Revision(10));
        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(HEAD);

        // Uncached
        when(delegateRepo.find(any(), any(), any()))
                .thenReturn(completedFuture(ImmutableMap.of("/foo.json", Entry.ofJson(
                        new Revision(10), "/foo.json", "{\"a\": \"foo\"}"))))
                .thenReturn(completedFuture(ImmutableMap.of("/bar.json", Entry.ofJson(
                        new Revision(10), "/bar.json", "{\"a\": \"bar\"}"))));

        assertThat(repo.mergeFiles(HEAD, query).join()).isEqualTo(queryResult);
        verify(delegateRepo).find(new Revision(10), "/foo.json", FIND_ONE_WITH_CONTENT);
        verify(delegateRepo).find(new Revision(10), "/bar.json", FIND_ONE_WITH_CONTENT);
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.mergeFiles(HEAD, query).join()).isEqualTo(queryResult);
        assertThat(repo.mergeFiles(new Revision(10), query).join()).isEqualTo(queryResult);
        verify(delegateRepo, never()).find(any(), any(), any());
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    void identityQueryMissingEntry() {
        final Repository repo = setMockNames(newCachingRepo());
        final Query<String> query = Query.ofText("/baz.txt");

        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(new Revision(10));
        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(HEAD);

        // Uncached
        when(delegateRepo.find(any(), any(), any())).thenReturn(completedFuture(ImmutableMap.of()));
        assertThat(repo.getOrNull(HEAD, query).join()).isNull();
        verify(delegateRepo).find(new Revision(10), "/baz.txt", FIND_ONE_WITH_CONTENT);
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.getOrNull(HEAD, query).join()).isNull();
        assertThat(repo.getOrNull(new Revision(10), query).join()).isNull();
        verify(delegateRepo, never()).find(any(), any(), any());
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonPathQueryMissingEntry() {
        final Repository repo = setMockNames(newCachingRepo());
        final Query<JsonNode> query = Query.ofJsonPath("/baz.json", "$.a");

        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(new Revision(10));
        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(HEAD);

        // Uncached
        when(delegateRepo.getOrNull(any(), any(Query.class))).thenReturn(completedFuture(null));
        assertThat(repo.getOrNull(HEAD, query).join()).isNull();
        verify(delegateRepo).getOrNull(new Revision(10), query);
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.getOrNull(HEAD, query).join()).isNull();
        assertThat(repo.getOrNull(new Revision(10), query).join()).isNull();
        verify(delegateRepo, never()).getOrNull(any(), any(Query.class));
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    void find() {
        final Repository repo = setMockNames(newCachingRepo());
        final Map<String, Entry<?>> entries =
                ImmutableMap.of("/baz.txt", Entry.ofText(new Revision(10), "/baz.txt", "qux"));

        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(new Revision(10));
        doReturn(new Revision(10)).when(delegateRepo).normalizeNow(HEAD);

        // Uncached
        when(delegateRepo.find(any(), any(), any())).thenReturn(completedFuture(entries));
        assertThat(repo.find(HEAD, "/**", ImmutableMap.of()).join()).isEqualTo(entries);
        verify(delegateRepo).find(new Revision(10), "/**", ImmutableMap.of());
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.find(HEAD, "/**", ImmutableMap.of()).join()).isEqualTo(entries);
        assertThat(repo.find(new Revision(10), "/**", ImmutableMap.of()).join()).isEqualTo(entries);
        verify(delegateRepo, never()).find(any(), any(), any());
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    void history() {
        final Repository repo = setMockNames(newCachingRepo());
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
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.history(HEAD, new Revision(-3), "/**", 3).join()).isEqualTo(commits);
        assertThat(repo.history(HEAD, INIT, "/**", 4).join()).isEqualTo(commits);
        assertThat(repo.history(new Revision(3), new Revision(-3), "/**", 5).join()).isEqualTo(commits);
        assertThat(repo.history(new Revision(3), INIT, "/**", 6).join()).isEqualTo(commits);
        verify(delegateRepo, never()).history(any(), any(), any(), anyInt());
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    void singleDiff() {
        final Repository repo = setMockNames(newCachingRepo());
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
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.diff(HEAD, new Revision(-10), query).join()).isEqualTo(change);
        assertThat(repo.diff(HEAD, INIT, query).join()).isEqualTo(change);
        assertThat(repo.diff(new Revision(10), new Revision(-10), query).join()).isEqualTo(change);
        assertThat(repo.diff(new Revision(10), INIT, query).join()).isEqualTo(change);
        verify(delegateRepo, never()).diff(any(), any(), any(Query.class));
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    void multiDiff() {
        final Repository repo = setMockNames(newCachingRepo());
        final Map<String, Change<?>> changes = ImmutableMap.of(
                "/foo.txt", Change.ofTextUpsert("/foo.txt", "bar"));

        doReturn(new RevisionRange(10, 1)).when(delegateRepo).normalizeNow(HEAD, INIT);
        doReturn(new RevisionRange(10, 1)).when(delegateRepo).normalizeNow(new Revision(10), INIT);
        doReturn(new RevisionRange(10, 1)).when(delegateRepo).normalizeNow(new Revision(-1), new Revision(-10));
        doReturn(new RevisionRange(10, 1)).when(delegateRepo).normalizeNow(new Revision(10), new Revision(-10));

        // Uncached
        when(delegateRepo.diff(any(), any(), any(String.class))).thenReturn(completedFuture(changes));
        assertThat(repo.diff(HEAD, INIT, "/**").join()).isEqualTo(changes);
        verify(delegateRepo).diff(INIT, new Revision(10), "/**");
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.diff(HEAD, new Revision(-10), "/**").join()).isEqualTo(changes);
        assertThat(repo.diff(HEAD, INIT, "/**").join()).isEqualTo(changes);
        assertThat(repo.diff(new Revision(10), new Revision(-10), "/**").join()).isEqualTo(changes);
        assertThat(repo.diff(new Revision(10), INIT, "/**").join()).isEqualTo(changes);
        verify(delegateRepo, never()).diff(any(), any(), any(Query.class));
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    void findLatestRevision() {
        final Repository repo = setMockNames(newCachingRepo());
        doReturn(new RevisionRange(INIT, new Revision(2))).when(delegateRepo).normalizeNow(INIT, HEAD);

        // Uncached
        when(delegateRepo.findLatestRevision(any(), any(), anyBoolean())).thenReturn(
                completedFuture(new Revision(2)));
        assertThat(repo.findLatestRevision(INIT, "/**", false).join()).isEqualTo(new Revision(2));
        verify(delegateRepo).findLatestRevision(INIT, "/**", false);
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.findLatestRevision(INIT, "/**", false).join()).isEqualTo(new Revision(2));
        verify(delegateRepo, never()).findLatestRevision(any(), any(), anyBoolean());
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    void findLatestRevisionNull() {
        final Repository repo = setMockNames(newCachingRepo());
        doReturn(new RevisionRange(INIT, new Revision(2))).when(delegateRepo).normalizeNow(INIT, HEAD);

        // Uncached
        when(delegateRepo.findLatestRevision(any(), any(), anyBoolean())).thenReturn(completedFuture(null));
        assertThat(repo.findLatestRevision(INIT, "/**", false).join()).isNull();
        verify(delegateRepo).findLatestRevision(INIT, "/**", false);
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.findLatestRevision(INIT, "/**", false).join()).isNull();
        verify(delegateRepo, never()).findLatestRevision(any(), any(), anyBoolean());
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    void finaLatestRevisionHead() {
        final Repository repo = newCachingRepo();
        final Revision actualHeadRev = new Revision(2);
        doReturn(new RevisionRange(actualHeadRev, actualHeadRev))
                .when(delegateRepo).normalizeNow(HEAD, HEAD);

        assertThat(repo.findLatestRevision(HEAD, "/**", false).join()).isNull();
        verify(delegateRepo, never()).findLatestRevision(any(), any(), anyBoolean());
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    void watchFastPath() {
        final Repository repo = setMockNames(newCachingRepo());
        doReturn(new RevisionRange(INIT, new Revision(2))).when(delegateRepo).normalizeNow(INIT, HEAD);

        // Uncached
        when(delegateRepo.findLatestRevision(any(), any(), anyBoolean())).thenReturn(
                completedFuture(new Revision(2)));
        assertThat(repo.watch(INIT, "/**", false).join()).isEqualTo(new Revision(2));
        verify(delegateRepo).findLatestRevision(INIT, "/**", false);
        verify(delegateRepo, never()).watch(any(), any(String.class), anyBoolean());
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.watch(INIT, "/**", false).join()).isEqualTo(new Revision(2));
        verify(delegateRepo, never()).findLatestRevision(any(), any(), anyBoolean());
        verify(delegateRepo, never()).watch(any(), any(String.class), anyBoolean());
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    void watchSlowPath() {
        final Repository repo = setMockNames(newCachingRepo());
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
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    void metrics() {
        final MeterRegistry meterRegistry = PrometheusMeterRegistries.newRegistry();
        final Repository repo = newCachingRepo(meterRegistry);
        final Map<String, Double> meters = MoreMeters.measureAll(meterRegistry);
        assertThat(meters).containsKeys("cache.load#count{cache=repository,result=success}");

        // Do something with 'repo' so that it is not garbage-collected even before the meters are measured.
        assertThat(repo.normalizeNow(HEAD)).isNotEqualTo("");
    }

    private Repository newCachingRepo() {
        return newCachingRepo(NoopMeterRegistry.get());
    }

    private Repository newCachingRepo(MeterRegistry meterRegistry) {
        when(delegateRepo.history(INIT, INIT, Repository.ALL_PATH, 1)).thenReturn(completedFuture(
                ImmutableList.of(new Commit(INIT, SYSTEM, "", "", Markup.PLAINTEXT))));

        final Repository cachingRepo = new CachingRepository(
                delegateRepo, new RepositoryCache("maximumSize=1000", meterRegistry));

        // Verify that CachingRepository calls delegateRepo.history() once to retrieve the initial commit.
        verify(delegateRepo, times(1)).history(INIT, INIT, Repository.ALL_PATH, 1);

        verifyNoMoreInteractions(delegateRepo);
        clearInvocations(delegateRepo);

        return cachingRepo;
    }

    private static Repository setMockNames(Repository mockRepo) {
        final Project project = mock(Project.class);
        when(mockRepo.parent()).thenReturn(project);
        when(project.name()).thenReturn("mock_proj");
        when(mockRepo.name()).thenReturn("mock_repo");
        return mockRepo;
    }
}
