/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.lang.reflect.ParameterizedType;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.api.TemplateParams;

/**
 * A request converter that converts to {@link Query} when the request has a valid file path.
 */
public final class TemplateParamsConverter implements RequestConverterFunction {

    /**
     * Extracts the template parameters from the request query parameters and converts them to
     * {@link TemplateParams}.
     */
    @Override
    public TemplateParams convertRequest(
            ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
            @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {
        final boolean renderTemplate = Boolean.parseBoolean(ctx.queryParam("renderTemplate"));
        final String variableFile = ctx.queryParam("variableFile");
        final String templateRevStr = ctx.queryParam("templateRevision");
        final Revision templateRevision = templateRevStr != null ? new Revision(templateRevStr) : null;

        return TemplateParams.of(renderTemplate, variableFile, templateRevision);
    }
}
