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

package com.linecorp.centraldogma.server.internal.api.converter;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.CommitMessageDto;

/**
 * A request converter that converts to {@link CommitMessageDto}.
 */
public final class CommitMessageRequestConverter extends JacksonRequestConverterFunction {

    @Override
    public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                 Class<?> expectedResultType) throws Exception {
        if (expectedResultType == CommitMessageDto.class) {
            final JsonNode node = (JsonNode) super.convertRequest(ctx, request, JsonNode.class);
            if (node == null || node.get("commitMessage") == null) {
                throw new IllegalArgumentException("commitMessage should be non-null.");
            }

            return convertCommitMessage(node.get("commitMessage"));
        }
        return RequestConverterFunction.fallthrough();
    }

    private static CommitMessageDto convertCommitMessage(JsonNode jsonNode) {
        final CommitMessageDto commitMessage = Jackson.convertValue(jsonNode, CommitMessageDto.class);
        checkArgument(!isNullOrEmpty(commitMessage.summary()), "summary should be non-null");
        return commitMessage;
    }
}
