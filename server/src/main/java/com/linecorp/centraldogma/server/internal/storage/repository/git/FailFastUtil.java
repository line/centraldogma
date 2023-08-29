/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.internal.storage.RequestAlreadyTimedOutException;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class FailFastUtil {

    private static final RequestAlreadyTimedOutException REQUEST_ALREADY_TIMED_OUT =
            Exceptions.clearTrace(new RequestAlreadyTimedOutException("Request already timed out."));

    static {
        REQUEST_ALREADY_TIMED_OUT.initCause(null);
    }

    @Nullable
    static ServiceRequestContext context() {
        return RequestContext.mapCurrent(ServiceRequestContext.class::cast, null);
    }

    @SuppressWarnings("MethodParameterNamingConvention")
    static void failFastIfTimedOut(Repository repo, Logger logger, @Nullable ServiceRequestContext ctx,
                                   String methodName, Object arg1) {
        if (ctx != null && ctx.isTimedOut()) {
            logger.info("{} Rejecting a request timed out already: repo={}/{}, method={}, args={}",
                        ctx, repo.parent().name(), repo.name(), methodName, arg1);
            throw REQUEST_ALREADY_TIMED_OUT;
        }
    }

    @SuppressWarnings("MethodParameterNamingConvention")
    static void failFastIfTimedOut(Repository repo, Logger logger, @Nullable ServiceRequestContext ctx,
                                   String methodName, Object arg1, Object arg2) {
        if (ctx != null && ctx.isTimedOut()) {
            logger.info("{} Rejecting a request timed out already: repo={}/{}, method={}, args=[{}, {}]",
                        ctx, repo.parent().name(), repo.name(), methodName, arg1, arg2);
            throw REQUEST_ALREADY_TIMED_OUT;
        }
    }

    @SuppressWarnings("MethodParameterNamingConvention")
    static void failFastIfTimedOut(Repository repo, Logger logger, @Nullable ServiceRequestContext ctx,
                                   String methodName, Object arg1, Object arg2, Object arg3) {
        if (ctx != null && ctx.isTimedOut()) {
            logger.info("{} Rejecting a request timed out already: repo={}/{}, method={}, args=[{}, {}, {}]",
                        ctx, repo.parent().name(), repo.name(), methodName, arg1, arg2, arg3);
            throw REQUEST_ALREADY_TIMED_OUT;
        }
    }

    @SuppressWarnings("MethodParameterNamingConvention")
    static void failFastIfTimedOut(Repository repo, Logger logger, @Nullable ServiceRequestContext ctx,
                                   String methodName, Object arg1, Object arg2, Object arg3, int arg4) {
        if (ctx != null && ctx.isTimedOut()) {
            logger.info(
                    "{} Rejecting a request timed out already: repo={}/{}, method={}, args=[{}, {}, {}, {}]",
                    ctx, repo.parent().name(), repo.name(), methodName, arg1, arg2, arg3, arg4);
            throw REQUEST_ALREADY_TIMED_OUT;
        }
    }

    private FailFastUtil() {}
}
