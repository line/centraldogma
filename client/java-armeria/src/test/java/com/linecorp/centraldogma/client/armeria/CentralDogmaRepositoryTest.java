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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.MergeSource;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class CentralDogmaRepositoryTest {

    @RegisterExtension
    static final CentralDogmaExtension centralDogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
            client.forRepo("foo", "bar")
                  .commit("commit2", ImmutableList.of(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }")))
                  .push(Revision.HEAD)
                  .join();

            client.forRepo("foo", "bar")
                  .commit("commit3", ImmutableList.of(Change.ofJsonUpsert("/bar.json", "{ \"a\": \"c\" }")))
                  .push(Revision.HEAD)
                  .join();
        }
    };

    @Test
    void files() throws JsonParseException {
        final CentralDogmaRepository preparation = centralDogma.client().forRepo("foo", "bar");
        assertThat(preparation.normalize(Revision.HEAD).join()).isEqualTo(new Revision(3));

        final Entry<JsonNode> fooJson = Entry.ofJson(new Revision(3), "/foo.json", "{ \"a\": \"b\" }");
        assertThat(preparation.file("/foo.json")
                              .get(Revision.HEAD)
                              .join())
                .isEqualTo(fooJson);

        assertThat(preparation.file(Query.ofJson("/foo.json"))
                              .get(Revision.HEAD)
                              .join())
                .isEqualTo(fooJson);

        final Entry<JsonNode> barJson = Entry.ofJson(new Revision(3), "/bar.json", "{ \"a\": \"c\" }");
        assertThat(preparation.file(PathPattern.all())
                              .get(Revision.HEAD)
                              .join())
                .containsOnly(Maps.immutableEntry("/foo.json", fooJson),
                              Maps.immutableEntry("/bar.json", barJson));

        final MergedEntry<?> merged = preparation.merge(MergeSource.ofRequired("/foo.json"),
                                                        MergeSource.ofRequired("/bar.json"))
                                                 .get(Revision.HEAD).join();
        assertThat(merged.paths()).containsExactly("/foo.json", "/bar.json");
        assertThat(merged.revision()).isEqualTo(new Revision(3));
        assertThatJson(merged.content()).isEqualTo("{ \"a\": \"c\" }");
    }

    @Test
    void historyAndDiff() {
        final CentralDogmaRepository preparation = centralDogma.client().forRepo("foo", "bar");
        final List<Commit> commits = preparation.history().get(new Revision(2), Revision.HEAD).join();
        assertThat(commits.stream()
                          .map(Commit::summary)
                          .collect(toImmutableList())).containsExactly("commit3", "commit2");
        assertThat(preparation.diff("/foo.json")
                              .get(Revision.INIT, Revision.HEAD)
                              .join())
                .isEqualTo(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }"));

        assertThat(preparation.diff(Query.ofJson("/foo.json"))
                              .get(Revision.INIT, Revision.HEAD)
                              .join())
                .isEqualTo(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }"));

        assertThat(preparation.diff(PathPattern.all())
                              .get(Revision.INIT, Revision.HEAD)
                              .join())
                .containsExactlyInAnyOrder(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }"),
                                           Change.ofJsonUpsert("/bar.json", "{ \"a\": \"c\" }"));

        assertThat(preparation.diff(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"d\" }"))
                              .get(Revision.HEAD)
                              .join())
                .containsExactly(Change.ofJsonPatch(
                        "/foo.json",
                        "[{\"op\":\"safeReplace\",\"path\":\"/a\",\"oldValue\":\"b\",\"value\":\"d\"}]"));
    }
}
