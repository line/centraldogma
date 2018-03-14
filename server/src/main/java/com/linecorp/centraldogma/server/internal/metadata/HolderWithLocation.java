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

package com.linecorp.centraldogma.server.internal.metadata;

import static java.util.Objects.requireNonNull;

public final class HolderWithLocation<T> {

    public static <T> HolderWithLocation<T> of(T object, String location) {
        return new HolderWithLocation<>(object, location);
    }

    private final T object;
    private final String location;

    HolderWithLocation(T object, String location) {
        this.object = requireNonNull(object, "object");
        this.location = requireNonNull(location, "location");
    }

    public T object() {
        return object;
    }

    public String location() {
        return location;
    }
}
