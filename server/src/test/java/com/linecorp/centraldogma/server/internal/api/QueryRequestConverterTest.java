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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.Test;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryType;
import com.linecorp.centraldogma.server.internal.api.converter.QueryRequestConverter;

public class QueryRequestConverterTest {

    private static final QueryRequestConverter converter = new QueryRequestConverter();

    @Test
    public void convertIdentityQuery() throws Exception {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final String filePath = "/a.txt";
        when(ctx.pathParam("path")).thenReturn(filePath);

        final Optional<Query<?>> query = convert(ctx);
        assert query.isPresent();
        assertThat(query.get().type()).isSameAs(QueryType.IDENTITY);
        assertThat(query.get().path()).isEqualTo(filePath);
    }

    @Test
    public void invalidPath() throws Exception {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final String filePath = "/"; // a directory
        when(ctx.pathParam("path")).thenReturn(filePath);

        final Optional<Query<?>> query = convert(ctx);
        assertThat(query.isPresent()).isFalse();
    }

    @Test
    public void convertJsonPathQuery() throws Exception {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final String jsonFilePath = "/a.json";
        when(ctx.pathParam("path")).thenReturn(jsonFilePath);

        final String httpQuery = "?jsonpath=%22%24.a%22";  // "$.a"
        when(ctx.query()).thenReturn(httpQuery);

        final Optional<Query<?>> query = convert(ctx);
        assert query.isPresent();
        assertThat(query.get().type()).isSameAs(QueryType.JSON_PATH);
        assertThat(query.get().path()).isEqualTo(jsonFilePath);
        assertThat(query.get().expressions().get(0)).isEqualTo("\"$.a\"");
    }

    @Test
    public void withoutExpression() throws Exception {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);

        // even though the file is a JSON file, the converted query's type will be IDENTITY because there's no
        // expression
        final String jsonFilePath = "/a.json";
        when(ctx.pathParam("path")).thenReturn(jsonFilePath);

        when(ctx.query()).thenReturn("");

        final Optional<Query<?>> query = convert(ctx);
        assert query.isPresent();
        assertThat(query.get().type()).isSameAs(QueryType.IDENTITY);
        assertThat(query.get().path()).isEqualTo(jsonFilePath);
    }

    @SuppressWarnings("unchecked")
    private static Optional<Query<?>> convert(ServiceRequestContext ctx) throws Exception {
        return (Optional<Query<?>>) converter.convertRequest(ctx, mock(AggregatedHttpMessage.class), null);
    }
}
