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

import com.linecorp.armeria.common.CompletableRpcResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingRpcService;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaException;

public final class CentralDogmaExceptionTranslator extends SimpleDecoratingRpcService {

    public CentralDogmaExceptionTranslator(RpcService delegate) {
        super(delegate);
    }

    @Override
    public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
        final CompletableRpcResponse newRes = new CompletableRpcResponse();
        try {
            final RpcResponse oldRes = delegate().serve(ctx, req);
            oldRes.handle((res, cause) -> {
                if (cause != null) {
                    handleException(req, newRes, cause);
                } else {
                    newRes.complete(res);
                }
                return null;
            });
        } catch (Throwable cause) {
            handleException(req, newRes, cause);
        }
        return newRes;
    }

    private static void handleException(RpcRequest req, CompletableRpcResponse res, Throwable cause) {
        final CentralDogmaException convertedCause = Converter.convert(cause);
        CentralDogmaExceptions.log(req.method(), convertedCause);
        res.completeExceptionally(convertedCause);
    }
}
