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
package com.linecorp.centraldogma.server.internal.storage.repository.cache;

import static com.linecorp.centraldogma.common.Author.SYSTEM;
import static com.linecorp.centraldogma.common.Revision.HEAD;
import static com.linecorp.centraldogma.common.Revision.INIT;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.quality.Strictness;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

public class CachingRepositoryTest {

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS);

    @Mock
    private Repository delegateRepo;

    @Test
    @SuppressWarnings("unchecked")
    public void query() {
        final Repository repo = setMockNames(newCachingRepo());
        final Query<String> query = Query.ofText("/baz.txt");
        final Entry<String> queryResult = Entry.ofText(new Revision(10), query.path(), "qux");

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
    @SuppressWarnings("unchecked")
    public void queryMissingEntry() {
        final Repository repo = setMockNames(newCachingRepo());
        final Query<String> query = Query.ofText("/baz.txt");

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
    public void find() {
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
    public void history() {
        final Repository repo = setMockNames(newCachingRepo());
        final List<Commit> commits = ImmutableList.of(
                new Commit(new Revision(3), SYSTEM, "third",  "", Markup.MARKDOWN),
                new Commit(new Revision(3), SYSTEM, "second", "", Markup.MARKDOWN),
                new Commit(new Revision(3), SYSTEM, "first",  "", Markup.MARKDOWN));

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
    public void singleDiff() {
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
    public void multiDiff() {
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
    public void findLatestRevision() {
        final Repository repo = setMockNames(newCachingRepo());
        doReturn(new RevisionRange(INIT, new Revision(2))).when(delegateRepo).normalizeNow(INIT, HEAD);

        // Uncached
        when(delegateRepo.findLatestRevision(any(), any())).thenReturn(completedFuture(new Revision(2)));
        assertThat(repo.findLatestRevision(INIT, "/**").join()).isEqualTo(new Revision(2));
        verify(delegateRepo).findLatestRevision(INIT, "/**");
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.findLatestRevision(INIT, "/**").join()).isEqualTo(new Revision(2));
        verify(delegateRepo, never()).findLatestRevision(any(), any());
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    public void findLatestRevisionNull() {
        final Repository repo = setMockNames(newCachingRepo());
        doReturn(new RevisionRange(INIT, new Revision(2))).when(delegateRepo).normalizeNow(INIT, HEAD);

        // Uncached
        when(delegateRepo.findLatestRevision(any(), any())).thenReturn(completedFuture(null));
        assertThat(repo.findLatestRevision(INIT, "/**").join()).isNull();
        verify(delegateRepo).findLatestRevision(INIT, "/**");
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.findLatestRevision(INIT, "/**").join()).isNull();
        verify(delegateRepo, never()).findLatestRevision(any(), any());
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    public void finaLatestRevisionHead() {
        final Repository repo = newCachingRepo();
        final Revision actualHeadRev = new Revision(2);
        doReturn(new RevisionRange(actualHeadRev, actualHeadRev))
                .when(delegateRepo).normalizeNow(HEAD, HEAD);

        assertThat(repo.findLatestRevision(HEAD, "/**").join()).isNull();
        verify(delegateRepo, never()).findLatestRevision(any(), any());
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    public void watchFastPath() {
        final Repository repo = setMockNames(newCachingRepo());
        doReturn(new RevisionRange(INIT, new Revision(2))).when(delegateRepo).normalizeNow(INIT, HEAD);

        // Uncached
        when(delegateRepo.findLatestRevision(any(), any())).thenReturn(completedFuture(new Revision(2)));
        assertThat(repo.watch(INIT, "/**").join()).isEqualTo(new Revision(2));
        verify(delegateRepo).findLatestRevision(INIT, "/**");
        verify(delegateRepo, never()).watch(any(), any(String.class));
        verifyNoMoreInteractions(delegateRepo);

        // Cached
        clearInvocations(delegateRepo);
        assertThat(repo.watch(INIT, "/**").join()).isEqualTo(new Revision(2));
        verify(delegateRepo, never()).findLatestRevision(any(), any());
        verify(delegateRepo, never()).watch(any(), any(String.class));
        verifyNoMoreInteractions(delegateRepo);
    }

    @Test
    public void watchSlowPath() {
        final Repository repo = setMockNames(newCachingRepo());
        doReturn(new RevisionRange(INIT, new Revision(2))).when(delegateRepo).normalizeNow(INIT, HEAD);

        final CompletableFuture<Revision> delegateWatchFuture = new CompletableFuture<>();
        when(delegateRepo.findLatestRevision(any(), any())).thenReturn(completedFuture(null));
        when(delegateRepo.watch(any(), any(String.class))).thenReturn(delegateWatchFuture);

        // Make sure the future returned by CachingRepository.watch() depends on
        // the future returned by delegateRepo.watch().
        final CompletableFuture<Revision> watchFuture = repo.watch(INIT, "/**");
        assertThat(watchFuture).isNotDone();
        delegateWatchFuture.complete(new Revision(3));
        assertThat(watchFuture.join()).isSameAs(delegateWatchFuture.join());

        verify(delegateRepo).findLatestRevision(INIT, "/**");
        verify(delegateRepo).watch(INIT, "/**");
        verifyNoMoreInteractions(delegateRepo);
    }

    private Repository newCachingRepo() {
        when(delegateRepo.history(INIT, INIT, Repository.ALL_PATH, 1)).thenReturn(completedFuture(
                ImmutableList.of(new Commit(INIT, SYSTEM, "", "", Markup.PLAINTEXT))));

        final Repository cachingRepo = new CachingRepository(delegateRepo,
                                                             new RepositoryCache("maximumSize=1000"));

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
