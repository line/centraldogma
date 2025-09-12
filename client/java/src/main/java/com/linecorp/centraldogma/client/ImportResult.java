/*
 * Copyright 2021 LINE Corporation
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

import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Revision;

/**
 * The wrapper of the result of an import operation.
 */
public final class ImportResult {

    /**
     * Creates a new instance from the specified {@link PushResult}.
     */
    public static ImportResult fromPushResult(PushResult pr) {
        requireNonNull(pr, "pushResult");
        return new ImportResult(pr.revision(), pr.when());
    }

    /**
     * Creates a new instance that represents an empty import operation.
     *
     * <p>An empty import operation means that no files were imported.
     */
    public static ImportResult empty() {
        return new ImportResult(Revision.INIT, 0, true);
    }

    private final boolean isEmpty;
    private final Revision revision;
    private final long when;

    /**
     * Creates a new instance.
     *
     * @param revision the {@link Revision} of the pushed commit
     * @param when the time and date of the pushed commit, represented as the number of milliseconds
     *             since the epoch (midnight, January 1, 1970 UTC)
     * @param isEmpty whether the import operation was empty (i.e. no files were imported)
     */
    private ImportResult(Revision revision, long when, boolean isEmpty) {
        this.revision = requireNonNull(revision, "revision");
        this.when = when / 1000L * 1000L;
        this.isEmpty = isEmpty;
    }

    /**
     * Creates a new instance.
     *
     * @param revision the {@link Revision} of the pushed commit
     * @param when the time and date of the pushed commit, represented as the number of milliseconds
     *             since the epoch (midnight, January 1, 1970 UTC)
     */
    private ImportResult(Revision revision, long when) {
        this(revision, when, false);
    }

    /**
     * Returns {@code true} if the import operation was empty (i.e. no files were imported).
     */
    public boolean isEmpty() {
        return isEmpty;
    }

    /**
     * Returns the {@link Revision} of the pushed commit.
     */
    public Revision revision() {
        return revision;
    }

    /**
     * Returns the time and date of the pushed commit.
     *
     * @return the number of milliseconds since the epoch (midnight, January 1, 1970 UTC)
     */
    public long whenMillis() {
        return when;
    }

    @Override
    public String toString() {
        return "ImportResult{" +
               "isEmpty=" + isEmpty +
               ", revision=" + revision +
               ", when=" + when +
               '}';
    }
}
