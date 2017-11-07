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

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.client.Watcher;

import javassist.util.proxy.MethodHandler;

final class CentralDogmaBeanMethodHandler<T> implements MethodHandler {
    final Watcher<T> watcher;
    final T defaultValue;

    CentralDogmaBeanMethodHandler(Watcher<T> watcher, T defaultValue) {
        this.watcher = watcher;
        this.defaultValue = requireNonNull(defaultValue, "defaultValue");
    }

    // note that below delegate will be match from top to bottom
    // so if your matcher may duplicate with another one, please consider the order carefully
    private static final List<MethodDelegate> methodDelegators = ImmutableList.of(
            new RevisionMethodDelegate(),
            new CloseMethodDelegate(),

            // The last delegator must be DefaultMethodDelegator.
            new DefaultMethodDelegate());

    @Override
    public Object invoke(Object self, Method thisMethod, Method proceed, Object[] args) throws Throwable {
        // TODO(ide) Push the change if thisMethod is setter method
        thisMethod.setAccessible(true);
        for (MethodDelegate md : methodDelegators) {
            if (md.match(thisMethod)) {
                return md.invoke(this, self, thisMethod, proceed, args);
            }
        }

        // it must not reach here
        throw new IllegalStateException("method is not handled: " + thisMethod);
    }
}
