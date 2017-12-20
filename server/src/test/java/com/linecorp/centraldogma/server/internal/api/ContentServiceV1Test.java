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

package com.linecorp.centraldogma.server.internal.api;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class ContentServiceV1Test {

    // TODO(minwoox) replace this unit test using nested structure in junit 5
    // Rule is used instead of ClassRule because the listFiles is
    // affected by other unit tests.
    @Rule
    public final CentralDogmaRule dogma = new CentralDogmaRule();

    private static final String CONTENTS_PREFIX = "/api/v1/projects/myPro/repos/myRepo/contents";

    private static HttpClient httpClient;

    @Before
    public void init() {
        final InetSocketAddress serverAddress = dogma.dogma().activePort().get().localAddress();
        final String serverUri = "http://" + serverAddress.getHostString() + ':' + serverAddress.getPort();
        httpClient = new HttpClientBuilder(serverUri)
                .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "bearer anonymous").build();

        // the default project used for unit tests
        // the default project used for unit tests
        HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, "/api/v1/projects").contentType(MediaType.JSON);
        String body = "{\"name\": \"myPro\"}";
        httpClient.execute(headers, body).aggregate().join();

        // the default repository used for unit tests
        headers = HttpHeaders.of(HttpMethod.POST, "/api/v1/projects/myPro/repos").contentType(MediaType.JSON);
        body = "{\"name\": \"myRepo\"}";
        httpClient.execute(headers, body).aggregate().join();
        // default files used for unit tests
    }

    @Test
    public void addFile() {
        final AggregatedHttpMessage aRes = addFooJson();
        final String expectedJson =
                '{' +
                "   \"path\": \"/foo.json\"," +
                "   \"type\": \"JSON\"," +
                "   \"revision\": 2," +
                "   \"url\": \"/api/v1/projects/myPro/repos/myRepo/foo.json\"," +
                "   \"modifiedAt\": \"${json-unit.ignore}\"" +
                '}';
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void renameFile() {
        addFooJson();
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
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX)
                                               .contentType(MediaType.JSON);
        final AggregatedHttpMessage aRes = httpClient.execute(headers, body).aggregate().join();
        final String expectedJson =
                '{' +
                "   \"path\": \"/bar.json\"," +
                "   \"type\": \"JSON\"," +
                "   \"revision\": 3," +
                "   \"url\": \"/api/v1/projects/myPro/repos/myRepo/bar.json\"," +
                "   \"modifiedAt\": \"${json-unit.ignore}\"" +
                '}';
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void renameDirectory() {
        addBarTxt();
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
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX)
                                               .contentType(MediaType.JSON);
        final AggregatedHttpMessage aRes = httpClient.execute(headers, body).aggregate().join();
        final String expectedJson =
                '{' +
                "   \"path\": \"/b\"," +
                "   \"type\": \"DIRECTORY\"," +
                "   \"revision\": 3," +
                "   \"url\": \"/api/v1/projects/myPro/repos/myRepo/b\"," +
                "   \"modifiedAt\": \"${json-unit.ignore}\"" +
                '}';
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void addFiles() {
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
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX)
                                               .contentType(MediaType.JSON);
        final AggregatedHttpMessage aRes = httpClient.execute(headers, body).aggregate().join();
        final String expectedJson =
                '[' +
                "   {" +
                "       \"path\": \"/foo0.json\"," +
                "       \"type\": \"JSON\"," +
                "       \"revision\": 2," +
                "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/foo0.json\"," +
                "       \"modifiedAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"path\": \"/foo1.json\"," +
                "       \"type\": \"JSON\"," +
                "       \"revision\": 2," +
                "       \"url\": \"/api/v1/projects/myPro/repos/myRepo/foo1.json\"," +
                "       \"modifiedAt\": \"${json-unit.ignore}\"" +
                "   }" +
                ']';
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void editFileWithPost() throws IOException {
        addFooJson();
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
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX)
                                               .contentType(MediaType.JSON);
        httpClient.execute(headers, editJsonBody).aggregate().join();

        // check whether the change is right
        final AggregatedHttpMessage res1 = httpClient
                .get("/api/v1/projects/myPro/repos/myRepo/compare?from=2&to=3").aggregate().join();
        final JsonNode content1 = Jackson.readTree(res1.content().toStringUtf8()).get(0).get("content");
        assertThat(content1.size()).isOne();
        assertThat(content1.get(0).toString()).isEqualToIgnoringCase(
                "{\"op\":\"safeReplace\",\"path\":\"/a\",\"oldValue\":\"bar\",\"value\":\"baz\"}");

        addBarTxt();
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
        httpClient.execute(HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX).contentType(MediaType.JSON),
                           editTextBody).aggregate().join();

        // check whether the change is right
        final AggregatedHttpMessage res2 = httpClient
                .get("/api/v1/projects/myPro/repos/myRepo/compare?from=4&to=5").aggregate().join();
        final JsonNode content2 = Jackson.readTree(res2.content().toStringUtf8()).get(0).get("content");
        assertThat(content2.textValue()).isEqualToIgnoringCase("--- /a/bar.txt\n" +
                                                               "+++ /a/bar.txt\n" +
                                                               "@@ -1,1 +1,1 @@\n" +
                                                               "-text in the file.\n" +
                                                               "+text in some file.");
    }

    @Test
    public void editFiles() {
        addFooJson();
        addBarTxt();
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
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX)
                                               .contentType(MediaType.JSON);
        httpClient.execute(headers, body).aggregate().join();
        final AggregatedHttpMessage aRes = httpClient
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
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void getFile() {
        addFooJson();
        final AggregatedHttpMessage aRes = httpClient.get(CONTENTS_PREFIX + "/foo.json").aggregate().join();

        final String expectedJson =
                '{' +
                "   \"path\": \"/foo.json\"," +
                "   \"type\": \"JSON\"," +
                "   \"content\" : {\"a\":\"bar\"}" +
                //"   \"modifiedAt\": \"${json-unit.ignore}\"" +
                '}';
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void getFileWithJsonPath() {
        addFooJson();
        final AggregatedHttpMessage aRes = httpClient
                .get(CONTENTS_PREFIX + "/foo.json?jsonpath=$[?(@.a == \"bar\")]&jsonpath=$[0].a")
                .aggregate().join();

        final String expectedJson =
                '{' +
                "   \"path\": \"/foo.json\"," +
                "   \"type\": \"JSON\"," +
                "   \"content\" : \"bar\"" +
                //"   \"modifiedAt\": \"${json-unit.ignore}\"" +
                '}';
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void listFiles() {
        addFooJson();
        addBarTxt();
        // get the list of all files
        final AggregatedHttpMessage res1 = httpClient
                .get("/api/v1/projects/myPro/repos/myRepo/tree/**").aggregate().join();
        final String expectedJson1 =
                '[' +
                "   {" +
                "       \"path\": \"/a/bar.txt\"," +
                "       \"type\": \"TEXT\"" +
                //"   \"modifiedAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"path\": \"/foo.json\"," +
                "       \"type\": \"JSON\"" +
                //"   \"modifiedAt\": \"${json-unit.ignore}\"" +
                "   }" +
                ']';
        final String actualJson1 = res1.content().toStringUtf8();
        assertThatJson(actualJson1).isEqualTo(expectedJson1);

        // get the list of files only under root
        final AggregatedHttpMessage res2 = httpClient
                .get("/api/v1/projects/myPro/repos/myRepo/tree/").aggregate().join();
        final String expectedJson2 =
                '[' +
                "   {" +
                "       \"path\": \"/foo.json\"," +
                "       \"type\": \"JSON\"" +
                //"   \"modifiedAt\": \"${json-unit.ignore}\"" +
                "   }" +
                ']';
        final String actualJson2 = res2.content().toStringUtf8();
        assertThatJson(actualJson2).isEqualTo(expectedJson2);

        // get the list of all files with revision 2, so only foo.json will be fetched
        final AggregatedHttpMessage res3 = httpClient
                .get("/api/v1/projects/myPro/repos/myRepo/tree/**?revision=2").aggregate().join();
        assertThatJson(expectedJson2).isEqualTo(res3.content().toStringUtf8());

        // get the list with a file path
        final AggregatedHttpMessage res4 = httpClient
                .get("/api/v1/projects/myPro/repos/myRepo/tree/foo.json").aggregate().join();
        assertThatJson(expectedJson2).isEqualTo(res4.content().toStringUtf8());
    }

    @Test
    public void listFilesWithContent() {
        addFooJson();
        addBarTxt();

        // get the list of all files
        final AggregatedHttpMessage res1 = httpClient.get(CONTENTS_PREFIX + "/**").aggregate().join();
        final String expectedJson1 =
                '[' +
                "   {" +
                "       \"path\": \"/a/bar.txt\"," +
                "       \"type\": \"TEXT\"," +
                "       \"content\" : \"text in the file.\\n\"" +
                //"   \"modifiedAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"path\": \"/foo.json\"," +
                "       \"type\": \"JSON\"," +
                "       \"content\" : {\"a\":\"bar\"}" +
                //"   \"modifiedAt\": \"${json-unit.ignore}\"" +
                "   }" +
                ']';
        final String actualJson1 = res1.content().toStringUtf8();
        assertThatJson(actualJson1).isEqualTo(expectedJson1);

        final AggregatedHttpMessage res2 = httpClient.get(CONTENTS_PREFIX + "/**?revision=2")
                                                     .aggregate().join();
        final String expectedJson2 =
                '[' +
                "   {" +
                "       \"path\": \"/foo.json\"," +
                "       \"type\": \"JSON\"," +
                "       \"content\" : {\"a\":\"bar\"}" +
                //"   \"modifiedAt\": \"${json-unit.ignore}\"" +
                "   }" +
                ']';
        final String actualJson2 = res2.content().toStringUtf8();
        assertThatJson(actualJson2).isEqualTo(expectedJson2);
    }

    @Test
    public void deleteFile() throws IOException {
        addFooJson();
        addBarTxt();

        final String body =
                '{' +
                "   \"path\": \"/foo.json\"," +
                "   \"type\": \"REMOVE\"," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Delete foo.json\"" +
                "   }" +
                '}';
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX)
                                               .contentType(MediaType.JSON);
        final AggregatedHttpMessage res1 = httpClient.execute(headers, body).aggregate().join();
        assertThat(res1.headers().status()).isEqualTo(HttpStatus.NO_CONTENT);

        final AggregatedHttpMessage res2 = httpClient.get(CONTENTS_PREFIX + "/**").aggregate().join();
        // only /a/bar.txt file is left
        assertThat(Jackson.readTree(res2.content().toStringUtf8()).size()).isOne();
    }

    @Test
    public void deleteFileInvalidRevision() {
        addFooJson();
        addBarTxt();
        final String body =
                '{' +
                "   \"path\": \"/foo.json\"," +
                "   \"type\": \"REMOVE\"," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Delete foo.json\"" +
                "   }" +
                '}';
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX + "?revision=2")
                                               .contentType(MediaType.JSON);
        final AggregatedHttpMessage res = httpClient.execute(headers, body).aggregate().join();
        assertThat(res.headers().status()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    public void editFileWithJsonPatch() throws IOException {
        addFooJson();
        final AggregatedHttpMessage res1 = editFooJson();
        final String expectedJson =
                '{' +
                "   \"path\": \"/foo.json\"," +
                "   \"type\": \"JSON\"," +
                "   \"revision\": 3," +
                "   \"url\": \"/api/v1/projects/myPro/repos/myRepo/foo.json\"," +
                "   \"modifiedAt\": \"${json-unit.ignore}\"" +
                '}';
        final String actualJson = res1.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);

        final AggregatedHttpMessage res2 = httpClient.get(CONTENTS_PREFIX + "/foo.json").aggregate().join();
        assertThat(Jackson.readTree(res2.content().toStringUtf8()).get("content").get("a").textValue())
                .isEqualToIgnoringCase("baz");
    }

    @Test
    public void editFileWithTextPatch() throws IOException {
        addBarTxt();
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

        final HttpHeaders reqHeaders = HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX)
                                                  .contentType(MediaType.JSON);
        final AggregatedHttpMessage res1 = httpClient.execute(reqHeaders, patch).aggregate().join();
        final String expectedJson =
                '{' +
                "   \"path\": \"/a/bar.txt\"," +
                "   \"type\": \"TEXT\"," +
                "   \"revision\": 3," +
                "   \"url\": \"/api/v1/projects/myPro/repos/myRepo/a/bar.txt\"," +
                "   \"modifiedAt\": \"${json-unit.ignore}\"" +
                '}';
        final String actualJson = res1.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);

        final AggregatedHttpMessage res2 = httpClient.get(CONTENTS_PREFIX + "/a/bar.txt").aggregate().join();
        assertThat(Jackson.readTree(res2.content().toStringUtf8()).get("content").textValue())
                .isEqualTo("text in some file.\n");
    }

    @Test
    public void watchRepository() {
        watchTest(CONTENTS_PREFIX);
    }

    @Test
    public void watchFile() {
        watchTest(CONTENTS_PREFIX + "/foo.json");
    }

    private static void watchTest(String path) {
        addFooJson();
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.GET, path).add(
                HttpHeaderNames.IF_NONE_MATCH, "-1");
        final CompletableFuture<AggregatedHttpMessage> future = httpClient.execute(headers).aggregate();

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                .isExactlyInstanceOf(TimeoutException.class);

        editFooJson();

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(future::isDone);
        final AggregatedHttpMessage res = future.join();

        final String expectedJson =
                '{' +
                "   \"head\" : {" +
                "       \"revision\" : 3," +
                "       \"author\" : {" +
                "           \"name\" : \"${json-unit.ignore}\"," +
                "           \"email\" : \"${json-unit.ignore}\"" +
                "       }," +
                "       \"pushedAt\" : \"${json-unit.ignore}\"," +
                "       \"commitMessage\" : {" +
                "           \"summary\" : \"Edit foo.json\"," +
                "           \"detail\" : \"Edit because we need it.\"," +
                "           \"markup\" : \"PLAINTEXT\"" +
                "       }" +
                "   }," +
                "   \"contentsUrl\": \"/api/v1/projects/myPro/repos/myRepo/contents/foo.json\"" +
                '}';
        final String actualJson = res.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void watchRepositoryTimeout() {
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.GET, CONTENTS_PREFIX)
                                               .add(HttpHeaderNames.IF_NONE_MATCH, "-1")
                                               .add(HttpHeaderNames.PREFER, "wait=1"); // 1 second
        final CompletableFuture<AggregatedHttpMessage> future = httpClient.execute(headers).aggregate();
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(future::isDone);
        assertThat(future.join().headers().status()).isSameAs(HttpStatus.NOT_MODIFIED);
    }

    @Test
    public void watchFileTimeout() {
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.GET, CONTENTS_PREFIX + "/foo.json")
                                               .add(HttpHeaderNames.IF_NONE_MATCH, "-1")
                                               .add(HttpHeaderNames.PREFER, "wait=1"); // 1 second
        final CompletableFuture<AggregatedHttpMessage> future = httpClient.execute(headers).aggregate();
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(future::isDone);
        assertThat(future.join().headers().status()).isSameAs(HttpStatus.NOT_MODIFIED);
    }

    @Test
    public void watchFileWithIdentityQuery() throws Exception {
        addFooJson();
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.GET, CONTENTS_PREFIX + "/foo.json").add(
                HttpHeaderNames.IF_NONE_MATCH, "-1");
        final CompletableFuture<AggregatedHttpMessage> future = httpClient.execute(headers).aggregate();

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                .isExactlyInstanceOf(TimeoutException.class);

        // An irrelevant change should not trigger a notification.
        addBarTxt();
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                .isExactlyInstanceOf(TimeoutException.class);

        // Make a relevant change now.
        editFooJson();
        final String expectedJson =
                '{' +
                "   \"head\" : {" +
                "       \"revision\" : 4," +
                "       \"author\" : {" +
                "           \"name\" : \"${json-unit.ignore}\"," +
                "           \"email\" : \"${json-unit.ignore}\"" +
                "       }," +
                "       \"pushedAt\" : \"${json-unit.ignore}\"," +
                "       \"commitMessage\" : {" +
                "           \"summary\" : \"Edit foo.json\"," +
                "           \"detail\" : \"Edit because we need it.\"," +
                "           \"markup\" : \"PLAINTEXT\"" +
                "       }" +
                "   }," +
                "   \"contentsUrl\": \"/api/v1/projects/myPro/repos/myRepo/contents/foo.json\"" +
                '}';
        final AggregatedHttpMessage res = future.join();
        final String actualJson = res.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    private static AggregatedHttpMessage addFooJson() {
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
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX)
                                               .contentType(MediaType.JSON);
        return httpClient.execute(headers, body).aggregate().join();
    }

    private static AggregatedHttpMessage editFooJson() {
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

        final HttpHeaders reqHeaders = HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX)
                                                  .contentType(MediaType.JSON);
        return httpClient.execute(reqHeaders, body).aggregate().join();
    }

    private static AggregatedHttpMessage addBarTxt() {
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
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX)
                                               .contentType(MediaType.JSON);
        return httpClient.execute(headers, body).aggregate().join();
    }
}
