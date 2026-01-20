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

package com.linecorp.centraldogma.client;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Revision;

/**
 * An immutable holder of the latest known value and its {@link Revision} retrieved by a {@link Watcher}.
 *
 * @param <U> the value type
 */
public final class Latest<U> {

    private final Revision revision;
    @Nullable
    private final Revision templateRevision;
    @Nullable
    private final U value;

    /**
     * Creates a new instance with the specified {@link Revision} and value.
     */
    public Latest(Revision revision, @Nullable U value) {
        this(revision, null, value);
    }

    /**
     * Creates a new instance with the specified {@link Revision}s and value.
     */
    public Latest(Revision revision, @Nullable Revision templateRevision, @Nullable U value) {
        this.revision = requireNonNull(revision, "revision");
        this.templateRevision = templateRevision;
        this.value = value;
    }

    /**
     * Returns the {@link Revision} of the latest known value.
     */
    public Revision revision() {
        return revision;
    }

    /**
     * Returns the revision of the template variables that were used to render the template and generate this
     * {@code value}.
     *
     * <p>{@code null} if this {@code value} was not generated from a template using project or repository level
     * variables.
     */
    @Nullable
    public Revision templateRevision() {
        return templateRevision;
    }

    /**
     * Returns the latest known value.
     */
    @Nullable
    public U value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Latest<?>)) {
            return false;
        }
        final Latest<?> that = (Latest<?>) o;
        return revision.equals(that.revision) &&
               Objects.equals(templateRevision, that.templateRevision) &&
               Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(revision, templateRevision, value);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("revision", revision)
                          .add("templateRevision", templateRevision)
                          .add("value", value)
                          .toString();
    }
}
