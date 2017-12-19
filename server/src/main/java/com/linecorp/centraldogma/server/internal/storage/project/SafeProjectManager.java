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

package com.linecorp.centraldogma.server.internal.storage.project;

import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.INTERNAL_PROJECT_NAME;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * A wrapper class of {@link ProjectManager} which prevents accessing internal projects
 * from unprivileged requests.
 */
public class SafeProjectManager implements ProjectManager {

    private final ProjectManager delegate;

    public SafeProjectManager(ProjectManager delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    @Override
    public CacheStats cacheStats() {
        return delegate().cacheStats();
    }

    @Override
    public void close() {
        delegate().close();
    }

    @Override
    public boolean exists(String name) {
        validateProjectName(name);
        return delegate().exists(name);
    }

    @Override
    public Project get(String name) {
        validateProjectName(name);
        return delegate().get(name);
    }

    @Override
    public Project create(String name, long creationTimeMillis) {
        validateProjectName(name);
        return delegate().create(name, creationTimeMillis);
    }

    @Override
    public Map<String, Project> list() {
        final Map<String, Project> list = delegate().list();
        final Map<String, Project> ret = new LinkedHashMap<>(list.size());
        for (Map.Entry<String, Project> entry : list.entrySet()) {
            if (isValidProjectName(entry.getValue().name())) {
                ret.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(ret);
    }

    @Override
    public Set<String> listRemoved() {
        return delegate().listRemoved();
    }

    @Override
    public void remove(String name) {
        validateProjectName(name);
        delegate().remove(name);
    }

    @Override
    public Project unremove(String name) {
        validateProjectName(name);
        return delegate().unremove(name);
    }

    @Override
    public void ensureOpen() {
        delegate().ensureOpen();
    }

    protected final ProjectManager delegate() {
        return delegate;
    }

    protected static void validateProjectName(String name) {
        if (!isValidProjectName(name)) {
            throw new IllegalArgumentException("Illegal access to project '" + name + '\'');
        }
    }

    protected static boolean isValidProjectName(String name) {
        return name != null &&
               !INTERNAL_PROJECT_NAME.equals(name);
    }
}
