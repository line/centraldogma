/*
 * Copyright 2019 LINE Corporation
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

import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.client.FileWatcher;
import com.linecorp.centraldogma.internal.client.RepositoryWatcher;

/**
 * A skeletal {@link CentralDogma} implementation.
 */
public abstract class AbstractCentralDogma implements CentralDogma {

    private final ScheduledExecutorService executor;

    /**
     * Creates a new instance.
     *
     * @param executor the {@link ScheduledExecutorService} which will be used for watching a file or
     *                 a repository
     */
    protected AbstractCentralDogma(ScheduledExecutorService executor) {
        this.executor = requireNonNull(executor, "executor");
    }

    @Override
    public final <T, U> Watcher<U> fileWatcher(String projectName, String repositoryName, Query<T> query,
                                               Function<? super T, ? extends U> function) {
        final FileWatcher<U> watcher =
                new FileWatcher<>(this, executor, projectName, repositoryName, query, function);
        watcher.start();
        return watcher;
    }

    @Override
    public final <T> Watcher<T> repositoryWatcher(String projectName, String repositoryName, String pathPattern,
                                                  Function<Revision, ? extends T> function) {
        final RepositoryWatcher<T> watcher =
                new RepositoryWatcher<>(this, executor,
                                        projectName, repositoryName, pathPattern, function);
        watcher.start();
        return watcher;
    }
}
