/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.xds.internal;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.RequestAlreadyTimedOutException;
import com.linecorp.centraldogma.server.storage.StorageException;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;

final class ControlPlaneExceptionHandlerFunction implements GrpcExceptionHandlerFunction {

    @Nullable
    @Override
    public Status apply(RequestContext ctx, Status status, Throwable cause, Metadata metadata) {
        if (status.getCode() != Code.UNKNOWN) {
            return status;
        }

        if (cause instanceof RequestAlreadyTimedOutException) {
            return Status.DEADLINE_EXCEEDED.withCause(cause);
        }

        if (cause instanceof RepositoryNotFoundException) {
            return Status.NOT_FOUND.withCause(cause);
        }

        if (cause instanceof StorageException) {
            return Status.INTERNAL.withCause(cause);
        }

        return null;
    }
}
