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
package com.linecorp.centraldogma.server.internal.pluggable;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Function;

import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.centraldogma.server.pluggable.PluggableService;

public final class PluggableServices {

    public static List<PluggableService> load(ClassLoader classLoader,
                                              Class<? extends PluggableService> pluginType) {
        return load(classLoader, pluginType, Function.identity());
    }

    public static <T> List<PluggableService> load(ClassLoader classLoader, Class<T> pluginType,
                                                  Function<T, ? extends PluggableService> converter) {
        requireNonNull(classLoader, "classLoader");
        requireNonNull(pluginType, "pluginType");
        requireNonNull(converter, "converter");

        final ServiceLoader<T> loader = ServiceLoader.load(pluginType, classLoader);
        final Builder<PluggableService> services = new Builder<>();
        for (T service : loader) {
            services.add(converter.apply(service));
        }
        return services.build();
    }

    private PluggableServices() {}
}
