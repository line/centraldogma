/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

/**
 * A function that transforms an {@link Entry} before applying {@link Query} on it.
 *
 * @see Repository#get(Revision, Query, EntryTransformer)
 */
@FunctionalInterface
public interface EntryTransformer<T> {

    /**
     * Returns an identity transformer that returns the given entry as is.
     */
    static <T> EntryTransformer<T> identity() {
        return UnmodifiableFuture::completedFuture;
    }

    /**
     * Transforms the specified {@link Entry}.
     */
    CompletableFuture<Entry<T>> transform(Entry<T> entry);
}
