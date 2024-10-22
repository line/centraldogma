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

package com.linecorp.centraldogma.server;

import java.lang.reflect.InvocationTargetException;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.util.SystemInfo;

final class ZoneResolver {

    @Nullable
    public static String resolve(@Nullable String zone) {
        if (zone == null) {
            return null;
        }

        if (zone.startsWith("$")) {
            return System.getenv(zone.substring(1));
        } else if (zone.startsWith("classpath:")) {
            final String className = zone.substring(10);
            final Class<?> clazz;
            try {
                clazz = ZoneProvider.class.getClassLoader().loadClass(className);
                if (!ZoneProvider.class.isAssignableFrom(clazz)) {
                    throw new IllegalArgumentException(
                            "An unexpected zone provider: " + className + " (expected: an subtype of " +
                            ZoneProvider.class.getName() + ')');
                }
                final ZoneProvider zoneProvider = (ZoneProvider) clazz.getDeclaredConstructor().newInstance();
                return zoneProvider.zone(SystemInfo.hostname());
            } catch (InstantiationException | NoSuchMethodException | InvocationTargetException |
                     IllegalAccessException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        } else {
            return zone;
        }
    }

    private ZoneResolver() {}
}
