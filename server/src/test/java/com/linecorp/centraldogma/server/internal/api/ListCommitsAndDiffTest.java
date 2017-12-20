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

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class ListCommitsAndDiffTest {

    // TODO(minwoox) replace this unit test using nested structure in junit 5
    // Rule is used instead of ClassRule because the listCommits is affected by other unit tests.
    @Rule
    public final CentralDogmaRule dogma = new CentralDogmaRule();

    private static HttpClient httpClient;

    @Before
    public void init() {
        final InetSocketAddress serverAddress = dogma.dogma().activePort().get().localAddress();
        final String serverUri = "http://" + serverAddress.getHostString() + ':' + serverAddress.getPort();
        httpClient = new HttpClientBuilder(serverUri)
                .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "bearer anonymous").build();

        // the default project used for unit tests
        HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, "/api/v1/projects").contentType(MediaType.JSON);
        String body = "{\"name\": \"myPro\"}";
        httpClient.execute(headers, body).aggregate().join();

        // the default repository used for unit tests
        headers = HttpHeaders.of(HttpMethod.POST, "/api/v1/projects/myPro/repos").contentType(MediaType.JSON);
        body = "{\"name\": \"myRepo\"}";
        httpClient.execute(headers, body).aggregate().join();
        // default files used for unit tests
        addFooFile();
    }

    private static void addFooFile() {
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST,
                                                   "/api/v1/projects/myPro/repos/myRepo/contents")
                                               .contentType(MediaType.JSON);
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
            httpClient.execute(headers, body).aggregate().join();
        }
    }

    @Test
    public void listCommits() {
        final AggregatedHttpMessage aRes = httpClient.get("/api/v1/projects/myPro/repos/myRepo/commits")
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
                "           \"name\": \"System\"," +
                "           \"email\": \"system@localhost.localdomain\"" +
                "       }," +
                "       \"pushedAt\": \"${json-unit.ignore}\"," +
                "       \"commitMessage\" : {" +
                "           \"summary\" : \"Create a new repository\"," +
                "           \"detail\": \"\"," +
                "           \"markup\": \"PLAINTEXT\"" +
                "       }" +
                "   }" +
                ']';
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void getOneCommit() {
        final AggregatedHttpMessage aRes = httpClient.get("/api/v1/projects/myPro/repos/myRepo/commits/2")
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
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void getCommitWithPath() {
        final AggregatedHttpMessage aRes = httpClient
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
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void listCommitsWithRevision() {
        final AggregatedHttpMessage res1 = httpClient.get("/api/v1/projects/myPro/repos/myRepo/commits?to=2")
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
        final String actualJson1 = res1.content().toStringUtf8();
        assertThatJson(actualJson1).isEqualTo(expectedJson);
        final AggregatedHttpMessage res2 = httpClient.get("/api/v1/projects/myPro/repos/myRepo/commits/3?to=2")
                                                     .aggregate().join();
        final String actualJson2 = res2.content().toStringUtf8();
        assertThatJson(actualJson2).isEqualTo(expectedJson);
    }

    @Test
    public void getDiff() {
        editFooFile();
        final AggregatedHttpMessage aRes = httpClient
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
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void getJsonDiff() {
        editFooFile();
        final AggregatedHttpMessage aRes = httpClient
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
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    private static void editFooFile() {
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST,
                                                   "/api/v1/projects/myPro/repos/myRepo/contents")
                                               .contentType(MediaType.JSON);
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
            httpClient.execute(headers, body).aggregate().join();
        }
    }
}
