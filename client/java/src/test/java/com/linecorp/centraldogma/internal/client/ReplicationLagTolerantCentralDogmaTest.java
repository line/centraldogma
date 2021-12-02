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
package com.linecorp.centraldogma.internal.client;

import static com.spotify.futures.CompletableFutures.exceptionallyCompletedFuture;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.RepositoryInfo;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;

class ReplicationLagTolerantCentralDogmaTest {

    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private static final Supplier<?> currentReplicaHintSupplier = () -> "?";

    @Mock
    private CentralDogma delegate;

    private ReplicationLagTolerantCentralDogma dogma;

    @BeforeEach
    void setUp() {
        dogma = new ReplicationLagTolerantCentralDogma(executor, delegate, 3, 0,
                                                       currentReplicaHintSupplier);
    }

    @AfterAll
    static void shutdownExecutor() {
        executor.shutdown();
    }

    @Test
    void normalizeRevision() {
        // Make sure the latest known revision is remembered on `normalizeRevision()`.
        final Revision latestRevision = new Revision(2);
        for (int i = 1; i <= latestRevision.major(); i++) {
            final Revision revision = new Revision(i);
            when(delegate.normalizeRevision(any(), any(), any())).thenReturn(completedFuture(revision));

            assertThat(dogma.normalizeRevision("foo", "bar", Revision.HEAD).join()).isEqualTo(revision);

            verify(delegate, times(1)).normalizeRevision("foo", "bar", Revision.HEAD);
            verifyNoMoreInteractions(delegate);
            reset(delegate);

            assertThat(dogma.latestKnownRevision("foo", "bar")).isEqualTo(revision);
        }

        // Make sure:
        // 1) The client retries when older revision is found.
        // 2) The latest known revision remains unchanged.
        //// Return an old revision once and then the correct revision to simulate a replication lag.
        when(delegate.normalizeRevision(any(), any(), any())).thenReturn(
                // Raise an error on the 1st attempt.
                exceptionallyCompletedFuture(new RevisionNotFoundException()),
                // Return an old revision on the 2nd attempt.
                completedFuture(latestRevision.backward(1)),
                // Return the correct revision finally.
                completedFuture(latestRevision));
        //// The client should not return the old revision but the correct revision.
        assertThat(dogma.normalizeRevision("foo", "bar", Revision.HEAD).join()).isEqualTo(latestRevision);
        //// 3 attempts must be made, but no more.
        verify(delegate, times(3)).normalizeRevision("foo", "bar", Revision.HEAD);
        verifyNoMoreInteractions(delegate);
        reset(delegate);

        assertThat(dogma.latestKnownRevision("foo", "bar")).isEqualTo(latestRevision);

        // Make sure the absolute revisions are normalized as well.
        final Revision newLatestRevision = latestRevision.forward(1);
        when(delegate.normalizeRevision(any(), any(), any())).thenReturn(completedFuture(newLatestRevision));
        assertThat(dogma.normalizeRevision("foo", "bar", newLatestRevision).join())
                .isEqualTo(newLatestRevision);
        verify(delegate, times(1)).normalizeRevision("foo", "bar", newLatestRevision);
        verifyNoMoreInteractions(delegate);
        reset(delegate);
        assertThat(dogma.latestKnownRevision("foo", "bar")).isEqualTo(newLatestRevision);

        // Make sure that the client does not retry indefinitely.
        //// Keep returning an old revision.
        when(delegate.normalizeRevision(any(), any(), any()))
                .thenReturn(completedFuture(Revision.INIT));
        //// Give up and return the old revision.
        assertThat(dogma.normalizeRevision("foo", "bar", Revision.HEAD).join()).isEqualTo(Revision.INIT);
        verify(delegate, times(4)).normalizeRevision("foo", "bar", Revision.HEAD);
        verifyNoMoreInteractions(delegate);
        reset(delegate);
        //// Keep failing with an error.
        when(delegate.normalizeRevision(any(), any(), any()))
                .thenReturn(exceptionallyCompletedFuture(new RevisionNotFoundException()));
        //// Give up and raise an exception.
        assertThatThrownBy(() -> dogma.normalizeRevision("foo", "bar", Revision.HEAD).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RevisionNotFoundException.class);
        verify(delegate, times(4)).normalizeRevision("foo", "bar", Revision.HEAD);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void normalizeRevisionAndExecuteWithRetries() throws Exception {
        final Revision latestRevision = new Revision(3);
        when(delegate.normalizeRevision(any(), any(), any())).thenReturn(completedFuture(latestRevision));
        when(delegate.getFile(any(), any(), any(), any(Query.class))).thenReturn(
                exceptionallyCompletedFuture(new RevisionNotFoundException()),
                exceptionallyCompletedFuture(new RevisionNotFoundException()),
                completedFuture(Entry.ofJson(latestRevision, "/foo.json", "{ \"a\": \"b\" }")));

        assertThat(dogma.getFile("foo", "bar", Revision.HEAD, Query.ofJson("/foo.json")).join())
                .isEqualTo(Entry.ofJson(latestRevision, "/foo.json", "{ \"a\": \"b\" }"));

        verify(delegate, times(1)).normalizeRevision("foo", "bar", Revision.HEAD);
        verify(delegate, times(3)).getFile("foo", "bar", latestRevision, Query.ofJson("/foo.json"));
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void normalizeRevisionsAndExecuteWithRetriesFastPath() {
        when(delegate.normalizeRevision(any(), any(), eq(Revision.HEAD)))
                .thenReturn(completedFuture(new Revision(2)));
        when(delegate.getDiff(any(), any(), any(), any(), any(Query.class))).thenReturn(
                exceptionallyCompletedFuture(new RevisionNotFoundException()),
                exceptionallyCompletedFuture(new RevisionNotFoundException()),
                completedFuture(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }")));

        assertThat(dogma.getDiff("foo", "bar", new Revision(-2), new Revision(-1),
                                 Query.ofJson("/foo.json")).join())
                .isEqualTo(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }"));

        verify(delegate, times(1)).normalizeRevision("foo", "bar", Revision.HEAD);
        verify(delegate, times(3)).getDiff("foo", "bar", Revision.INIT, new Revision(2),
                                           Query.ofJson("/foo.json"));
        verifyNoMoreInteractions(delegate);

        // Test again with swapped revisions.
        assertThat(dogma.getDiff("foo", "bar", new Revision(-1), new Revision(-2),
                                 Query.ofJson("/foo.json")).join())
                .isEqualTo(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }"));

        verify(delegate, times(2)).normalizeRevision("foo", "bar", Revision.HEAD);
        verify(delegate, times(1)).getDiff("foo", "bar", new Revision(2), Revision.INIT,
                                           Query.ofJson("/foo.json"));
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void normalizeRevisionsAndExecuteWithRetriesSlowPath() {
        when(delegate.normalizeRevision(any(), any(), eq(Revision.INIT)))
                .thenReturn(completedFuture(Revision.INIT));
        when(delegate.normalizeRevision(any(), any(), eq(Revision.HEAD)))
                .thenReturn(completedFuture(new Revision(2)));
        when(delegate.getDiff(any(), any(), any(), any(), any(Query.class))).thenReturn(
                exceptionallyCompletedFuture(new RevisionNotFoundException()),
                exceptionallyCompletedFuture(new RevisionNotFoundException()),
                completedFuture(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }")));

        assertThat(dogma.getDiff("foo", "bar", Revision.INIT, Revision.HEAD,
                                 Query.ofJson("/foo.json")).join())
                .isEqualTo(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }"));

        verify(delegate, times(1)).normalizeRevision("foo", "bar", Revision.INIT);
        verify(delegate, times(1)).normalizeRevision("foo", "bar", Revision.HEAD);
        verify(delegate, times(3)).getDiff("foo", "bar", Revision.INIT, new Revision(2),
                                           Query.ofJson("/foo.json"));
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void listRepositories() {
        // `listRepository()` must remember the latest known revisions.
        final Revision latestRevision = new Revision(2);
        for (int i = 1; i <= latestRevision.major(); i++) {
            final Revision revision = new Revision(i);
            when(delegate.listRepositories(any())).thenReturn(completedFuture(
                    ImmutableMap.of("bar", new RepositoryInfo("bar", revision))));
            assertThat(dogma.listRepositories("foo").join()).isEqualTo(
                    ImmutableMap.of("bar", new RepositoryInfo("bar", revision)));
            verify(delegate, times(1)).listRepositories("foo");
            verifyNoMoreInteractions(delegate);
            reset(delegate);
            assertThat(dogma.latestKnownRevision("foo", "bar")).isEqualTo(revision);
        }

        // Make sure:
        // 1) The client retries when older revision is found.
        // 2) The latest known revision remains unchanged.
        //// Return an old revision once and then the correct revision to simulate a replication lag.
        when(delegate.listRepositories(any())).thenReturn(
                // Return an old revision on the 1st attempt.
                completedFuture(ImmutableMap.of(
                        "bar", new RepositoryInfo("bar", latestRevision.backward(1)))),
                // Return the correct revision on the 2nd attempt.
                completedFuture(ImmutableMap.of(
                        "bar", new RepositoryInfo("bar", latestRevision))));
        //// The client should not return the old revision but the correct revision.
        assertThat(dogma.listRepositories("foo").join())
                .isEqualTo(ImmutableMap.of("bar", new RepositoryInfo("bar", latestRevision)));
        //// 3 attempts must be made, but no more.
        verify(delegate, times(2)).listRepositories("foo");
        verifyNoMoreInteractions(delegate);
        reset(delegate);

        assertThat(dogma.latestKnownRevision("foo", "bar")).isEqualTo(latestRevision);

        // Make sure that the client does not retry indefinitely.
        //// Keep returning an old revision.
        when(delegate.listRepositories(any())).thenReturn(completedFuture(
                ImmutableMap.of("bar", new RepositoryInfo("bar", Revision.INIT))));
        //// Give up and return the old revision.
        assertThat(dogma.listRepositories("foo").join()).isEqualTo(
                ImmutableMap.of("bar", new RepositoryInfo("bar", Revision.INIT)));
        verify(delegate, times(4)).listRepositories("foo");
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void retryOnlyOnRevisionNotFoundException() {
        when(delegate.normalizeRevision(any(), any(), any()))
                .thenReturn(exceptionallyCompletedFuture(new ProjectNotFoundException()));

        assertThatThrownBy(() -> dogma.normalizeRevision("foo", "bar", Revision.HEAD).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ProjectNotFoundException.class);

        verify(delegate, times(1)).normalizeRevision("foo", "bar", Revision.HEAD);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void push() {
        final PushResult pushResult = new PushResult(new Revision(3), 42L);
        when(delegate.push(any(), any(), any(), any(), any(), any(), any(Iterable.class)))
                .thenReturn(completedFuture(pushResult));

        assertThat(dogma.push("foo", "bar", Revision.HEAD, "summary", "detail", Markup.MARKDOWN,
                              ImmutableList.of(Change.ofTextUpsert("/a.txt", "a"))).join())
                .isEqualTo(pushResult);

        // On successful push, latest known revision must be remembered.
        assertThat(dogma.latestKnownRevision("foo", "bar")).isEqualTo(pushResult.revision());
    }

    @Test
    void pushWithRetries() {
        // Make the client remember the latest known revision.
        when(delegate.normalizeRevision(any(), any(), any()))
                .thenReturn(completedFuture(new Revision(3)));

        assertThat(dogma.normalizeRevision("foo", "bar", new Revision(-2)).join())
                .isEqualTo(new Revision(3));

        //// Note that the revision -2 has been resolved into the revision 3,
        //// which means the revision -1 is the revision 4.
        assertThat(dogma.latestKnownRevision("foo", "bar")).isEqualTo(new Revision(4));
        reset(delegate);

        // Attempt to push at the base revision 4, which should succeed after retries.
        final PushResult pushResult = new PushResult(new Revision(5), 42L);
        when(delegate.push(any(), any(), any(), any(), any(), any(), any(Iterable.class)))
                .thenReturn(exceptionallyCompletedFuture(new RevisionNotFoundException()),
                            exceptionallyCompletedFuture(new RevisionNotFoundException()),
                            completedFuture(pushResult));

        assertThat(dogma.push("foo", "bar", new Revision(4), "summary", "detail", Markup.MARKDOWN,
                              ImmutableList.of(Change.ofTextUpsert("/a.txt", "a"))).join())
                .isEqualTo(pushResult);

        // On successful push, latest known revision must be remembered.
        assertThat(dogma.latestKnownRevision("foo", "bar")).isEqualTo(pushResult.revision());

        verify(delegate, times(3)).push("foo", "bar", new Revision(4), "summary", "detail", Markup.MARKDOWN,
                                        ImmutableList.of(Change.ofTextUpsert("/a.txt", "a")));
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void watchRepository() {
        // Make sure `watchRepository()` remembers the latest revision.
        final Revision latestRevision = new Revision(3);
        when(delegate.normalizeRevision(any(), any(), any()))
                .thenReturn(completedFuture(Revision.INIT));
        when(delegate.watchRepository(any(), any(), any(), any(), anyLong()))
                .thenReturn(completedFuture(latestRevision));

        assertThat(dogma.watchRepository("foo", "bar", Revision.INIT, "/**", 10000L).join())
                .isEqualTo(latestRevision);

        assertThat(dogma.latestKnownRevision("foo", "bar")).isEqualTo(latestRevision);

        verify(delegate, times(1)).normalizeRevision("foo", "bar", Revision.INIT);
        verify(delegate, times(1)).watchRepository("foo", "bar", Revision.INIT, "/**", 10000L);
        verifyNoMoreInteractions(delegate);
        reset(delegate);

        // Make sure `getFile()` at HEAD revision does not fetch an outdated entry.
        when(delegate.normalizeRevision(any(), any(), any()))
                .thenReturn(completedFuture(latestRevision));
        when(delegate.getFile(any(), any(), any(), any(Query.class)))
                .thenReturn(exceptionallyCompletedFuture(new RevisionNotFoundException()),
                            completedFuture(Entry.ofText(latestRevision, "/a.txt", "a")));

        assertThat(dogma.getFile("foo", "bar", Revision.HEAD, Query.ofText("/a.txt")).join())
                .isEqualTo(Entry.ofText(latestRevision, "/a.txt", "a"));

        verify(delegate, times(1)).normalizeRevision("foo", "bar", Revision.HEAD);
        verify(delegate, times(2)).getFile("foo", "bar", latestRevision, Query.ofText("/a.txt"));
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void watchFile() {
        // Make sure `watchFile()` remembers the latest revision.
        final Revision latestRevision = new Revision(3);
        final Entry<String> latestEntry = Entry.ofText(latestRevision, "/a.txt", "a");
        when(delegate.normalizeRevision(any(), any(), any()))
                .thenReturn(completedFuture(Revision.INIT));
        when(delegate.watchFile(any(), any(), any(), (Query<String>) any(), anyLong()))
                .thenReturn(completedFuture(latestEntry));

        assertThat(dogma.watchFile("foo", "bar", Revision.INIT, Query.ofText("/a.txt"), 10000L).join())
                .isEqualTo(latestEntry);

        assertThat(dogma.latestKnownRevision("foo", "bar")).isEqualTo(latestRevision);

        verify(delegate, times(1)).normalizeRevision("foo", "bar", Revision.INIT);
        verify(delegate, times(1)).watchFile("foo", "bar", Revision.INIT, Query.ofText("/a.txt"), 10000L);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void getFile() {
        final Revision latestRevision = new Revision(3);
        final Entry<String> latestEntry = Entry.ofText(latestRevision, "/a.txt", "a");
        when(delegate.normalizeRevision(any(), any(), any()))
                .thenReturn(completedFuture(latestRevision));
        when(delegate.getFile(any(), any(), any(), any(Query.class)))
                .thenAnswer(invocation -> CompletableFuture.supplyAsync(() -> {
                    throw new RevisionNotFoundException();
                }))
                .thenReturn(completedFuture(latestEntry));
        assertThat(dogma.forRepo("foo", "bar").file("/a.txt").get(Revision.HEAD).join())
                .isEqualTo(latestEntry);

        verify(delegate).normalizeRevision("foo", "bar", Revision.HEAD);
        verify(delegate, times(2)).getFile("foo", "bar",
                                           latestRevision, Query.ofText("/a.txt"));
        verifyNoMoreInteractions(delegate);
    }
}
