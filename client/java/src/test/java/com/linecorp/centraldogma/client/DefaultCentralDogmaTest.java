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
package com.linecorp.centraldogma.client;

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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.centraldogma.client.ThriftTypes.TAuthor;
import com.linecorp.centraldogma.client.ThriftTypes.TChange;
import com.linecorp.centraldogma.client.ThriftTypes.TCommit;
import com.linecorp.centraldogma.client.ThriftTypes.TEntry;
import com.linecorp.centraldogma.client.ThriftTypes.TEntryType;
import com.linecorp.centraldogma.client.ThriftTypes.TMarkup;
import com.linecorp.centraldogma.client.ThriftTypes.TRevision;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService;
import com.linecorp.centraldogma.internal.thrift.ChangeType;
import com.linecorp.centraldogma.internal.thrift.Comment;
import com.linecorp.centraldogma.internal.thrift.DiffFileResult;
import com.linecorp.centraldogma.internal.thrift.GetFileResult;
import com.linecorp.centraldogma.internal.thrift.Project;
import com.linecorp.centraldogma.internal.thrift.Repository;
import com.linecorp.centraldogma.internal.thrift.WatchFileResult;
import com.linecorp.centraldogma.internal.thrift.WatchRepositoryResult;

public class DefaultCentralDogmaTest {
    private static final String TIMESTAMP = "2016-01-02T03:04:05Z";

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    private CentralDogmaService.AsyncIface iface;

    private CentralDogma client;

    @Before
    public void setup() {
        client = new DefaultCentralDogma(ClientFactory.DEFAULT, iface);
    }

