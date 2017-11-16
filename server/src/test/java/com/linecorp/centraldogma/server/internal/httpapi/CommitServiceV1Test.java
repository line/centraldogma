/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.server.internal.httpapi;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import java.net.InetSocketAddress;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class CommitServiceV1Test {

    // TODO(minwoox) replace this unit test using nested structure in junit 5
    // Rule is used instead of ClassRule because the listCommits is affected by other unit tests.
    @Rule
    public final CentralDogmaRule dogma = new CentralDogmaRule();

    private static final String COMMITS_PREFIX = "/api/v1/projects/myPro/repos/myRepo/commits";

    private static HttpClient httpClient;

    @Before
    public void init() {
        final InetSocketAddress serverAddress = dogma.dogma().activePort().get().localAddress();
        final String serverUri = "http://" + serverAddress.getHostString() + ':' + serverAddress.getPort();
        httpClient = new HttpClientBuilder(serverUri)
                .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "bearer anonymous").build();

        // the default project used for unit tests
        String body = "{\"name\": \"myPro\"}";
        httpClient.post("/api/v1/projects", body).aggregate().join();

        // the default repository used for unit tests
        body = "{\"name\": \"myRepo\"}";
        httpClient.post("/api/v1/projects/myPro/repos", body).aggregate().join();
        // default files used for unit tests
        addFooFile();
    }

    private static void addFooFile() {
        for (int i = 0; i < 2; i++) {
            final String body =
                    '{' +
                    "   \"path\": \"/foo" + i + ".json\"," +
                    "   \"content\" : {\"a\": \"bar" + i + "\"}," +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Add foo" + i + ".json\"," +
                    "       \"detail\": \"Add because we need it.\"," +
                    "       \"markup\": \"PLAINTEXT\"" +
                    "   }" +
                    '}';
            httpClient.post("/api/v1/projects/myPro/repos/myRepo/contents", body).aggregate()
                      .join();
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
                "           \"name\": \"User\"," +
                "           \"email\": \"user@localhost.localdomain\"" +
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
                "           \"name\": \"User\"," +
                "           \"email\": \"user@localhost.localdomain\"" +
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
                "       \"name\": \"User\"," +
                "       \"email\": \"user@localhost.localdomain\"" +
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
                '{' +
                "   \"revision\": 2," +
                "   \"author\": {" +
                "       \"name\": \"User\"," +
                "       \"email\": \"user@localhost.localdomain\"" +
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
    public void listCommitsWithRevision() {
        final AggregatedHttpMessage res1 = httpClient.get("/api/v1/projects/myPro/repos/myRepo/commits?to=2")
                                                     .aggregate().join();
        final String expectedJson =
                '[' +
                "   {" +
                "       \"revision\": 3," +
                "       \"author\": {" +
                "           \"name\": \"User\"," +
                "           \"email\": \"user@localhost.localdomain\"" +
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
                "           \"name\": \"User\"," +
                "           \"email\": \"user@localhost.localdomain\"" +
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
                .get("/api/v1/projects/myPro/repos/myRepo/compare?" +
                     "path=/foo0.json&queryType=JSON_PATH&expression=$.a&from=3&to=4").aggregate().join();
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
        for (int i = 0; i < 2; i++) {
            final String body =
                    '{' +
                    "   \"path\": \"/foo" + i + ".json\"," +
                    "   \"content\" : {\"a\": \"baz" + i + "\"}," +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Edit foo" + i + ".json\"," +
                    "       \"detail\": \"Edit because we need it.\"," +
                    "       \"markup\": \"PLAINTEXT\"" +
                    "   }" +
                    '}';
            httpClient.post("/api/v1/projects/myPro/repos/myRepo/contents", body).aggregate()
                      .join();
        }
    }
}
