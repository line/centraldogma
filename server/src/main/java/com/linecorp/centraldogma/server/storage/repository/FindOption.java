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

package com.linecorp.centraldogma.server.storage.repository;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * An option which is specified when retrieving one or more files.
 *
 * @param <T> the type of the value
 */
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

    FindOption(String name, T defaultValue) {
        this.name = name;
        this.defaultValue = defaultValue;
    }

    /**
     * Returns the name of this option.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the default value of this option.
     */
    public T defaultValue() {
        return defaultValue;
    }

    /**
     * Returns {@code true} if the specified {@code value} is valid.
     */
    boolean isValid(T value) {
        return true;
    }

    /**
     * Returns the value if this option exists in the specified {@code options} map.
     * Otherwise, the default value would be returned.
     */
    public T get(@Nullable Map<FindOption<?>, ?> options) {
        if (options == null) {
            return defaultValue();
        }

        @SuppressWarnings("unchecked")
        final T value = (T) options.get(this);
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
