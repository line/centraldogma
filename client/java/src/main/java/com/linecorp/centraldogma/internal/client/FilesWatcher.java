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
package com.linecorp.centraldogma.internal.client;

import static java.util.Objects.requireNonNull;

import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.Latest;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;

/**
 * Similar to {@link RepositoryWatcher} but retrieves files after receive a change notification.
 * @param <T>
 */
public final class FilesWatcher<T> extends AbstractWatcher<Map<String, Entry<T>>> {
    private final String pathPattern;
    private final Function<Entry<?>, ? extends Entry<T>> function;

    /**
     * Creates a new instance.
     */
    public FilesWatcher(CentralDogma client, ScheduledExecutorService watchScheduler,
                        String projectName, String repositoryName,
                        String pathPattern, Function<Entry<?>, ? extends Entry<T>> function) {
        super(client, watchScheduler, projectName, repositoryName, pathPattern);
        this.pathPattern = requireNonNull(pathPattern, "pathPattern");
        this.function = requireNonNull(function, "function");
    }

    @Override
    protected CompletableFuture<Latest<Map<String, Entry<T>>>> doWatch(CentralDogma client, String projectName,
                                                                       String repositoryName,
                                                                       Revision lastKnownRevision) {
        return client.watchRepository(projectName, repositoryName, lastKnownRevision, pathPattern)
                     .thenComposeAsync(revision -> {
                         if (revision == null) {
                             return CompletableFuture.completedFuture(null);
                         }
                         return client.getFiles(projectName, repositoryName, revision, pathPattern)
                                      .thenApply(files -> {
                                          if (files == null) {
                                              return null;
                                          }
                                          return new Latest<>(revision, convert(files));
                                      });
                     }, watchScheduler);
    }

    private Map<String, Entry<T>> convert(Map<String, Entry<?>> files) {
        return files.entrySet().stream()
                    .map(e -> new SimpleEntry<>(e.getKey(), function.apply(e.getValue())))
                    .collect(ImmutableMap.toImmutableMap(SimpleEntry::getKey,
                                                         SimpleEntry::getValue));
    }
}
