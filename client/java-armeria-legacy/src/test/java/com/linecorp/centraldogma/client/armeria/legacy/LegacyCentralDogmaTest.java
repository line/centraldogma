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
package com.linecorp.centraldogma.client.armeria.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.RepositoryInfo;
import com.linecorp.centraldogma.client.armeria.legacy.ThriftTypes.TAuthor;
import com.linecorp.centraldogma.client.armeria.legacy.ThriftTypes.TChange;
import com.linecorp.centraldogma.client.armeria.legacy.ThriftTypes.TCommit;
import com.linecorp.centraldogma.client.armeria.legacy.ThriftTypes.TEntry;
import com.linecorp.centraldogma.client.armeria.legacy.ThriftTypes.TEntryType;
import com.linecorp.centraldogma.client.armeria.legacy.ThriftTypes.TMarkup;
import com.linecorp.centraldogma.client.armeria.legacy.ThriftTypes.TRevision;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergeSource;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService;
import com.linecorp.centraldogma.internal.thrift.ChangeType;
import com.linecorp.centraldogma.internal.thrift.Comment;
import com.linecorp.centraldogma.internal.thrift.DiffFileResult;
import com.linecorp.centraldogma.internal.thrift.GetFileResult;
import com.linecorp.centraldogma.internal.thrift.MergedEntry;
import com.linecorp.centraldogma.internal.thrift.Project;
import com.linecorp.centraldogma.internal.thrift.Repository;
import com.linecorp.centraldogma.internal.thrift.WatchFileResult;
import com.linecorp.centraldogma.internal.thrift.WatchRepositoryResult;

class LegacyCentralDogmaTest {

    private static final String TIMESTAMP = "2016-01-02T03:04:05Z";

    @Mock
    private CentralDogmaService.AsyncIface iface;

    private CentralDogma client;

    @BeforeEach
    void setUp() {
        client = new LegacyCentralDogma(CommonPools.blockingTaskExecutor(), iface);
    }

