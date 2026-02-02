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

import static com.linecorp.centraldogma.internal.Util.validateStructuredFilePath;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryType;
import com.linecorp.centraldogma.common.Revision;

/**
 * Prepares to send a {@link CentralDogma#watchFile(String, String, Revision, Query, long, boolean)}
 * request to the Central Dogma repository or create a new {@link Watcher}.
 */
public final class WatchRequest<T> extends WatchOptions {

    private final CentralDogmaRepository centralDogmaRepo;
    private final Query<T> query;
    private boolean viewRaw;
    private boolean renderTemplate;
    @Nullable
    private String variableFile;

    WatchRequest(CentralDogmaRepository centralDogmaRepo, Query<T> query) {
        this.centralDogmaRepo = centralDogmaRepo;
        this.query = query;
    }

    @Override
    public WatchRequest<T> timeout(Duration timeout) {
        //noinspection unchecked
        return (WatchRequest<T>) super.timeout(timeout);
    }

    @Override
    public WatchRequest<T> timeoutMillis(long timeoutMillis) {
        //noinspection unchecked
        return (WatchRequest<T>) super.timeoutMillis(timeoutMillis);
    }

    @Override
    public WatchRequest<T> errorOnEntryNotFound(boolean errorOnEntryNotFound) {
        //noinspection unchecked
        return (WatchRequest<T>) super.errorOnEntryNotFound(errorOnEntryNotFound);
    }

    /**
     * Sets whether to view the raw content of the watched files.
     * The default is {@code false}.
     *
     * <p>Note that {@link QueryType#JSON_PATH} query cannot be used with raw view.
     * @throws IllegalArgumentException if {@link QueryType#JSON_PATH} query is used with raw view
     */
    public WatchRequest<T> viewRaw(boolean viewRaw) {
        if (viewRaw && query.type() == QueryType.JSON_PATH) {
            throw new IllegalArgumentException("JSON_PATH query cannot be used with raw view");
        }
        this.viewRaw = viewRaw;
        return this;
    }

    /**
     * Sets whether to apply template processing to the file using the variables defined in
     * the same repository and its parent project.
     *
     * <p>If {@link #viewRaw(boolean)} is set to true, the template processing will be applied to the raw
     * content. If {@link #viewRaw(boolean)} is set to false, the template processing will be applied to the
     * normalized content.
     */
    public WatchRequest<T> renderTemplate(boolean renderTemplate) {
        this.renderTemplate = renderTemplate;
        variableFile = null;
        return this;
    }

    /**
     * Applies template processing to the file using the specified variable file in the same repository.
     * The variable file must be a JSON, JSON5 or YAML file and have an object at the top level (arrays or
     * string are not allowed).
     *
     * <p>If {@link #viewRaw(boolean)} is set to true, the template processing will be applied to the raw
     * content. If {@link #viewRaw(boolean)} is set to false, the template processing will be applied to the
     * normalized content.
     */
    public WatchRequest<T> renderTemplate(String variableFile) {
        validateStructuredFilePath(variableFile, "variableFile");
        renderTemplate = true;
        this.variableFile = variableFile;
        return this;
    }

    /**
     * Waits for the file matched by the {@link Query} to be changed since the {@link Revision#HEAD}.
     * If no changes were made within the {@link #timeoutMillis(long)}, the
     * returned {@link CompletableFuture} will be completed with {@code null}.
     *
     * @return the {@link Entry} which contains the latest known {@link Query} result.
     *         {@code null} if the file was not changed for {@link #timeoutMillis(long)} milliseconds
     *         since the invocation of this method. {@link EntryNotFoundException} is raised if the
     *         target does not exist.
     */
    public CompletableFuture<Entry<T>> start() {
        return start(Revision.HEAD);
    }

    /**
     * Waits for the file matched by the {@link Query} to be changed since the {@code lastKnownRevision}.
     * If no changes were made within the {@link #timeoutMillis(long)}, the
     * returned {@link CompletableFuture} will be completed with {@code null}.
     *
     * @return the {@link Entry} which contains the latest known {@link Query} result.
     *         {@code null} if the file was not changed for {@link #timeoutMillis(long)} milliseconds
     *         since the invocation of this method. {@link EntryNotFoundException} is raised if the
     *         target does not exist.
     */
    public CompletableFuture<Entry<T>> start(Revision lastKnownRevision) {
        requireNonNull(lastKnownRevision, "lastKnownRevision");
        return centralDogmaRepo.centralDogma().watchFile(centralDogmaRepo.projectName(),
                                                         centralDogmaRepo.repositoryName(),
                                                         lastKnownRevision, query,
                                                         timeoutMillis(), errorOnEntryNotFound(), viewRaw,
                                                         renderTemplate, variableFile, null);
    }
}
