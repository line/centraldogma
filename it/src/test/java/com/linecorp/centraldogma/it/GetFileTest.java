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

import static com.linecorp.centraldogma.testing.internal.ExpectedExceptionAppender.assertThatThrownByWithExpectedException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryExecutionException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Json5;

class GetFileTest {

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding();

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void getJsonAsText(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        client.push(dogma.project(), dogma.repo1(), Revision.HEAD, "Add a file",
                    Change.ofJsonUpsert("/test/foo.json", "{ \"a\": \"b\" }")).join();
        final Entry<JsonNode> json = client.getFile(dogma.project(), dogma.repo1(), Revision.HEAD,
                                                    Query.ofJson("/test/foo.json")).join();
        assertThatJson(json.content()).isEqualTo("{\"a\":\"b\"}");

        final Entry<String> text = client.getFile(dogma.project(), dogma.repo1(), Revision.HEAD,
                                                  Query.ofText("/test/foo.json")).join();
        assertThat(text.content()).isEqualTo("{\"a\":\"b\"}");
        client.push(dogma.project(), dogma.repo1(), Revision.HEAD, "Remove a file",
                    Change.ofRemoval("/test/foo.json")).join();
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void invalidJsonPath(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        assertThatThrownBy(() -> client.getFile(
                dogma.project(), dogma.repo1(), Revision.HEAD,
                Query.ofJsonPath("/test/test2.json", "$.non_exist_path")).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(QueryExecutionException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void invalidFile(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        assertThatThrownByWithExpectedException(EntryNotFoundException.class, "non_existing_file", () ->
                client.getFile(dogma.project(), dogma.repo1(), Revision.HEAD,
                               Query.ofJsonPath("/test/non_existing_file.json", "$.a")).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(EntryNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void invalidRepo(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        assertThatThrownByWithExpectedException(RepositoryNotFoundException.class, "non_exist_repo", () ->
                client.getFile(dogma.project(), "non_exist_repo", Revision.HEAD,
                               Query.ofJsonPath("/test/test2.json", "$.a")).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(RepositoryNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void invalidProject(ClientType clientType) throws Exception {
        final CentralDogma client = clientType.client(dogma);
        assertThatThrownByWithExpectedException(ProjectNotFoundException.class, "non_exist_proj", () ->
                client.getFile("non_exist_proj", dogma.repo1(), Revision.HEAD,
                               Query.ofJsonPath("/test/test2.json", "$.non_exist_path")).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(ProjectNotFoundException.class);
    }

    @Nested
    class GetFileJson5Test {

        final String JSON5_CONTENTS = new String(
                Files.readAllBytes(new File(GetFileJson5Test.class.getClassLoader().getResource(
                        "com/linecorp/centraldogma/it/import/test1.json5").getPath()).toPath()), UTF_8);

        GetFileJson5Test() throws IOException {}

        @Test
        void getJson5() throws JsonParseException {
            final CentralDogma client = dogma.client();

            final Entry<JsonNode> json = client.getFile(dogma.project(), dogma.repo1(), Revision.HEAD,
                                                        Query.ofJson("/test/test1.json5")).join();
            assertThatJson(json.content()).isEqualTo(Json5.readTree(JSON5_CONTENTS));
            assertThat(json.contentAsText()).isEqualTo(JSON5_CONTENTS);
        }

        @Test
        void jsonPath() {
            final CentralDogma client = dogma.client();

            final Entry<JsonNode> json1 = client.getFile(
                    dogma.project(), dogma.repo1(), Revision.HEAD,
                    Query.ofJsonPath("/test/test1.json5", "$.singleQuotes")).join();
            assertThat(json1.content().asText()).isEqualTo("I can use \"double quotes\" here");

            final Entry<JsonNode> json2 = client.getFile(
                    dogma.project(), dogma.repo1(), Revision.HEAD,
                    Query.ofJsonPath("/test/test1.json5", ImmutableList.of("$.andIn", "$.[0]"))).join();
            assertThat(json2.content().asText()).isEqualTo("arrays");
        }
    }
}
