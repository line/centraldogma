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

package com.linecorp.centraldogma.server.internal.storage.repository;

import java.util.Map;

import com.linecorp.centraldogma.internal.Util;

public class FindOption<T> {

    /**
     * Whether to fetch the content of the found files. The default value is {@code true}.
     */
    public static final FindOption<Boolean> FETCH_CONTENT = new FindOption<>("FETCH_CONTENT", true);

    /**
     * The maximum number of the fetched files. The default value is {@link Integer#MAX_VALUE}.
     */
    public static final FindOption<Integer> MAX_ENTRIES =
            new FindOption<Integer>("MAX_ENTRIES", Integer.MAX_VALUE) {
                @Override
                boolean isValid(Integer value) {
                    return value > 0;
                }
            };

    private final String name;
    private final T defaultValue;
    private final String fullName;

    FindOption(String name, T defaultValue) {
        this.name = name;
        this.defaultValue = defaultValue;
        fullName = Util.simpleTypeName(FindOption.class) + '.' + name;
    }

    public String name() {
        return name;
    }

    public T defaultValue() {
        return defaultValue;
    }

    public final void validate(T value) {
        if (value == null) {
            throw new NullPointerException(fullName);
        }

        if (!isValid(value)) {
            throw new IllegalArgumentException(fullName + ": " + value);
        }
    }

    boolean isValid(T value) {
        return true;
    }

    public T get(Map<FindOption<?>, ?> options) {
        if (options == null) {
            return defaultValue();
        }

        @SuppressWarnings("unchecked")
        T value = (T) options.get(this);
        if (value == null) {
            return defaultValue();
        }

        return value;
    }

    @Override
    public String toString() {
        return name();
    }
}
