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

import java.net.InetSocketAddress;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.testing.junit4.CentralDogmaRule;

public class ListCommitsAndDiffTest {

    // TODO(minwoox) replace this unit test using nested structure in junit 5
    // Rule is used instead of ClassRule because the listCommits is affected by other unit tests.
    @Rule
    public final CentralDogmaRule dogma = new CentralDogmaRule();

    private static WebClient webClient;

    @Before
    public void init() {
        final InetSocketAddress serverAddress = dogma.dogma().activePort().get().localAddress();
        final String serverUri = "http://127.0.0.1:" + serverAddress.getPort();
        webClient = WebClient.builder(serverUri)
                             .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                             .build();

        // the default project used for unit tests
        RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/api/v1/projects",
                                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        String body = "{\"name\": \"myPro\"}";
        webClient.execute(headers, body).aggregate().join();

        // the default repository used for unit tests
        headers = RequestHeaders.of(HttpMethod.POST, "/api/v1/projects/myPro/repos",
                                    HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        body = "{\"name\": \"myRepo\"}";
        webClient.execute(headers, body).aggregate().join();
        // default files used for unit tests
        addFooFile();
    }

    private static void addFooFile() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST,
                                                         "/api/v1/projects/myPro/repos/myRepo/contents",
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        for (int i = 0; i < 2; i++) {
            final String body =
                    '{' +
                    "   \"path\": \"/foo" + i + ".json\"," +
                    "   \"type\": \"UPSERT_JSON\"," +
                    "   \"content\" : {\"a\": \"bar" + i + "\"}," +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Add foo" + i + ".json\"," +
                    "       \"detail\": \"Add because we need it.\"," +
                    "       \"markup\": \"PLAINTEXT\"" +
                    "   }" +
                    '}';
            webClient.execute(headers, body).aggregate().join();
        }
    }

