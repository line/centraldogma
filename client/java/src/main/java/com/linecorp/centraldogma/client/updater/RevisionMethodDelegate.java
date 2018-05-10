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
package com.linecorp.centraldogma.client.updater;

import java.lang.reflect.Method;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.common.Revision;

final class RevisionMethodDelegate implements MethodDelegate {
    @Override
    public boolean match(Method thisMethod) {
        return thisMethod.getReturnType() == Revision.class &&
               thisMethod.getParameterCount() == 0;
    }

    @Override
    @Nullable
    public <T> Object invoke(CentralDogmaBeanMethodHandler<T> handler, Object self, Method thisMethod,
                             Method proceed, Object[] args) throws Throwable {
        if (handler.watcher.initialValueFuture().isDone()) {
            return handler.watcher.latest().revision();
        } else {
            return null;
        }
    }
}
