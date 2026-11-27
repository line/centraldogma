/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.centraldogma.client.armeria;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.MergeSource;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class CentralDogmaRepositoryTest {

    @RegisterExtension
    static final ServerExtension oldServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/api/v1/projects/foo/repos/bar/commits/1", (ctx, req) ->
                    HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                    "[{\"revision\":1," +
                                    "\"author\":{\"name\":\"Alice\",\"email\":\"alice@example.com\"}," +
                                    "\"commitMessage\":{\"summary\":\"summary\",\"detail\":\"detail\"," +
                                    "\"markup\":\"MARKDOWN\"}," +
                                    "\"pushedAt\":\"2026-09-16T00:00:00Z\"}]")
            );
        }
    };

    @RegisterExtension
    static final CentralDogmaExtension centralDogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar")
                  .join()
                  .commit("commit2", ImmutableList.of(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }")))
                  .push()
                  .join();

            client.forRepo("foo", "bar")
                  .commit("commit3", ImmutableList.of(Change.ofJsonUpsert("/bar.json", "{ \"a\": \"c\" }")))
                  .push()
                  .join();
        }
    };

    @Test
    void files() throws JsonParseException {
        final CentralDogmaRepository centralDogmaRepo = centralDogma.client().forRepo("foo", "bar");
        assertThat(centralDogmaRepo.normalize(Revision.HEAD).join()).isEqualTo(new Revision(3));

        final Entry<JsonNode> fooJson = Entry.ofJson(new Revision(3), "/foo.json", "{ \"a\": \"b\" }");
        assertThat(centralDogmaRepo.file("/foo.json")
                                   .get()
                                   .join())
                .isEqualTo(fooJson);

        assertThat(centralDogmaRepo.file(Query.ofJson("/foo.json"))
                                   .get()
                                   .join())
                .isEqualTo(fooJson);

        final Entry<JsonNode> barJson = Entry.ofJson(new Revision(3), "/bar.json", "{ \"a\": \"c\" }");
        assertThat(centralDogmaRepo.file(PathPattern.all())
                                   .get()
                                   .join())
                .containsOnly(Maps.immutableEntry("/foo.json", fooJson),
                              Maps.immutableEntry("/bar.json", barJson));

        final MergedEntry<?> merged = centralDogmaRepo.merge(MergeSource.ofRequired("/foo.json"),
                                                             MergeSource.ofRequired("/bar.json"))
                                                      .get().join();
        assertThat(merged.paths()).containsExactly("/foo.json", "/bar.json");
        assertThat(merged.revision()).isEqualTo(new Revision(3));
        assertThatJson(merged.content()).isEqualTo("{ \"a\": \"c\" }");
    }

    @Test
    void historyAndDiff() {
        final CentralDogmaRepository centralDogmaRepo = centralDogma.client().forRepo("foo", "bar");
        final List<Commit> commits = centralDogmaRepo.history().get(new Revision(2), Revision.HEAD).join();
        assertThat(commits.stream()
                          .map(Commit::summary)
                          .collect(toImmutableList())).containsExactly("commit3", "commit2");
        assertThat(commits).allSatisfy(commit -> assertThat(commit.upstreamCommitId()).isNull());
        assertThat(centralDogmaRepo.diff("/foo.json")
                                   .get(Revision.INIT, Revision.HEAD)
                                   .join())
                .isEqualTo(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }"));

        assertThat(centralDogmaRepo.diff(Query.ofJson("/foo.json"))
                                   .get(Revision.INIT, Revision.HEAD)
                                   .join())
                .isEqualTo(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }"));

        assertThat(centralDogmaRepo.diff(PathPattern.all())
                                   .get(Revision.INIT, Revision.HEAD)
                                   .join())
                .containsExactlyInAnyOrder(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }"),
                                           Change.ofJsonUpsert("/bar.json", "{ \"a\": \"c\" }"));

        assertThat(centralDogmaRepo.diff(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"d\" }"))
                                   .get()
                                   .join())
                .containsExactly(Change.ofJsonPatch(
                        "/foo.json",
                        "[{\"op\":\"safeReplace\",\"path\":\"/a\",\"oldValue\":\"b\",\"value\":\"d\"}]"));
    }

    @Test
    void readsHistoryFromOldServerWithoutUpstreamCommitIds() throws Exception {
        try (CentralDogma client = new ArmeriaCentralDogmaBuilder()
                .host("127.0.0.1", oldServer.httpPort())
                .healthCheckIntervalMillis(0)
                .maxNumRetriesOnReplicationLag(0)
                .build()) {
            final List<Commit> commits = client.forRepo("foo", "bar")
                                               .history()
                                               .get(Revision.INIT, Revision.INIT)
                                               .join();

            assertThat(commits).singleElement().satisfies(commit -> {
                assertThat(commit.revision()).isEqualTo(Revision.INIT);
                assertThat(commit.author().name()).isEqualTo("Alice");
                assertThat(commit.author().email()).isEqualTo("alice@example.com");
                assertThat(commit.when()).isEqualTo(
                        Instant.parse("2026-09-16T00:00:00Z").toEpochMilli());
                assertThat(commit.summary()).isEqualTo("summary");
                assertThat(commit.detail()).isEqualTo("detail");
                assertThat(commit.markup()).isEqualTo(Markup.MARKDOWN);
                assertThat(commit.upstreamCommitId()).isNull();
            });
        }
    }
}
