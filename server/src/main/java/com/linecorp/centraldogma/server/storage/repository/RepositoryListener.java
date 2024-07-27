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

package com.linecorp.centraldogma.server.storage.repository;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.function.Consumer;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;


/**
 * A listener that gets notified whenever changes matching with {@link #pathPattern()}
 * are pushed to {@link Repository}.
 */
public interface RepositoryListener {

    /**
     * Returns a new {@link RepositoryListener} with the specified {@code pathPattern} and {@link Consumer}.
     */
    static RepositoryListener of(String pathPattern, Consumer<? super Map<String, Entry<?>>> listener) {
        requireNonNull(pathPattern, "pathPattern");
        requireNonNull(listener, "listener");
        return new RepositoryListener() {
            @Override
            public String pathPattern() {
                return pathPattern;
            }

            @Override
            public void onUpdate(Map<String, Entry<?>> entries) {
                listener.accept(entries);
            }
        };
    }

    /**
     * Returns a new {@link RepositoryListener} with the specified {@link Query} and {@link Consumer}.
     */
    static <T> RepositoryListener of(Query<T> query, Consumer<? super Entry<T>> listener) {
        requireNonNull(query, "query");
        requireNonNull(listener, "listener");
        return new RepositoryListener() {
            @Override
            public String pathPattern() {
                return query.path();
            }

            @Override
            public void onUpdate(Map<String, Entry<?>> entries) {
                @SuppressWarnings("unchecked")
                Entry<T> entry = (Entry<T>) entries.get(query.path());
                if (entry == null) {
                    listener.accept(null);
                } else {
                    entry = RepositoryUtil.applyQuery(entry, query);
                    listener.accept(entry);
                }
            }
        };
    }

    /**
     * Returns the path pattern that this {@link RepositoryListener} is interested in.
     */
    String pathPattern();

    /**
     * Invoked when changes matching with {@link #pathPattern()} are pushed.
     */
    void onUpdate(Map<String, Entry<?>> entries);
}