    @Test
    public void createProject() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(1);
            callback.onComplete(null);
            return null;
        }).when(iface).createProject(any(), any());
        assertThat(client.createProject("project").get()).isNull();
        verify(iface).createProject(eq("project"), any());
    }

    @Test
    public void removeProject() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(1);
            callback.onComplete(null);
            return null;
        }).when(iface).removeProject(any(), any());
        assertThat(client.removeProject("project").get()).isNull();
        verify(iface).removeProject(eq("project"), any());
    }

    @Test
    public void unremoveProject() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(1);
            callback.onComplete(null);
            return null;
        }).when(iface).unremoveProject(any(), any());
        assertThat(client.unremoveProject("project").get()).isNull();
        verify(iface).unremoveProject(eq("project"), any());
    }

    @Test
    public void listProjects() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<List<Project>> callback = invocation.getArgument(0);
            callback.onComplete(ImmutableList.of(new Project("project")));
            return null;
        }).when(iface).listProjects(any());
        assertThat(client.listProjects().get()).isEqualTo(ImmutableSet.of("project"));
        verify(iface).listProjects(any());
    }

    @Test
    public void listRemovedProjects() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Set<String>> callback = invocation.getArgument(0);
            callback.onComplete(ImmutableSet.of("project"));
            return null;
        }).when(iface).listRemovedProjects(any());
        assertThat(client.listRemovedProjects().get()).isEqualTo(ImmutableSet.of("project"));
        verify(iface).listRemovedProjects(any());
    }

    @Test
    public void createRepository() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(2);
            callback.onComplete(null);
            return null;
        }).when(iface).createRepository(anyString(), anyString(), any());
        assertThat(client.createRepository("project", "repo").get()).isNull();
        verify(iface).createRepository(eq("project"), eq("repo"), any());
    }

    @Test
    public void removeRepository() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(2);
            callback.onComplete(null);
            return null;
        }).when(iface).removeRepository(anyString(), anyString(), any());
        assertThat(client.removeRepository("project", "repo").get()).isNull();
        verify(iface).removeRepository(eq("project"), eq("repo"), any());
    }

    @Test
    public void unremoveRepository() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Void> callback = invocation.getArgument(2);
            callback.onComplete(null);
            return null;
        }).when(iface).unremoveRepository(anyString(), anyString(), any());
        assertThat(client.unremoveRepository("project", "repo").get()).isNull();
        verify(iface).unremoveRepository(eq("project"), eq("repo"), any());
    }

    @Test
    public void listRepositories() throws Exception {
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
                "repo",
                new RepositoryInfo(
                        "repo",
                        new Commit(new Revision(42),
                                   new Author("hitchhiker", "arthur@dent.com"),
                                   Instant.parse("1978-03-08T00:00:00Z").toEpochMilli(),
                                   "The primary phrase", "", Markup.PLAINTEXT))));
        verify(iface).listRepositories(eq("project"), any());
    }

    @Test
    public void listRemovedRepositories() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<Set<String>> callback = invocation.getArgument(1);
            callback.onComplete(ImmutableSet.of("repo"));
            return null;
        }).when(iface).listRemovedRepositories(any(), any());
        assertThat(client.listRemovedRepositories("project").get()).isEqualTo(ImmutableSet.of("repo"));
        verify(iface).listRemovedRepositories(anyString(), any());
    }

    @Test
    public void normalizeRevision() throws Exception {
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
    public void listFiles() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<List<TEntry>> callback = invocation.getArgument(4);
            final TEntry entry = new TEntry("/a.txt", TEntryType.TEXT);
            entry.setContent("hello");
            callback.onComplete(ImmutableList.of(entry));
            return null;
        }).when(iface).listFiles(anyString(), anyString(), any(), anyString(), any());
        assertThat(client.listFiles("project", "repo", new Revision(1), "/a.txt").get())
                .isEqualTo(ImmutableMap.of("/a.txt", EntryType.TEXT));
        verify(iface).listFiles(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    public void getFiles() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<List<TEntry>> callback = invocation.getArgument(4);
            final TEntry entry = new TEntry("/b.txt", TEntryType.TEXT);
            entry.setContent("world");
            callback.onComplete(ImmutableList.of(entry));
            return null;
        }).when(iface).getFiles(anyString(), anyString(), any(), anyString(), any());
        assertThat(client.getFiles("project", "repo", new Revision(1), "path").get())
                .isEqualTo(ImmutableMap.of("/b.txt", Entry.ofText("/b.txt", "world")));
        verify(iface).getFiles(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    public void getHistory() throws Exception {
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
        assertThat(client.getHistory("project", "repo", new Revision(1), new Revision(3), "path").get())
                .isEqualTo(ImmutableList.of(new CommitAndChanges<>(
                        new Commit(new Revision(1),
                                   new Author("name", "name@sample.com"),
                                   Instant.parse(TIMESTAMP).toEpochMilli(),
                                   "summary", "detail", Markup.PLAINTEXT),
                        ImmutableList.of(Change.ofTextUpsert("/a.txt", "content")))));
        verify(iface).getHistory(eq("project"), eq("repo"), any(), any(), eq("path"), any());
    }

    @Test
    public void getDiffs() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<List<TChange>> callback = invocation.getArgument(5);
            final TChange change = new TChange("/a.txt", ChangeType.UPSERT_TEXT);
            change.setContent("content");
            callback.onComplete(ImmutableList.of(change));
            return null;
        }).when(iface).getDiffs(any(), any(), any(), any(), any(), any());
        assertThat(client.getDiffs("project", "repo", new Revision(1), new Revision(3), "path").get())
                .isEqualTo(ImmutableList.of(Change.ofTextUpsert("/a.txt", "content")));
        verify(iface).getDiffs(eq("project"), eq("repo"), any(), any(), eq("path"), any());
    }

    @Test
    public void getPreviewDiffs() throws Exception {
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
    public void push() throws Exception {
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
        ).get()).isEqualTo(new Commit(
                new Revision(1),
                new Author("name", "name@sample.com"),
                Instant.parse(TIMESTAMP).toEpochMilli(),
                "summary", "detail", Markup.PLAINTEXT
        ));
        verify(iface).push(eq("project"), eq("repo"), any(), any(), eq("summary"),
                           any(), any(), any());
    }

    @Test
    public void getFile() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<GetFileResult> callback = invocation.getArgument(4);
            callback.onComplete(new GetFileResult(TEntryType.TEXT, "content"));
            return null;
        }).when(iface).getFile(any(), any(), any(), any(), any());
        assertThat(client.getFile("project", "repo", new Revision(1), Query.identity("/a.txt")).get())
                .isEqualTo(Entry.ofText("/a.txt", "content"));
        verify(iface).getFile(eq("project"), eq("repo"), any(), any(), any());
    }

    @Test
    public void getFile_path() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<GetFileResult> callback = invocation.getArgument(4);
            callback.onComplete(new GetFileResult(TEntryType.TEXT, "content"));
            return null;
        }).when(iface).getFile(any(), any(), any(), any(), any());
        assertThat(client.getFile("project", "repo", new Revision(1), "/a.txt").get())
                .isEqualTo(Entry.ofText("/a.txt", "content"));
        verify(iface).getFile(eq("project"), eq("repo"), any(), any(), any());
    }

    @Test
    public void diffFile() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<DiffFileResult> callback = invocation.getArgument(5);
            callback.onComplete(new DiffFileResult(ChangeType.UPSERT_TEXT, "some_text"));
            return null;
        }).when(iface).diffFile(any(), any(), any(), any(), any(), any());
        assertThat(client.getDiff("project", "repo", new Revision(1), new Revision(3),
                                  Query.identity("/a.txt")).get())
                .isEqualTo(Change.ofTextUpsert("/a.txt", "some_text"));
        verify(iface).diffFile(eq("project"), eq("repo"), any(), any(), any(), any());
    }

    @Test
    public void watchRepository() throws Exception {
        doAnswer(invocation -> {
            final AsyncMethodCallback<WatchRepositoryResult> callback = invocation.getArgument(5);
            callback.onComplete(new WatchRepositoryResult().setRevision(new TRevision(42)));
            return null;
        }).when(iface).watchRepository(any(), any(), any(), anyString(), anyLong(), any());
        assertThat(client.watchRepository("project", "repo", new Revision(1), "/a.txt", 100).get())
                .isEqualTo(new Revision(42));
        verify(iface).watchRepository(eq("project"), eq("repo"), any(), eq("/a.txt"), eq(100L), any());
    }

    @Test
    public void watchRepositoryTimedOut() throws Exception {
        doAnswer(invocation -> {
            AsyncMethodCallback<WatchRepositoryResult> callback = invocation.getArgument(5);
            callback.onComplete(new WatchRepositoryResult());
            return null;
        }).when(iface).watchRepository(any(), any(), any(), anyString(), anyLong(), any());
        assertThat(client.watchRepository("project", "repo", new Revision(1), "/a.txt", 100).get())
                .isNull();
        verify(iface).watchRepository(eq("project"), eq("repo"), any(), eq("/a.txt"), eq(100L), any());
    }

    @Test
    public void watchFile() throws Exception {
        doAnswer(invocation -> {
            AsyncMethodCallback<WatchFileResult> callback = invocation.getArgument(5);
            callback.onComplete(new WatchFileResult().setRevision(new TRevision(42))
                                                     .setType(TEntryType.TEXT)
                                                     .setContent("foo"));
            return null;
        }).when(iface).watchFile(any(), any(), any(), any(), anyLong(), any());
        assertThat(client.watchFile("project", "repo", new Revision(1), Query.identity("/a.txt"), 100).get())
                .isEqualTo(new QueryResult<>(new Revision(42), EntryType.TEXT, "foo"));
        verify(iface).watchFile(eq("project"), eq("repo"), any(), any(), eq(100L), any());
    }

    @Test
    public void watchFileTimedOut() throws Exception {
        doAnswer(invocation -> {
            AsyncMethodCallback<WatchFileResult> callback = invocation.getArgument(5);
            callback.onComplete(new WatchFileResult());
            return null;
        }).when(iface).watchFile(any(), any(), any(), any(), anyLong(), any());
        assertThat(client.watchFile("project", "repo", new Revision(1),
                                    Query.identity("/a.txt"), 100).get()).isNull();
        verify(iface).watchFile(eq("project"), eq("repo"), any(), any(), eq(100L), any());
    }
}
