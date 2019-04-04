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

package com.linecorp.centraldogma.server.storage;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;

/**
 * An abstract interface to define operations performed on the storage.
 *
 * @param <T> type of the managed element
 */
public interface StorageManager<T> {
    /**
     * Closes this manager.
     *
     * @param failureCauseSupplier the supplier which provides a reason why it is closed
     */
    void close(Supplier<CentralDogmaException> failureCauseSupplier);

    /**
     * Returns {@code true} if an element with the specified {@code name} exists.
     *
     * @param name the name of an element
     */
    boolean exists(String name);

    /**
     * Returns an element with the specified {@code name}.
     *
     * @param name the name of an element
     * @throws CentralDogmaException if no element with the specified {@code name} exists
     */
    T get(String name);

    /**
     * Returns a newly-created element with the specified {@code name} by the specified {@link Author}.
     *
     * @param name the name of an element which is supposed to be created
     * @param author the author who is creating the new element
     */
    default T create(String name, Author author) {
        return create(name, System.currentTimeMillis(), author);
    }

    /**
     * Returns a newly-created element with the specified {@code name} and the specified
     * {@code creationTimeMillis} by the specified {@link Author}.
     *
     * @param name the name of an element which is supposed to be created
     * @param creationTimeMillis the creation time in milliseconds
     * @param author the author who is creating the new element
     */
    T create(String name, long creationTimeMillis, Author author);

    /**
     * Returns all elements as a {@link Map} of the name and the element.
     */
    Map<String, T> list();

    /**
     * Returns a set of names for the elements which have been removed.
     */
    Set<String> listRemoved();

    /**
     * Removes an element with the specified {@code name}.
     *
     * @param name the name of an element which is supposed to be removed
     */
    void remove(String name);

    /**
     * Restores an element with the specified {@code name}.
     *
     * @param name the name of an element which is supposed to be restored
     */
    T unremove(String name);

    /**
     * Ensures that this manager is open.
     *
     * @throws IllegalStateException if this manager is not initialized yet
     * @throws CentralDogmaException if this manager has already been closed with a cause.
     *                               See {@link #close(Supplier)} method.
     */
    void ensureOpen();
}
