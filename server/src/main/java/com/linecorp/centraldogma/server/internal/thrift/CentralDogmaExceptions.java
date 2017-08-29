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

package com.linecorp.centraldogma.server.internal.thrift;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaException;
import com.linecorp.centraldogma.internal.thrift.ErrorCode;

final class CentralDogmaExceptions {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaExceptions.class);

    static void log(String operationName, CentralDogmaException e) {
        final RequestContext ctx =
                RequestContext.mapCurrent(Function.identity(), () -> null);

        final ErrorCode errorCode = e.getErrorCode();
        switch (errorCode) {
            case BAD_REQUEST:
            case PROJECT_NOT_FOUND:
            case PROJECT_EXISTS:
            case REPOSITORY_NOT_FOUND:
            case REPOSITORY_EXISTS:
            case REVISION_NOT_FOUND:
            case REVISION_EXISTS:
            case ENTRY_NOT_FOUND:
            case CHANGE_CONFLICT:
            case QUERY_FAILURE:
                if (logger.isDebugEnabled()) {
                    if (ctx != null) {
                        logger.debug("{} Exception with error code {} ({}) from: {}()",
                                     ctx, errorCode, errorCode.getValue(), operationName, e);

                    } else {
                        logger.debug("Exception with error code {} ({}) from: {}()",
                                     errorCode, errorCode.getValue(), operationName, e);
                    }
                }
                break;
            case REDUNDANT_CHANGE:
            case SHUTTING_DOWN:
                // Do not log the stack trace for REDUNDANT_CHANGE and SHUTTING_DOWN error code
                // because it is expected to occur very often.
                if (logger.isDebugEnabled()) {
                    if (ctx != null) {
                        logger.debug("{} Exception with error code {} ({}) from: {}()",
                                     ctx, errorCode, errorCode.getValue(), operationName);

                    } else {
                        logger.debug("Exception with error code {} ({}) from: {}()",
                                     errorCode, errorCode.getValue(), operationName);
                    }
                }
                break;
            default:
                if (ctx != null) {
                    logger.warn("{} Unexpected exception with error code {} ({}) from: {}()",
                                ctx, errorCode, errorCode.getValue(), operationName, e);
                } else {
                    logger.warn("Unexpected exception with error code {} ({}) from: {}()",
                                errorCode, errorCode.getValue(), operationName, e);
                }
        }
    }

    private CentralDogmaExceptions() {}
}
