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
package com.linecorp.centraldogma.it;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergeSource;
import com.linecorp.centraldogma.common.MergedEntry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryExecutionException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MergeFileTest  {

    @RegisterExtension
    final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("myPro").join();
            client.createRepository("myPro", "myRepo").join();
            client.push("myPro", "myRepo", Revision.HEAD, "Initial files",
                        Change.ofJsonUpsert("/foo.json", "{ \"a\": \"bar\" }"),
                        Change.ofJsonUpsert("/foo1.json", "{ \"b\": \"baz\" }"),
                        Change.ofJsonUpsert("/foo2.json", "{ \"a\": \"new_bar\" }")).join();
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void mergeJsonFiles(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final MergedEntry<?> merged = client.mergeFiles("myPro", "myRepo", Revision.HEAD,
                                                        MergeSource.ofRequired("/foo.json"),
                                                        MergeSource.ofRequired("/foo1.json"),
                                                        MergeSource.ofRequired("/foo2.json"),
                                                        MergeSource.ofOptional("/foo3.json")).join();

        assertThat(merged.paths()).containsExactly("/foo.json", "/foo1.json","/foo2.json");
        assertThat(merged.revision()).isEqualTo(new Revision(2));
        assertThatJson(merged.content()).isEqualTo("{ \"a\": \"new_bar\", \"b\": \"baz\" }");

        // Check again to see if the original files are changed.
        assertThatJson(client.getFile("myPro", "myRepo", Revision.HEAD, Query.ofJson("/foo.json"))
                             .join()
                             .content())
                .isEqualTo("{ \"a\": \"bar\" }");
        assertThatJson(client.getFile("myPro", "myRepo", Revision.HEAD, Query.ofJson("/foo1.json"))
                             .join()
                             .content())
                .isEqualTo("{ \"b\": \"baz\" }");
        assertThatJson(client.getFile("myPro", "myRepo", Revision.HEAD, Query.ofJson("/foo2.json"))
                             .join()
                             .content())
                .isEqualTo("{ \"a\": \"new_bar\" }");

        assertThatThrownBy(() -> client.mergeFiles("myPro", "myRepo", Revision.HEAD,
                                                   MergeSource.ofRequired("/foo.json"),
                                                   MergeSource.ofRequired("/foo1.json"),
                                                   MergeSource.ofRequired("/foo2.json"),
                                                   MergeSource.ofRequired("/foo3.json")).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(EntryNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void exceptionWhenOnlyOptionalFilesAndDoNotExist(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        assertThatThrownBy(() -> client.mergeFiles("myPro", "myRepo", Revision.HEAD,
                                                   MergeSource.ofOptional("/non_existent1.json"),
                                                   MergeSource.ofRequired("/non_existent2.json")).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(EntryNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void mismatchedValueWhileMerging(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        client.push("myPro", "myRepo", Revision.HEAD, "Add /foo10.json",
                    Change.ofJsonUpsert("/foo10.json", "{ \"a\": 1 }")).join();

        assertThatThrownBy(() -> client.mergeFiles("myPro", "myRepo", Revision.HEAD,
                                                   MergeSource.ofRequired("/foo.json"),
                                                   MergeSource.ofRequired("/foo1.json"),
                                                   MergeSource.ofRequired("/foo2.json"),
                                                   MergeSource.ofRequired("/foo10.json")).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(QueryExecutionException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void mergeJsonPaths(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final MergeQuery<JsonNode> query = MergeQuery.ofJsonPath(
                ImmutableList.of(MergeSource.ofRequired("/foo.json"),
                                 MergeSource.ofRequired("/foo1.json"),
                                 MergeSource.ofRequired("/foo2.json")),
                "$[?(@.b == \"baz\")]",
                "$[0].b");

        final MergedEntry<?> merged = client.mergeFiles("myPro", "myRepo", Revision.HEAD, query).join();

        assertThat(merged.paths()).containsExactly("/foo.json", "/foo1.json","/foo2.json");
        assertThat(merged.revision()).isEqualTo(new Revision(2));
        assertThatJson(merged.content()).isStringEqualTo("baz");

        final MergeQuery<JsonNode> badQuery = MergeQuery.ofJsonPath(
                ImmutableList.of(MergeSource.ofRequired("/foo.json"),
                                 MergeSource.ofRequired("/foo1.json"),
                                 MergeSource.ofRequired("/foo2.json")),
                "$.c");

        assertThatThrownBy(() -> client.mergeFiles("myPro", "myRepo", Revision.HEAD, badQuery).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(QueryExecutionException.class);
    }
}
