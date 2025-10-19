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

package com.linecorp.centraldogma.server.internal.api.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.jspecify.annotations.Nullable;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryType;

class QueryRequestConverterTest {

    private static final QueryRequestConverter converter = new QueryRequestConverter();

    @Test
    void convertIdentityQuery() throws Exception {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final String filePath = "/a.txt";
        when(ctx.pathParam("path")).thenReturn(filePath);

        final Query<?> query = convert(ctx);
        assertThat(query).isNotNull();
        assertThat(query.type()).isSameAs(QueryType.IDENTITY);
        assertThat(query.path()).isEqualTo(filePath);
    }

    @Test
    void invalidPath() throws Exception {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final String filePath = "/"; // a directory
        when(ctx.pathParam("path")).thenReturn(filePath);

        final Query<?> query = convert(ctx);
        assertThat(query).isNull();
    }

    @Test
    void convertJsonPathQuery() throws Exception {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final String jsonFilePath = "/a.json";
        when(ctx.pathParam("path")).thenReturn(jsonFilePath);

        final String httpQuery = "?jsonpath=%22%24.a%22";  // "$.a"
        when(ctx.query()).thenReturn(httpQuery);

        final Query<?> query = convert(ctx);
        assertThat(query).isNotNull();
        assertThat(query.type()).isSameAs(QueryType.JSON_PATH);
        assertThat(query.path()).isEqualTo(jsonFilePath);
        assertThat(query.expressions().get(0)).isEqualTo("\"$.a\"");
    }

    @Test
    void withoutExpression() throws Exception {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);

        // even though the file is a JSON file, the converted query's type will be IDENTITY because there's no
        // expression
        final String jsonFilePath = "/a.json";
        when(ctx.pathParam("path")).thenReturn(jsonFilePath);

        when(ctx.query()).thenReturn("");

        final Query<?> query = convert(ctx);
        assertThat(query).isNotNull();
        assertThat(query.type()).isSameAs(QueryType.IDENTITY);
        assertThat(query.path()).isEqualTo(jsonFilePath);
    }

    @Nullable
    private static Query<?> convert(ServiceRequestContext ctx) throws Exception {
        return (Query<?>) converter.convertRequest(ctx, mock(AggregatedHttpRequest.class), null, null);
    }
}
