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

import com.linecorp.centraldogma.client.Watcher;

import javassist.util.proxy.MethodHandler;

class CentralDogmaBeanMethodHandler<T> implements MethodHandler {
    private final Watcher<T> watcher;
    private final T defaultValue;

    CentralDogmaBeanMethodHandler(Watcher<T> watcher, T defaultValue) {
        this.watcher = watcher;
        this.defaultValue = requireNonNull(defaultValue, "defaultValue");
    }

    @Override
    public Object invoke(Object self, Method thisMethod, Method proceed, Object[] args) throws Throwable {
        // TODO(ide) Push the change if thisMethod is setter method
        thisMethod.setAccessible(true);
        return thisMethod.invoke(watcher.latestValue(defaultValue), args);
    }
}
