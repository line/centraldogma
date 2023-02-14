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

package com.linecorp.centraldogma.server.internal.api;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.InvalidPushException;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ContentServiceV1Test {

    static final String CONTENTS_PREFIX = "/api/v1/projects/myPro/repos/myRepo/contents";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            // Shorten the default request timeout here, in order to do the following tests
            // that a watch request overrides the default request timeout:
            // - watchRepositoryTimeout
            // - watchFileTimeout
            builder.requestTimeoutMillis(3_000);
        }

        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        }
    };

    @BeforeAll
    static void setUp() {
        createProject(dogma);
    }

    @Test
    void watchRepositoryTimeout() {
        final WebClient client = dogma.httpClient();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, CONTENTS_PREFIX,
                                                         HttpHeaderNames.IF_NONE_MATCH, "-1",
                                                         HttpHeaderNames.PREFER, "wait=5"); // 5 seconds
        final CompletableFuture<AggregatedHttpResponse> future = client.execute(headers).aggregate();
        await().between(4, TimeUnit.SECONDS, 6, TimeUnit.SECONDS).until(future::isDone);
        assertThat(ResponseHeaders.of(future.join().headers()).status()).isSameAs(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void watchFileTimeout() {
        final WebClient client = dogma.httpClient();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, CONTENTS_PREFIX + "/foo.json",
                                                         HttpHeaderNames.IF_NONE_MATCH, "-1",
                                                         HttpHeaderNames.PREFER, "wait=5"); // 5 seconds
        final CompletableFuture<AggregatedHttpResponse> future = client.execute(headers).aggregate();
        await().between(4, TimeUnit.SECONDS, 6, TimeUnit.SECONDS).until(future::isDone);
        assertThat(ResponseHeaders.of(future.join().headers()).status()).isSameAs(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void emptyChangeSet() {
        final WebClient client = dogma.httpClient();
        // Add /foo.json and then remove it, which is essentially a no-op.
        final String body = '{' +
                            "  \"commitMessage\": {" +
                            "    \"summary\": \"do nothing\"," +
                            "    \"detail\": \"\"," +
                            "    \"markup\": \"PLAINTEXT\"" +
                            "  }," +
                            "  \"changes\": [" +
                            "    {" +
                            "      \"path\": \"/foo.json\"," +
                            "      \"type\": \"UPSERT_JSON\"," +
                            "      \"content\": {}" +
                            "    }," +
                            "    {" +
                            "      \"path\": \"/foo.json\"," +
                            "      \"type\": \"REMOVE\"" +
                            "    }" +
                            "  ]" +
                            '}';
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final AggregatedHttpResponse res = client.execute(headers, body).aggregate().join();
        assertThat(ResponseHeaders.of(res.headers()).status()).isEqualTo(HttpStatus.CONFLICT);
        assertThatJson(res.contentUtf8()).isEqualTo(
                '{' +
                "  \"exception\": \"" + RedundantChangeException.class.getName() + "\"," +
                "  \"message\": \"${json-unit.ignore}\"" +
                '}');
    }

    @Test
    void pushFileToMetaRepositoryShouldFail() {
        final WebClient client = dogma.httpClient();

        final String body =
                '{' +
                "   \"path\" : \"/foo.json\"," +
                "   \"type\" : \"UPSERT_JSON\"," +
                "   \"content\" : {\"a\": \"bar\"}," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Add foo.json\"," +
                "       \"detail\": \"Add because we need it.\"," +
                "       \"markup\": \"PLAINTEXT\"" +
                "   }" +
                '}';
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/api/v1/projects/myPro/repos/meta/contents",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final AggregatedHttpResponse res = client.execute(headers, body).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.contentUtf8()).contains(InvalidPushException.class.getName());
    }

    @Test
    void pushInvalidJson() {
        final WebClient client = dogma.httpClient();

        // An invalid JSON containing a trailing comma.
        final String body =
                '{' +
                "   \"path\" : \"/invalid.json\"," +
                "   \"type\" : \"UPSERT_JSON\"," +
                "   \"content\" : {\"trailing\": \"comma\", }," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Add invalid.json\"," +
                "       \"detail\": \"An invalid JSON must be rejected.\"," +
                "       \"markup\": \"PLAINTEXT\"" +
                "   }" +
                '}';
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/api/v1/projects/myPro/repos/myRepo/contents",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final AggregatedHttpResponse res = client.execute(headers, body).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.contentUtf8()).contains(JsonParseException.class.getName());
    }

    @Nested
    class FilesTest {

        @RegisterExtension
        final CentralDogmaExtension dogma = new CentralDogmaExtension() {
            @Override
            protected void configureHttpClient(WebClientBuilder builder) {
                builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
            }

            @Override
            protected boolean runForEachTest() {
                return true;
            }
        };

        @BeforeEach
        void setUp() {
            createProject(dogma);
        }

        @Test
        void addFile() {
            final WebClient client = dogma.httpClient();
            final AggregatedHttpResponse aRes = addFooJson(client);
            final String expectedJson =
                    '{' +
                    "   \"revision\": 2," +
                    "   \"pushedAt\": \"${json-unit.ignore}\"" +
                    '}';
            final String actualJson = aRes.contentUtf8();
            assertThatJson(actualJson).isEqualTo(expectedJson);
        }

        @Test
        void renameFile() {
            final WebClient client = dogma.httpClient();
            addFooJson(client);
            final String body =
                    '{' +
                    "   \"path\" : \"/foo.json\"," +
                    "   \"type\" : \"RENAME\"," +
                    "   \"content\" : \"/bar.json\"," +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Rename foo.json\"," +
                    "       \"detail\": \"Rename to bar.json\"," +
                    "       \"markup\": \"PLAINTEXT\"" +
                    "   }" +
                    '}';
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                             HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
            final AggregatedHttpResponse aRes = client.execute(headers, body).aggregate().join();
            final String expectedJson =
                    '{' +
                    "   \"revision\": 3," +
                    "   \"pushedAt\": \"${json-unit.ignore}\"" +
                    '}';
            final String actualJson = aRes.contentUtf8();
            assertThatJson(actualJson).isEqualTo(expectedJson);
        }

        @Test
        void renameDirectory() {
            final WebClient client = dogma.httpClient();
            addBarTxt(client);
            final String body =
                    '{' +
                    "   \"path\" : \"/a\"," +
                    "   \"type\" : \"RENAME\"," +
                    "   \"content\" : \"/b\"," +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Rename /a\"," +
                    "       \"detail\": \"Rename to /b\"," +
                    "       \"markup\": \"PLAINTEXT\"" +
                    "   }" +
                    '}';
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                             HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
            final AggregatedHttpResponse aRes = client.execute(headers, body).aggregate().join();
            final String expectedJson =
                    '{' +
                    "   \"revision\": 3," +
                    "   \"pushedAt\": \"${json-unit.ignore}\"" +
                    '}';
            final String actualJson = aRes.contentUtf8();
            assertThatJson(actualJson).isEqualTo(expectedJson);
        }

        @Test
        void addFiles() {
            final WebClient client = dogma.httpClient();
            final String body =
                    '{' +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Add foo.json\"," +
                    "       \"detail\": \"Add because we need it.\"," +
                    "       \"markup\": \"PLAINTEXT\"" +
                    "   }," +
                    "   \"changes\" : [" +
                    "       {" +
                    "           \"path\" : \"/foo0.json\"," +
                    "           \"type\" : \"UPSERT_JSON\"," +
                    "           \"content\" : {\"a\": \"bar\"}" +
                    "       }," +
                    "       {" +
                    "           \"path\" : \"/foo1.json\"," +
                    "           \"type\" : \"UPSERT_JSON\"," +
                    "           \"content\" : {\"b\": \"bar\"}" +
                    "       }" +
                    "   ]" +
                    '}';
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                             HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
            final AggregatedHttpResponse aRes = client.execute(headers, body).aggregate().join();
            final String expectedJson =
                    '{' +
                    "   \"revision\": 2," +
                    "   \"pushedAt\": \"${json-unit.ignore}\"" +
                    '}';
            final String actualJson = aRes.contentUtf8();
            assertThatJson(actualJson).isEqualTo(expectedJson);
        }

        @Test
        void editFileWithPost() throws IOException {
            final WebClient client = dogma.httpClient();
            addFooJson(client);
            final String editJsonBody =
                    '{' +
                    "   \"path\" : \"/foo.json\"," +
                    "   \"type\" : \"UPSERT_JSON\"," +
                    "   \"content\" : {\"a\": \"baz\"}," +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Edit foo.json\"," +
                    "       \"detail\": \"Edit because we need it.\"," +
                    "       \"markup\": \"PLAINTEXT\"" +
                    "   }" +
                    '}';
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                             HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
            client.execute(headers, editJsonBody).aggregate().join();

            // check whether the change is right
            final AggregatedHttpResponse res1 = client
                    .get("/api/v1/projects/myPro/repos/myRepo/compare?from=2&to=3").aggregate().join();
            final JsonNode content1 = Jackson.readTree(res1.contentUtf8()).get(0).get("content");
            assertThat(content1.size()).isOne();
            assertThat(content1.get(0).toString()).isEqualToIgnoringCase(
                    "{\"op\":\"safeReplace\",\"path\":\"/a\",\"oldValue\":\"bar\",\"value\":\"baz\"}");

            addBarTxt(client);
            final String editTextBody =
                    '{' +
                    "   \"path\" : \"/a/bar.txt\"," +
                    "   \"type\" : \"UPSERT_TEXT\"," +
                    "   \"content\" : \"text in some file.\"," +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Edit bar.txt\"," +
                    "       \"detail\": \"Edit because we need it.\"," +
                    "       \"markup\": \"PLAINTEXT\"" +
                    "   }" +
                    '}';
            client.execute(RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                             HttpHeaderNames.CONTENT_TYPE, MediaType.JSON),
                           editTextBody).aggregate().join();

            // check whether the change is right
            final AggregatedHttpResponse res2 = client
                    .get("/api/v1/projects/myPro/repos/myRepo/compare?from=4&to=5").aggregate().join();
            final JsonNode content2 = Jackson.readTree(res2.contentUtf8()).get(0).get("content");
            assertThat(content2.textValue()).isEqualToIgnoringCase("--- /a/bar.txt\n" +
                                                                   "+++ /a/bar.txt\n" +
                                                                   "@@ -1,1 +1,1 @@\n" +
                                                                   "-text in the file.\n" +
                                                                   "+text in some file.");
        }

        @Test
        void editFiles() {
            final WebClient client = dogma.httpClient();
            addFooJson(client);
            addBarTxt(client);
            final String body =
                    '{' +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Edit files\"," +
                    "       \"detail\": \"Edit because we need it.\"," +
                    "       \"markup\": \"PLAINTEXT\"" +
                    "   }," +
                    "   \"changes\" : [" +
                    "       {" +
                    "           \"path\" : \"/foo.json\"," +
                    "           \"type\" : \"UPSERT_JSON\"," +
                    "           \"content\" : {\"b\": \"bar\"}" +
                    "       }," +
                    "       {" +
                    "           \"path\" : \"/a/bar.txt\"," +
                    "           \"type\" : \"UPSERT_TEXT\"," +
                    "           \"content\" : \"text in a file.\\n\"" +
                    "       }" +
                    "   ]" +
                    '}';
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                             HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
            client.execute(headers, body).aggregate().join();
            final AggregatedHttpResponse aRes = client
                    .get("/api/v1/projects/myPro/repos/myRepo/compare?from=3&to=4").aggregate().join();
            final String expectedJson =
                    '[' +
                    "   {" +
                    "       \"path\": \"/a/bar.txt\"," +
                    "       \"type\": \"APPLY_TEXT_PATCH\"," +
                    "       \"content\": \"--- /a/bar.txt\\n" +
                    "+++ /a/bar.txt\\n" +
                    "@@ -1,1 +1,1 @@\\n" +
                    "-text in the file.\\n" +
                    "+text in a file.\"" +
                    "   }," +
                    "   {" +
                    "       \"path\": \"/foo.json\"," +
                    "       \"type\": \"APPLY_JSON_PATCH\"," +
                    "       \"content\": [{" +
                    "           \"op\": \"remove\"," +
                    "           \"path\": \"/a\"" +
                    "       },{" +
                    "           \"op\": \"add\"," +
                    "           \"path\": \"/b\"," +
                    "           \"value\": \"bar\"" +
                    "       }]" +
                    "   }" +
                    ']';
            final String actualJson = aRes.contentUtf8();
            assertThatJson(actualJson).isEqualTo(expectedJson);
        }

        @Test
        void getFile() {
            final WebClient client = dogma.httpClient();
            addFooJson(client);
            final AggregatedHttpResponse aRes = client.get(CONTENTS_PREFIX + "/foo.json").aggregate().join();

            final String expectedJson =
                    '{' +
                    "   \"revision\": 2," +
                    "   \"path\": \"/foo.json\"," +
                    "   \"type\": \"JSON\"," +
                    "   \"content\" : {\"a\":\"bar\"}," +
                    "   \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/foo.json\"" +
                    '}';
            final String actualJson = aRes.contentUtf8();
            assertThatJson(actualJson).isEqualTo(expectedJson);
        }

        @Test
        void getFileWithJsonPath() {
            final WebClient client = dogma.httpClient();
            addFooJson(client);
            final AggregatedHttpResponse aRes = client
                    .get(CONTENTS_PREFIX + "/foo.json?jsonpath=$[?(@.a == \"bar\")]&jsonpath=$[0].a")
                    .aggregate().join();

            final String expectedJson =
                    '{' +
                    "   \"revision\": 2," +
                    "   \"path\": \"/foo.json\"," +
                    "   \"type\": \"JSON\"," +
                    "   \"content\" : \"bar\"," +
                    "   \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/foo.json\"" +
                    '}';
            final String actualJson = aRes.contentUtf8();
            assertThatJson(actualJson).isEqualTo(expectedJson);
        }

        @Test
        void listFiles() {
            final WebClient client = dogma.httpClient();
            addFooJson(client);
            addBarTxt(client);
            // get the list of all files
            final AggregatedHttpResponse res1 = client
                    .get("/api/v1/projects/myPro/repos/myRepo/list/**").aggregate().join();
            final String expectedJson1 =
                    '[' +
                    "   {" +
                    "       \"revision\": 3," +
                    "       \"path\": \"/a\"," +
                    "       \"type\": \"DIRECTORY\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/a\"" +
                    "   }," +
                    "   {" +
                    "       \"revision\": 3," +
                    "       \"path\": \"/a/bar.txt\"," +
                    "       \"type\": \"TEXT\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/a/bar.txt\"" +
                    "   }," +
                    "   {" +
                    "       \"revision\": 3," +
                    "       \"path\": \"/foo.json\"," +
                    "       \"type\": \"JSON\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/foo.json\"" +
                    "   }" +
                    ']';
            assertThatJson(res1.contentUtf8()).isEqualTo(expectedJson1);

            // get the list of files only under root
            final AggregatedHttpResponse res2 = client
                    .get("/api/v1/projects/myPro/repos/myRepo/list/").aggregate().join();
            final String expectedJson2 =
                    '[' +
                    "   {" +
                    "       \"revision\": 3," +
                    "       \"path\": \"/a\"," +
                    "       \"type\": \"DIRECTORY\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/a\"" +
                    "   }," +
                    "   {" +
                    "       \"revision\": 3," +
                    "       \"path\": \"/foo.json\"," +
                    "       \"type\": \"JSON\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/foo.json\"" +
                    "   }" +
                    ']';
            assertThatJson(res2.contentUtf8()).isEqualTo(expectedJson2);

            // get the list of all files with revision 2, so only foo.json will be fetched
            final AggregatedHttpResponse res3 = client
                    .get("/api/v1/projects/myPro/repos/myRepo/list/**?revision=2").aggregate().join();
            final String expectedJson3 =
                    '[' +
                    "   {" +
                    "       \"revision\": 2," +
                    "       \"path\": \"/foo.json\"," +
                    "       \"type\": \"JSON\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/foo.json\"" +
                    "   }" +
                    ']';
            assertThatJson(res3.contentUtf8()).isEqualTo(expectedJson3);

            // get the list with a file path
            final AggregatedHttpResponse res4 = client
                    .get("/api/v1/projects/myPro/repos/myRepo/list/foo.json").aggregate().join();
            final String expectedJson4 =
                    '[' +
                    "   {" +
                    "       \"revision\": 3," +
                    "       \"path\": \"/foo.json\"," +
                    "       \"type\": \"JSON\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/foo.json\"" +
                    "   }" +
                    ']';
            assertThatJson(res4.contentUtf8()).isEqualTo(expectedJson4);
        }

        @Test
        void listFilesWithContent() {
            final WebClient client = dogma.httpClient();
            addFooJson(client);
            addBarTxt(client);

            // get the list of all files
            final AggregatedHttpResponse res1 = client.get(CONTENTS_PREFIX + "/**").aggregate().join();
            final String expectedJson1 =
                    '[' +
                    "   {" +
                    "       \"revision\": 3," +
                    "       \"path\": \"/a\"," +
                    "       \"type\": \"DIRECTORY\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/a\"" +
                    "   }," +
                    "   {" +
                    "       \"revision\": 3," +
                    "       \"path\": \"/a/bar.txt\"," +
                    "       \"type\": \"TEXT\"," +
                    "       \"content\" : \"text in the file.\\n\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/a/bar.txt\"" +
                    "   }," +
                    "   {" +
                    "       \"revision\": 3," +
                    "       \"path\": \"/foo.json\"," +
                    "       \"type\": \"JSON\"," +
                    "       \"content\" : {\"a\":\"bar\"}," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/foo.json\"" +
                    "   }" +
                    ']';
            assertThatJson(res1.contentUtf8()).isEqualTo(expectedJson1);

            final AggregatedHttpResponse res2 = client.get(CONTENTS_PREFIX + "/**?revision=2")
                                                      .aggregate().join();
            final String expectedJson2 =
                    '[' +
                    "   {" +
                    "       \"revision\": 2," +
                    "       \"path\": \"/foo.json\"," +
                    "       \"type\": \"JSON\"," +
                    "       \"content\" : {\"a\":\"bar\"}," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/foo.json\"" +
                    "   }" +
                    ']';
            assertThatJson(res2.contentUtf8()).isEqualTo(expectedJson2);
        }

        @Test
        void deleteFile() throws IOException {
            final WebClient client = dogma.httpClient();
            addFooJson(client);
            addBarTxt(client);

            final String body =
                    '{' +
                    "   \"path\": \"/foo.json\"," +
                    "   \"type\": \"REMOVE\"," +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Delete foo.json\"" +
                    "   }" +
                    '}';
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                             HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
            final AggregatedHttpResponse res1 = client.execute(headers, body).aggregate().join();
            assertThat(ResponseHeaders.of(res1.headers()).status()).isEqualTo(HttpStatus.OK);

            final AggregatedHttpResponse res2 = client.get(CONTENTS_PREFIX + "/**").aggregate().join();
            // /a directory and /a/bar.txt file are left
            assertThat(Jackson.readTree(res2.contentUtf8()).size()).isEqualTo(2);
        }

        @Test
        void deleteFileInvalidRevision() {
            final WebClient client = dogma.httpClient();
            addFooJson(client);
            addBarTxt(client);
            final String body =
                    '{' +
                    "   \"path\": \"/foo.json\"," +
                    "   \"type\": \"REMOVE\"," +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Delete foo.json\"" +
                    "   }" +
                    '}';
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX + "?revision=2",
                                                             HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
            final AggregatedHttpResponse res = client.execute(headers, body).aggregate().join();
            assertThat(ResponseHeaders.of(res.headers()).status()).isEqualTo(HttpStatus.CONFLICT);
            assertThatJson(res.contentUtf8()).isEqualTo(
                    '{' +
                    "  \"exception\": \"" + ChangeConflictException.class.getName() + "\"," +
                    "  \"message\": \"${json-unit.ignore}\"" +
                    '}');
        }

        @Test
        void editFileWithJsonPatch() throws IOException {
            final WebClient client = dogma.httpClient();
            addFooJson(client);
            final AggregatedHttpResponse res1 = editFooJson(client);
            final String expectedJson =
                    '{' +
                    "   \"revision\": 3," +
                    "   \"pushedAt\": \"${json-unit.ignore}\"" +
                    '}';
            final String actualJson = res1.contentUtf8();
            assertThatJson(actualJson).isEqualTo(expectedJson);

            final AggregatedHttpResponse res2 = client.get(CONTENTS_PREFIX + "/foo.json").aggregate().join();
            assertThat(Jackson.readTree(res2.contentUtf8()).get("content").get("a").textValue())
                    .isEqualToIgnoringCase("baz");
        }

        @Test
        void editFileWithTextPatch() throws IOException {
            final WebClient client = dogma.httpClient();
            addBarTxt(client);
            final String patch =
                    '{' +
                    "   \"path\": \"/a/bar.txt\"," +
                    "   \"type\": \"APPLY_TEXT_PATCH\"," +
                    "   \"content\" : \"--- /a/bar.txt\\n" +
                    "+++ /a/bar.txt\\n" +
                    "@@ -1,1 +1,1 @@\\n" +
                    "-text in the file.\\n" +
                    "+text in some file.\\n\"," +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Edit bar.txt\"," +
                    "       \"detail\": \"Edit because we need it.\"," +
                    "       \"markup\": \"PLAINTEXT\"" +
                    "   }" +
                    '}';

            final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                             HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
            final AggregatedHttpResponse res1 = client.execute(headers, patch).aggregate().join();
            final String expectedJson =
                    '{' +
                    "   \"revision\": 3," +
                    "   \"pushedAt\": \"${json-unit.ignore}\"" +
                    '}';
            final String actualJson = res1.contentUtf8();
            assertThatJson(actualJson).isEqualTo(expectedJson);

            final AggregatedHttpResponse res2 = client.get(CONTENTS_PREFIX + "/a/bar.txt").aggregate().join();
            assertThat(Jackson.readTree(res2.contentUtf8()).get("content").textValue())
                    .isEqualTo("text in some file.\n");
        }

        @Test
        void watchRepository() {
            final WebClient client = dogma.httpClient();
            addFooJson(client);
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, CONTENTS_PREFIX,
                                                             HttpHeaderNames.IF_NONE_MATCH, "-1");
            final CompletableFuture<AggregatedHttpResponse> future = client.execute(headers).aggregate();

            assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                    .isExactlyInstanceOf(TimeoutException.class);

            editFooJson(client);

            await().atMost(3, TimeUnit.SECONDS).untilAsserted(future::isDone);
            final AggregatedHttpResponse res = future.join();

            final String expectedJson =
                    '{' +
                    "   \"revision\" : 3" +
                    '}';
            final String actualJson = res.contentUtf8();
            assertThatJson(actualJson).isEqualTo(expectedJson);
        }

        @Test
        void watchFileWithIdentityQuery() {
            final WebClient client = dogma.httpClient();
            addFooJson(client);
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, CONTENTS_PREFIX + "/foo.json",
                                                             HttpHeaderNames.IF_NONE_MATCH, "-1");
            final CompletableFuture<AggregatedHttpResponse> future = client.execute(headers).aggregate();

            assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                    .isExactlyInstanceOf(TimeoutException.class);

            // An irrelevant change should not trigger a notification.
            addBarTxt(client);
            assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                    .isExactlyInstanceOf(TimeoutException.class);

            // Make a relevant change now.
            editFooJson(client);
            final String expectedJson =
                    '{' +
                    "   \"revision\" : 4," +
                    "   \"entry\": {" +
                    "       \"revision\" : 4," +
                    "       \"path\": \"/foo.json\"," +
                    "       \"type\": \"JSON\"," +
                    "       \"content\": {\"a\":\"baz\"}," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/foo.json\"" +
                    "   }" +
                    '}';
            final AggregatedHttpResponse res = future.join();
            final String actualJson = res.contentUtf8();
            assertThatJson(actualJson).isEqualTo(expectedJson);
        }

        @Test
        void watchFileWithJsonPathQuery() {
            final WebClient client = dogma.httpClient();
            addFooJson(client);
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET,
                                                             CONTENTS_PREFIX + "/foo.json?jsonpath=%24.a",
                                                             HttpHeaderNames.IF_NONE_MATCH, "-1");
            final CompletableFuture<AggregatedHttpResponse> future = client.execute(headers).aggregate();

            assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                    .isExactlyInstanceOf(TimeoutException.class);

            // An irrelevant change should not trigger a notification.
            addBarTxt(client);
            assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                    .isExactlyInstanceOf(TimeoutException.class);

            // Make a relevant change now.
            editFooJson(client);
            final String expectedJson =
                    '{' +
                    "   \"revision\" : 4," +
                    "   \"entry\": {" +
                    "       \"revision\" : 4," +
                    "       \"path\": \"/foo.json\"," +
                    "       \"type\": \"JSON\"," +
                    "       \"content\": \"baz\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/foo.json\"" +
                    "   }" +
                    '}';
            final AggregatedHttpResponse res = future.join();
            final String actualJson = res.contentUtf8();
            assertThatJson(actualJson).isEqualTo(expectedJson);
        }

        @Test
        void listADirectoryWithoutSlash() {
            final WebClient client = dogma.httpClient();
            final String body =
                    '{' +
                    "   \"path\" : \"/a.json/b.json/c.json/d.json\"," +
                    "   \"type\" : \"UPSERT_JSON\"," +
                    "   \"content\" : {\"a\": \"bar\"}," +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Add d.json in weird directory structure\"" +
                    "   }" +
                    '}';
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                             HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
            client.execute(headers, body).aggregate().join();
            final String expectedJson =
                    '[' +
                    "   {" +
                    "       \"revision\": 2," +
                    "       \"path\": \"/a.json/b.json\"," +
                    "       \"type\": \"DIRECTORY\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/contents/a.json/b.json\"" +
                    "   }" +
                    ']';
            // List directory without slash.
            final AggregatedHttpResponse res1 = client
                    .get("/api/v1/projects/myPro/repos/myRepo/list/a.json").aggregate().join();
            assertThatJson(res1.contentUtf8()).isEqualTo(expectedJson);

            // Listing directory with a slash is same with the listing director without slash which is res1.
            final AggregatedHttpResponse res2 = client
                    .get("/api/v1/projects/myPro/repos/myRepo/list/a.json/").aggregate().join();
            assertThatJson(res2.contentUtf8()).isEqualTo(expectedJson);
        }
    }

    static void createProject(CentralDogmaExtension dogma) {
        final WebClient client = dogma.httpClient();

        // the default project used for unit tests
        RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/api/v1/projects",
                                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        String body = "{\"name\": \"myPro\"}";
        client.execute(headers, body).aggregate().join();

        // the default repository used for unit tests
        headers = RequestHeaders.of(HttpMethod.POST, "/api/v1/projects/myPro/repos",
                                    HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        body = "{\"name\": \"myRepo\"}";
        client.execute(headers, body).aggregate().join();
    }

    static AggregatedHttpResponse addFooJson(WebClient client) {
        final String body =
                '{' +
                "   \"path\" : \"/foo.json\"," +
                "   \"type\" : \"UPSERT_JSON\"," +
                "   \"content\" : {\"a\": \"bar\"}," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Add foo.json\"," +
                "       \"detail\": \"Add because we need it.\"," +
                "       \"markup\": \"PLAINTEXT\"" +
                "   }" +
                '}';
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        return client.execute(headers, body).aggregate().join();
    }

    static AggregatedHttpResponse editFooJson(WebClient client) {
        final String body =
                '{' +
                "   \"path\" : \"/foo.json\"," +
                "   \"type\" : \"APPLY_JSON_PATCH\"," +
                "   \"content\" : [{" +
                "       \"op\" : \"safeReplace\"," +
                "       \"path\": \"/a\"," +
                "       \"oldValue\": \"bar\"," +
                "       \"value\": \"baz\"" +
                "   }]," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Edit foo.json\"," +
                "       \"detail\": \"Edit because we need it.\"," +
                "       \"markup\": \"PLAINTEXT\"" +
                "   }" +
                '}';

        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        return client.execute(headers, body).aggregate().join();
    }

    private static AggregatedHttpResponse addBarTxt(WebClient client) {
        final String body =
                '{' +
                "   \"path\" : \"/a/bar.txt\"," +
                "   \"type\" : \"UPSERT_TEXT\"," +
                "   \"content\" : \"text in the file.\\n\"," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Add bar.txt\"," +
                "       \"detail\": \"Add because we need it.\"," +
                "       \"markup\": \"PLAINTEXT\"" +
                "   }" +
                '}';
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        return client.execute(headers, body).aggregate().join();
    }
}
