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

import static com.linecorp.centraldogma.server.internal.api.ContentServiceV1Test.CONTENTS_PREFIX;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MergeFileTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.addHttpHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        }
    };

    @BeforeAll
    static void setUp() {
        createProject(dogma);
    }

    @Test
    void mergeJsonFiles() {
        final WebClient client = dogma.httpClient();
        addFilesForMergeJson(client);

        // The property "a" in "/foo.json" is overwritten by the property "a" in "/foo2.json"
        String queryString = "path=/foo.json" + '&' +
                             "path=/foo1.json" + '&' +
                             "path=/foo2.json" + '&' +
                             "optional_path=/foo3.json";

        AggregatedHttpResponse aRes = client.get("/api/v1/projects/myPro/repos/myRepo/merge?" +
                                                 queryString).aggregate().join();

        String expectedJson =
                '{' +
                "   \"revision\" : 4," +
                "   \"type\" : \"JSON\"," +
                "   \"content\" : {" +
                "       \"a\" : \"new_bar\"," +
                "       \"b\" : \"baz\" " +
                "   }," +
                "   \"paths\" : [\"/foo.json\", \"/foo1.json\", \"/foo2.json\"] " +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);

        queryString = "path=/foo.json" + '&' +
                      "path=/foo1.json" + '&' +
                      "path=/foo2.json" + '&' +
                      "path=/foo3.json";
        aRes = client.get("/api/v1/projects/myPro/repos/myRepo/merge?" + queryString).aggregate().join();
        assertThat(aRes.status()).isEqualTo(HttpStatus.NOT_FOUND);
        expectedJson =
                '{' +
                "     \"exception\": \"com.linecorp.centraldogma.common.EntryNotFoundException\"," +
                "     \"message\": \"Entry '/foo3.json (revision: 4)' does not exist.\"" +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    void exceptionWhenOnlyOptionalFilesAndDoNotExist() {
        final WebClient client = dogma.httpClient();
        addFilesForMergeJson(client);
        final String queryString = "optional_path=/no_exist1.json" + '&' +
                                   "optional_path=/no_exist2.json";

        final AggregatedHttpResponse aRes = client.get("/api/v1/projects/myPro/repos/myRepo/merge?" +
                                                       queryString).aggregate().join();
        assertThat(aRes.status()).isEqualTo(HttpStatus.NOT_FOUND);
        final String expectedJson =
                '{' +
                "     \"exception\": \"com.linecorp.centraldogma.common.EntryNotFoundException\"," +
                "     \"message\": \"Entry '/no_exist1.json,/no_exist2.json (revision: 4)' does not exist.\"" +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    void mergeJsonPaths() {
        final WebClient client = dogma.httpClient();
        addFilesForMergeJson(client);
        String queryString = "path=/foo.json" + '&' +
                             "path=/foo1.json" + '&' +
                             "path=/foo2.json" + '&' +
                             "jsonpath=$[?(@.b == \"baz\")]&jsonpath=$[0].b";

        AggregatedHttpResponse aRes = client.get("/api/v1/projects/myPro/repos/myRepo/merge?" +
                                                 queryString).aggregate().join();
        String expectedJson =
                '{' +
                "   \"revision\" : 4," +
                "   \"type\" : \"JSON\"," +
                "   \"content\" : \"baz\"," +
                "   \"paths\" : [\"/foo.json\", \"/foo1.json\", \"/foo2.json\"] " +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);

        queryString = "path=/foo.json" + '&' +
                      "path=/foo1.json" + '&' +
                      "path=/foo2.json" + '&' +
                      "jsonpath=$.c";
        aRes = client.get("/api/v1/projects/myPro/repos/myRepo/merge?" + queryString).aggregate().join();
        assertThat(aRes.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        expectedJson =
                '{' +
                "     \"exception\": \"com.linecorp.centraldogma.common.QueryExecutionException\"," +
                "     \"message\": \"JSON path evaluation failed: $.c\"" +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Nested
    class MergeJsonMismatchTest {

        @RegisterExtension
        final CentralDogmaExtension dogma = new CentralDogmaExtension() {
            @Override
            protected void configureHttpClient(WebClientBuilder builder) {
                builder.addHttpHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
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
        void mismatchedValueWhileMerging() {
            final WebClient client = dogma.httpClient();
            addFilesForMergeJson(client);
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                             HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
            final String body =
                    '{' +
                    "   \"path\" : \"/foo10.json\"," +
                    "   \"type\" : \"UPSERT_JSON\"," +
                    "   \"content\" : {\"a\": 1}," +
                    "   \"commitMessage\" : {" +
                    "       \"summary\" : \"Add foo3.json\"" +
                    "   }" +
                    '}';
            client.execute(headers, body).aggregate().join();

            final String queryString = "path=/foo.json" + '&' +
                                       "path=/foo1.json" + '&' +
                                       "path=/foo2.json" + '&' +
                                       "path=/foo10.json";

            final AggregatedHttpResponse aRes = client.get("/api/v1/projects/myPro/repos/myRepo/merge?" +
                                                           queryString).aggregate().join();
            assertThat(aRes.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            final String expectedJson =
                    '{' +
                    "     \"exception\": \"com.linecorp.centraldogma.common.QueryExecutionException\"," +
                    "     \"message\": \"Failed to merge tree. /a/ type: NUMBER (expected: STRING)\"" +
                    '}';
            assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
        }
    }

    public static void createProject(CentralDogmaExtension dogma) {
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

    private static void addFilesForMergeJson(WebClient client) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, CONTENTS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        String body =
                '{' +
                "   \"path\" : \"/foo.json\"," +
                "   \"type\" : \"UPSERT_JSON\"," +
                "   \"content\" : {\"a\": \"bar\"}," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Add foo.json\"" +
                "   }" +
                '}';
        client.execute(headers, body).aggregate().join();
        body =
                '{' +
                "   \"path\" : \"/foo1.json\"," +
                "   \"type\" : \"UPSERT_JSON\"," +
                "   \"content\" : {\"b\": \"baz\"}," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Add foo1.json\"" +
                "   }" +
                '}';
        client.execute(headers, body).aggregate().join();
        body =
                '{' +
                "   \"path\" : \"/foo2.json\"," +
                "   \"type\" : \"UPSERT_JSON\"," +
                "   \"content\" : {\"a\": \"new_bar\"}," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Add foo3.json\"" +
                "   }" +
                '}';
        client.execute(headers, body).aggregate().join();
    }
}
