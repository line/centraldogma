/*
 * Copyright 2018 LINE Corporation
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

import org.junit.Test;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

public class MergeFileTest extends ContentServiceV1TestBase {

    @Test
    public void mergeJsonFiles() {
        addFilesForMergeJson();

        // The property "a" in "/foo.json" is overwritten by the property "a" in "/foo2.json"
        String queryString = "path=/foo.json" + '&' +
                             "path=/foo1.json" + '&' +
                             "path=/foo2.json" + '&' +
                             "optional_path=/foo3.json";

        AggregatedHttpMessage aRes = httpClient().get("/api/v1/projects/myPro/repos/myRepo/merge?" +
                                                      queryString).aggregate().join();

        final String expectedJson =
                '{' +
                "   \"revision\" : 4," +
                "   \"type\" : \"JSON\"," +
                "   \"content\" : {" +
                "       \"a\" : \"new_bar\"," +
                "       \"b\" : \"baz\" " +
                "   }," +
                "   \"paths\" : [\"/foo.json\", \"/foo1.json\", \"/foo2.json\"] " +
                '}';
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);

        queryString = "path=/foo.json" + '&' +
                      "path=/foo1.json" + '&' +
                      "path=/foo2.json" + '&' +
                      "path=/foo3.json";
        aRes = httpClient().get("/api/v1/projects/myPro/repos/myRepo/merge?" + queryString).aggregate()
                           .join();
        assertThat(aRes.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(aRes.content().toStringUtf8()).contains("Entry '/foo3.json (4)' does not exist.");
    }

    @Test
    public void exceptionWhenOnlyOptionalFilesAndDoNotExist() {
        addFilesForMergeJson();
        final String queryString = "optional_path=/no_exist1.json" + '&' +
                                   "optional_path=/no_exist2.json";

        final AggregatedHttpMessage aRes = httpClient().get("/api/v1/projects/myPro/repos/myRepo/merge?" +
                                                            queryString).aggregate().join();
        assertThat(aRes.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(aRes.content().toStringUtf8()).contains(
                "Entry '/no_exist1.json,/no_exist2.json (4)' does not exist.");
    }

    @Test
    public void mismatchedValueWhileMerging() {
        addFilesForMergeJson();
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX)
                                               .contentType(MediaType.JSON);
        final String body =
                '{' +
                "   \"path\" : \"/foo10.json\"," +
                "   \"type\" : \"UPSERT_JSON\"," +
                "   \"content\" : {\"a\": 1}," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Add foo3.json\"" +
                "   }" +
                '}';
        httpClient().execute(headers, body).aggregate().join();

        final String queryString = "path=/foo.json" + '&' +
                                   "path=/foo1.json" + '&' +
                                   "path=/foo2.json" + '&' +
                                   "path=/foo10.json";

        final AggregatedHttpMessage aRes = httpClient().get("/api/v1/projects/myPro/repos/myRepo/merge?" +
                                                            queryString).aggregate().join();
        assertThat(aRes.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(aRes.content().toStringUtf8()).contains("Failed to merge tree.");
    }

    @Test
    public void mergeJsonPaths() {
        addFilesForMergeJson();
        String queryString = "path=/foo.json" + '&' +
                             "path=/foo1.json" + '&' +
                             "path=/foo2.json" + '&' +
                             "jsonpath=$[?(@.b == \"baz\")]&jsonpath=$[0].b";

        AggregatedHttpMessage aRes = httpClient().get("/api/v1/projects/myPro/repos/myRepo/merge?" +
                                                      queryString).aggregate().join();
        final String actualJson = aRes.content().toStringUtf8();
        final String expectedJson =
                '{' +
                "   \"revision\" : 4," +
                "   \"type\" : \"JSON\"," +
                "   \"content\" : \"baz\"," +
                "   \"paths\" : [\"/foo.json\", \"/foo1.json\", \"/foo2.json\"] " +
                '}';
        assertThatJson(actualJson).isEqualTo(expectedJson);

        queryString = "path=/foo.json" + '&' +
                      "path=/foo1.json" + '&' +
                      "path=/foo2.json" + '&' +
                      "jsonpath=$.c";
        aRes = httpClient().get("/api/v1/projects/myPro/repos/myRepo/merge?" + queryString).aggregate()
                           .join();
        assertThat(aRes.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(aRes.content().toStringUtf8()).contains("JSON path evaluation failed: $.c");
    }

    private void addFilesForMergeJson() {
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, CONTENTS_PREFIX)
                                               .contentType(MediaType.JSON);
        String body =
                '{' +
                "   \"path\" : \"/foo.json\"," +
                "   \"type\" : \"UPSERT_JSON\"," +
                "   \"content\" : {\"a\": \"bar\"}," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Add foo.json\"" +
                "   }" +
                '}';
        httpClient().execute(headers, body).aggregate().join();
        body =
                '{' +
                "   \"path\" : \"/foo1.json\"," +
                "   \"type\" : \"UPSERT_JSON\"," +
                "   \"content\" : {\"b\": \"baz\"}," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Add foo1.json\"" +
                "   }" +
                '}';
        httpClient().execute(headers, body).aggregate().join();
        body =
                '{' +
                "   \"path\" : \"/foo2.json\"," +
                "   \"type\" : \"UPSERT_JSON\"," +
                "   \"content\" : {\"a\": \"new_bar\"}," +
                "   \"commitMessage\" : {" +
                "       \"summary\" : \"Add foo3.json\"" +
                "   }" +
                '}';
        httpClient().execute(headers, body).aggregate().join();
    }
}