    @Test
    public void listCommits() {
        final AggregatedHttpResponse aRes = webClient.get("/api/v1/projects/myPro/repos/myRepo/commits")
                                                     .aggregate().join();
        final String expectedJson =
                '[' +
                "   {" +
                "       \"revision\": 3," +
                "       \"author\": {" +
                "           \"name\": \"${json-unit.ignore}\"," +
                "           \"email\": \"${json-unit.ignore}\"" +
                "       }," +
                "       \"pushedAt\": \"${json-unit.ignore}\"," +
                "       \"commitMessage\" : {" +
                "           \"summary\" : \"Add foo1.json\"," +
                "           \"detail\": \"Add because we need it.\"," +
                "           \"markup\": \"PLAINTEXT\"" +
                "       }" +
                "   }," +
                "   {" +
                "       \"revision\": 2," +
                "       \"author\": {" +
                "           \"name\": \"${json-unit.ignore}\"," +
                "           \"email\": \"${json-unit.ignore}\"" +
                "       }," +
                "       \"pushedAt\": \"${json-unit.ignore}\"," +
                "       \"commitMessage\" : {" +
                "           \"summary\" : \"Add foo0.json\"," +
                "           \"detail\": \"Add because we need it.\"," +
                "           \"markup\": \"PLAINTEXT\"" +
                "       }" +
                "   }," +
                "   {" +
                "       \"revision\": 1," +
                "       \"author\": {" +
                "           \"name\": \"admin\"," +
                "           \"email\": \"admin@localhost.localdomain\"" +
                "       }," +
                "       \"pushedAt\": \"${json-unit.ignore}\"," +
                "       \"commitMessage\" : {" +
                "           \"summary\" : \"Create a new repository\"," +
                "           \"detail\": \"\"," +
                "           \"markup\": \"PLAINTEXT\"" +
                "       }" +
                "   }" +
                ']';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void listCommitsWithmaxCommits() {
        final AggregatedHttpResponse aRes =
                webClient.get("/api/v1/projects/myPro/repos/myRepo/commits/-1?to=1&maxCommits=2")
                         .aggregate().join();
        final String expectedJson =
                '[' +
                "   {" +
                "       \"revision\": 3," +
                "       \"author\": {" +
                "           \"name\": \"${json-unit.ignore}\"," +
                "           \"email\": \"${json-unit.ignore}\"" +
                "       }," +
                "       \"pushedAt\": \"${json-unit.ignore}\"," +
                "       \"commitMessage\" : {" +
                "           \"summary\" : \"Add foo1.json\"," +
                "           \"detail\": \"Add because we need it.\"," +
                "           \"markup\": \"PLAINTEXT\"" +
                "       }" +
                "   }," +
                "   {" +
                "       \"revision\": 2," +
                "       \"author\": {" +
                "           \"name\": \"${json-unit.ignore}\"," +
                "           \"email\": \"${json-unit.ignore}\"" +
                "       }," +
                "       \"pushedAt\": \"${json-unit.ignore}\"," +
                "       \"commitMessage\" : {" +
                "           \"summary\" : \"Add foo0.json\"," +
                "           \"detail\": \"Add because we need it.\"," +
                "           \"markup\": \"PLAINTEXT\"" +
                "       }" +
                "   }" +
                ']';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void getOneCommit() {
        final AggregatedHttpResponse aRes = webClient.get("/api/v1/projects/myPro/repos/myRepo/commits/2")
                                                     .aggregate().join();
        final String expectedJson =
                '{' +
                "   \"revision\": 2," +
                "   \"author\": {" +
                "       \"name\": \"${json-unit.ignore}\"," +
                "       \"email\": \"${json-unit.ignore}\"" +
                "   }," +
                "   \"pushedAt\": \"${json-unit.ignore}\"," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Add foo0.json\"," +
                "       \"detail\": \"Add because we need it.\"," +
                "       \"markup\": \"PLAINTEXT\"" +
                "   }" +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void getCommitWithPath() {
        final AggregatedHttpResponse aRes = webClient
                .get("/api/v1/projects/myPro/repos/myRepo/commits?path=/foo0.json").aggregate().join();
        final String expectedJson =
                '[' +
                "   {" +
                "       \"revision\": 2," +
                "       \"author\": {" +
                "           \"name\": \"${json-unit.ignore}\"," +
                "           \"email\": \"${json-unit.ignore}\"" +
                "       }," +
                "       \"pushedAt\": \"${json-unit.ignore}\"," +
                "       \"commitMessage\" : {" +
                "           \"summary\" : \"Add foo0.json\"," +
                "           \"detail\": \"Add because we need it.\"," +
                "           \"markup\": \"PLAINTEXT\"" +
                "       }" +
                "   }" +
                ']';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void listCommitsWithRevision() {
        final AggregatedHttpResponse res1 = webClient.get("/api/v1/projects/myPro/repos/myRepo/commits?to=2")
                                                     .aggregate().join();
        final String expectedJson =
                '[' +
                "   {" +
                "       \"revision\": 3," +
                "       \"author\": {" +
                "           \"name\": \"${json-unit.ignore}\"," +
                "           \"email\": \"${json-unit.ignore}\"" +
                "       }," +
                "       \"pushedAt\": \"${json-unit.ignore}\"," +
                "       \"commitMessage\" : {" +
                "           \"summary\" : \"Add foo1.json\"," +
                "           \"detail\": \"Add because we need it.\"," +
                "           \"markup\": \"PLAINTEXT\"" +
                "       }" +
                "   }," +
                "   {" +
                "       \"revision\": 2," +
                "       \"author\": {" +
                "           \"name\": \"${json-unit.ignore}\"," +
                "           \"email\": \"${json-unit.ignore}\"" +
                "       }," +
                "       \"pushedAt\": \"${json-unit.ignore}\"," +
                "       \"commitMessage\" : {" +
                "           \"summary\" : \"Add foo0.json\"," +
                "           \"detail\": \"Add because we need it.\"," +
                "           \"markup\": \"PLAINTEXT\"" +
                "       }" +
                "   }" +
                ']';
        assertThatJson(res1.contentUtf8()).isEqualTo(expectedJson);
        final AggregatedHttpResponse res2 = webClient.get("/api/v1/projects/myPro/repos/myRepo/commits/3?to=2")
                                                     .aggregate().join();
        assertThatJson(res2.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void getDiff() {
        editFooFile();
        final AggregatedHttpResponse aRes = webClient
                .get("/api/v1/projects/myPro/repos/myRepo/compare?from=3&to=5").aggregate().join();
        final String expectedJson =
                '[' +
                "   {" +
                "       \"path\": \"/foo0.json\"," +
                "       \"type\": \"APPLY_JSON_PATCH\"," +
                "       \"content\": [{" +
                "           \"op\": \"safeReplace\"," +
                "           \"path\": \"/a\"," +
                "           \"oldValue\": \"bar0\"," +
                "           \"value\": \"baz0\"" +
                "       }]" +
                "   }," +
                "   {" +
                "       \"path\": \"/foo1.json\"," +
                "       \"type\": \"APPLY_JSON_PATCH\"," +
                "       \"content\": [{" +
                "           \"op\": \"safeReplace\"," +
                "           \"path\": \"/a\"," +
                "           \"oldValue\": \"bar1\"," +
                "           \"value\": \"baz1\"" +
                "       }]" +
                "   }" +
                ']';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void getJsonDiff() {
        editFooFile();
        final AggregatedHttpResponse aRes = webClient
                .get("/api/v1/projects/myPro/repos/myRepo/compare?path=/foo0.json&jsonpath=$.a&from=3&to=4")
                .aggregate().join();

        final String expectedJson =
                '{' +
                "   \"path\": \"/foo0.json\"," +
                "   \"type\": \"APPLY_JSON_PATCH\"," +
                "   \"content\": [{" +
                "       \"op\": \"safeReplace\"," +
                "       \"path\": \"\"," +
                "       \"oldValue\": \"bar0\"," +
                "       \"value\": \"baz0\"" +
                "   }]" +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    private static void editFooFile() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST,
                                                         "/api/v1/projects/myPro/repos/myRepo/contents",
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        for (int i = 0; i < 2; i++) {
            final String body =
                    '{' +
                    "   \"path\": \"/foo" + i + ".json\"," +
                    "   \"type\": \"UPSERT_JSON\"," +
                    "   \"content\" : {\"a\": \"baz" + i + "\"}," +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Edit foo" + i + ".json\"," +
                    "       \"detail\": \"Edit because we need it.\"," +
                    "       \"markup\": \"PLAINTEXT\"" +
                    "   }" +
                    '}';
            webClient.execute(headers, body).aggregate().join();
        }
    }
}