    @Test
    void createProject() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(1);
            callback.onComplete(null);
            return null;
        }).when(iface).createProject(any(), any());
        assertThat(client.createProject("project").get()).isNull();
        verify(iface).createProject(eq("project"), any());
    }

    @Test
    void removeProject() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(1);
            callback.onComplete(null);
            return null;
        }).when(iface).removeProject(any(), any());
        assertThat(client.removeProject("project").get()).isNull();
        verify(iface).removeProject(eq("project"), any());
    }

    @Test
    void purgeProject() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(1);
            callback.onComplete(null);
            return null;
        }).when(iface).purgeProject(any(), any());
        assertThat(client.purgeProject("project").get()).isNull();
        verify(iface).purgeProject(eq("project"), any());
    }

    @Test
    void unremoveProject() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(1);
            callback.onComplete(null);
            return null;
        }).when(iface).unremoveProject(any(), any());
        assertThat(client.unremoveProject("project").get()).isNull();
        verify(iface).unremoveProject(eq("project"), any());
    }

    @Test
    void listProjects() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<List<Project>> callback = invocation.getArgument(0);
            callback.onComplete(ImmutableList.of(new Project("project")));
            return null;
        }).when(iface).listProjects(any());
        assertThat(client.listProjects().get()).isEqualTo(ImmutableSet.of("project"));
        verify(iface).listProjects(any());
    }

    @Test
    void listRemovedProjects() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Set<String>> callback = invocation.getArgument(0);
            callback.onComplete(ImmutableSet.of("project"));
            return null;
        }).when(iface).listRemovedProjects(any());
        assertThat(client.listRemovedProjects().get()).isEqualTo(ImmutableSet.of("project"));
        verify(iface).listRemovedProjects(any());
    }

    @Test
    void createRepository() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(2);
            callback.onComplete(null);
            return null;
        }).when(iface).createRepository(anyString(), anyString(), any());
        assertThat(client.createRepository("project", "repo").get()).isNull();
        verify(iface).createRepository(eq("project"), eq("repo"), any());
    }

    @Test
    void removeRepository() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(2);
            callback.onComplete(null);
            return null;
        }).when(iface).removeRepository(anyString(), anyString(), any());
        assertThat(client.removeRepository("project", "repo").get()).isNull();
        verify(iface).removeRepository(eq("project"), eq("repo"), any());
    }

    @Test
    void purgeRepository() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(2);
            callback.onComplete(null);
            return null;
        }).when(iface).purgeRepository(anyString(), anyString(), any());
        assertThat(client.purgeRepository("project", "repo").get()).isNull();
        verify(iface).purgeRepository(eq("project"), eq("repo"), any());
    }

    @Test
    void unremoveRepository() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(2);
            callback.onComplete(null);
            return null;
        }).when(iface).unremoveRepository(anyString(), anyString(), any());
        assertThat(client.unremoveRepository("project", "repo").get()).isNull();
        verify(iface).unremoveRepository(eq("project"), eq("repo"), any());
    }

    @Test
    void listRepositories() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<List<Repository>> callback = invocation.getArgument(1);
            final Repository repository = new Repository("repo").setHead(
                    new TCommit(new TRevision(42),
                                new TAuthor("hitchhiker", "arthur@dent.com"),
                                "1978-03-08T00:00:00Z", "The primary phrase",
                                new Comment(""), null));
            callback.onComplete(ImmutableList.of(repository));
            return null;
        }).when(iface).listRepositories(any(), any());
        assertThat(client.listRepositories("project").get()).isEqualTo(ImmutableMap.of(
                "repo", new RepositoryInfo("repo", new Revision(42))));
        verify(iface).listRepositories(eq("project"), any());
    }

    @Test
    void listRemovedRepositories() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Set<String>> callback = invocation.getArgument(1);
            callback.onComplete(ImmutableSet.of("repo"));
            return null;
        }).when(iface).listRemovedRepositories(any(), any());
        assertThat(client.listRemovedRepositories("project").get()).isEqualTo(ImmutableSet.of("repo"));
        verify(iface).listRemovedRepositories(anyString(), any());
    }

    @Test
    void normalizeRevision() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<TRevision> callback = invocation.getArgument(3);
            callback.onComplete(new TRevision(3));
            return null;
        }).when(iface).normalizeRevision(anyString(), anyString(), any(), any());
        assertThat(client.normalizeRevision("project", "repo", new Revision(1)).get())
                .isEqualTo(new Revision(3));
        verify(iface).normalizeRevision(eq("project"), eq("repo"), any(), any());
    }

    @Test
    void listFiles() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<List<TEntry>> callback = invocation.getArgument(4);
            final TEntry entry = new TEntry("/a.txt", TEntryType.TEXT);
            entry.setContent("hello");
            callback.onComplete(ImmutableList.of(entry));
            return null;
        }).when(iface).listFiles(anyString(), anyString(), any(), anyString(), any());
        assertThat(client.listFiles("project", "repo", new Revision(1), PathPattern.of("/a.txt")).get())
                .isEqualTo(ImmutableMap.of("/a.txt", EntryType.TEXT));
        verify(iface).listFiles(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void getFiles() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<List<TEntry>> callback = invocation.getArgument(4);
            final TEntry entry = new TEntry("/b.txt", TEntryType.TEXT);
            entry.setContent("world");
            callback.onComplete(ImmutableList.of(entry));
            return null;
        }).when(iface).getFiles(anyString(), anyString(), any(), anyString(), any());
        assertThat(client.getFiles("project", "repo", new Revision(1), PathPattern.of("path")).get())
                .isEqualTo(ImmutableMap.of("/b.txt", Entry.ofText(new Revision(1), "/b.txt", "world")));
        verify(iface).getFiles(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void getHistory() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<List<TCommit>> callback = invocation.getArgument(5);
            callback.onComplete(ImmutableList.of(new TCommit(
                    new TRevision(1),
                    new TAuthor("name", "name@sample.com"),
                    TIMESTAMP,
                    "summary",
                    new Comment("detail").setMarkup(TMarkup.PLAINTEXT),
                    ImmutableList.of(new TChange("/a.txt", ChangeType.UPSERT_TEXT).setContent("content")))));
            return null;
        }).when(iface).getHistory(any(), any(), any(), any(), any(), any());
        assertThat(client.getHistory("project", "repo", new Revision(1), new Revision(3),
                                     PathPattern.of("path")).get())
                .isEqualTo(ImmutableList.of(new Commit(new Revision(1),
                                                       new Author("name", "name@sample.com"),
                                                       Instant.parse(TIMESTAMP).toEpochMilli(),
                                                       "summary", "detail", Markup.PLAINTEXT)));
        verify(iface).getHistory(eq("project"), eq("repo"), any(), any(), eq("/**/path"), any());
    }

    @Test
    void getDiffs() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<List<TChange>> callback = invocation.getArgument(5);
            final TChange change = new TChange("/a.txt", ChangeType.UPSERT_TEXT);
            change.setContent("content");
            callback.onComplete(ImmutableList.of(change));
            return null;
        }).when(iface).getDiffs(any(), any(), any(), any(), any(), any());
        assertThat(client.getDiff("project", "repo", new Revision(1), new Revision(3), PathPattern.of("path"))
                         .get())
                .isEqualTo(ImmutableList.of(Change.ofTextUpsert("/a.txt", "content")));
        verify(iface).getDiffs(eq("project"), eq("repo"), any(), any(), eq("/**/path"), any());
    }

    @Test
    void getPreviewDiffs() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<List<TChange>> callback = invocation.getArgument(4);
            final TChange change = new TChange("/a.txt", ChangeType.UPSERT_TEXT);
            change.setContent("content");
            callback.onComplete(ImmutableList.of(change));
            return null;
        }).when(iface).getPreviewDiffs(any(), any(), any(), any(), any());
        assertThat(client.getPreviewDiffs("project", "repo", new Revision(1),
                                          ImmutableList.of(Change.ofTextUpsert("/a.txt", "content"))).get())
                .isEqualTo(ImmutableList.of(Change.ofTextUpsert("/a.txt", "content")));
        verify(iface).getPreviewDiffs(eq("project"), eq("repo"), any(), any(), any());
    }

    @Test
    void push() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<TCommit> callback = invocation.getArgument(7);
            callback.onComplete(new TCommit(
                    new TRevision(1),
                    new TAuthor("name", "name@sample.com"),
                    TIMESTAMP,
                    "summary",
                    new Comment("detail"),
                    ImmutableList.of()));
            return null;
        }).when(iface).push(anyString(), anyString(), any(), any(), any(), any(), any(), any());
        assertThat(client.push("project", "repo", new Revision(1),
                               new Author("name", "name@sample.com"),
                               "summary", "detail", Markup.PLAINTEXT,
                               ImmutableList.of(Change.ofTextUpsert("/a.txt", "hello"))
        ).get()).isEqualTo(new PushResult(new Revision(1), Instant.parse(TIMESTAMP).toEpochMilli()));
        verify(iface).push(eq("project"), eq("repo"), any(), any(), eq("summary"),
                           any(), any(), any());
    }

    @Test
    void getFile() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<GetFileResult> callback = invocation.getArgument(4);
            callback.onComplete(new GetFileResult(TEntryType.TEXT, "content"));
            return null;
        }).when(iface).getFile(any(), any(), any(), any(), any());
        assertThat(client.getFile("project", "repo", new Revision(1), Query.ofText("/a.txt")).get())
                .isEqualTo(Entry.ofText(new Revision(1), "/a.txt", "content"));
        verify(iface).getFile(eq("project"), eq("repo"), any(), any(), any());
    }

    @Test
    void getFile_path() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<GetFileResult> callback = invocation.getArgument(4);
            callback.onComplete(new GetFileResult(TEntryType.TEXT, "content"));
            return null;
        }).when(iface).getFile(any(), any(), any(), any(), any());
        assertThat(client.getFile("project", "repo", new Revision(1), Query.ofText("/a.txt")).get())
                .isEqualTo(Entry.ofText(new Revision(1), "/a.txt", "content"));
        verify(iface).getFile(eq("project"), eq("repo"), any(), any(), any());
    }

    @Test
    void mergeFiles() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<MergedEntry> callback = invocation.getArgument(4);
            callback.onComplete(new MergedEntry(new TRevision(1), TEntryType.JSON, "{\"foo\": \"bar\"}",
                                                ImmutableList.of("/a.json", "/b.json")));
            return null;
        }).when(iface).mergeFiles(any(), any(), any(), any(), any());
        assertThat(client.mergeFiles("project", "repo", new Revision(1),
                                     MergeQuery.ofJson(ImmutableList.of(MergeSource.ofOptional("/a.json"),
                                                                        MergeSource.ofRequired("/b.json"))))
                         .get())
                .isEqualTo(com.linecorp.centraldogma.common.MergedEntry.of(
                        new Revision(1), EntryType.JSON, Jackson.readTree("{\"foo\": \"bar\"}"),
                        ImmutableList.of("/a.json", "/b.json")));
        verify(iface).mergeFiles(eq("project"), eq("repo"), any(), any(), any());
    }

    @Test
    void diffFile() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<DiffFileResult> callback = invocation.getArgument(5);
            callback.onComplete(new DiffFileResult(ChangeType.UPSERT_TEXT, "some_text"));
            return null;
        }).when(iface).diffFile(any(), any(), any(), any(), any(), any());
        assertThat(client.getDiff("project", "repo", new Revision(1), new Revision(3),
                                  Query.ofText("/a.txt")).get())
                .isEqualTo(Change.ofTextUpsert("/a.txt", "some_text"));
        verify(iface).diffFile(eq("project"), eq("repo"), any(), any(), any(), any());
    }

    @Test
    void watchRepository() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<WatchRepositoryResult> callback = invocation.getArgument(5);
            callback.onComplete(new WatchRepositoryResult().setRevision(new TRevision(42)));
            return null;
        }).when(iface).watchRepository(any(), any(), any(), anyString(), anyLong(), any());
        assertThat(client.watchRepository("project", "repo", new Revision(1),
                                          PathPattern.of("/a.txt"), 100, false).get())
                .isEqualTo(new Revision(42));
        verify(iface).watchRepository(eq("project"), eq("repo"), any(), eq("/a.txt"), eq(100L), any());
    }

    @Test
    void watchRepositoryTimedOut() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<WatchRepositoryResult> callback = invocation.getArgument(5);
            callback.onComplete(new WatchRepositoryResult());
            return null;
        }).when(iface).watchRepository(any(), any(), any(), anyString(), anyLong(), any());
        assertThat(client.watchRepository("project", "repo", new Revision(1),
                                          PathPattern.of("/a.txt"), 100, false).get())
                .isNull();
        verify(iface).watchRepository(eq("project"), eq("repo"), any(), eq("/a.txt"), eq(100L), any());
    }

    @Test
    void watchFile() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<WatchFileResult> callback = invocation.getArgument(5);
            callback.onComplete(new WatchFileResult().setRevision(new TRevision(42))
                                                     .setType(TEntryType.TEXT)
                                                     .setContent("foo"));
            return null;
        }).when(iface).watchFile(any(), any(), any(), any(), anyLong(), any());
        assertThat(client.watchFile("project", "repo", new Revision(1),
                                    Query.ofText("/a.txt"), 100, false).get())
                .isEqualTo(Entry.ofText(new Revision(42), "/a.txt", "foo"));
        verify(iface).watchFile(eq("project"), eq("repo"), any(), any(), eq(100L), any());
    }

    @Test
    void watchFileTimedOut() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<WatchFileResult> callback = invocation.getArgument(5);
            callback.onComplete(new WatchFileResult());
            return null;
        }).when(iface).watchFile(any(), any(), any(), any(), anyLong(), any());
        assertThat(client.watchFile("project", "repo", new Revision(1),
                                    Query.ofText("/a.txt"), 100, false).get()).isNull();
        verify(iface).watchFile(eq("project"), eq("repo"), any(), any(), eq(100L), any());
    }
}
