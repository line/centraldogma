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

import static com.linecorp.centraldogma.server.internal.storage.project.ProjectInitializer.INTERNAL_PROJECT_DOGMA;
import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

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
    public void close(Supplier<CentralDogmaException> failureCauseSupplier) {
        delegate().close(failureCauseSupplier);
    }

    @Override
    public boolean exists(String name) {
        validateProjectName(name, true);
        return delegate().exists(name);
    }

    @Override
    public Project get(String name) {
        validateProjectName(name, true);
        return delegate().get(name);
    }

    @Override
    public Project create(String name, long creationTimeMillis, Author author) {
        validateProjectName(name, false);
        return delegate().create(name, creationTimeMillis, author);
    }

    @Override
    public Map<String, Project> list() {
        final Map<String, Project> list = delegate().list();
        final Map<String, Project> ret = new LinkedHashMap<>(list.size());
        for (Map.Entry<String, Project> entry : list.entrySet()) {
            if (isValidProjectName(entry.getValue().name(), true)) {
                ret.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(ret);
    }

    @Override
    public Map<String, Instant> listRemoved() {
        return delegate().listRemoved();
    }

    @Override
    public void remove(String name) {
        validateProjectName(name, false);
        delegate().remove(name);
    }

    @Override
    public Project unremove(String name) {
        validateProjectName(name, false);
        return delegate().unremove(name);
    }

    @Override
    public void purgeMarked() {
        delegate().purgeMarked();
    }

    @Override
    public void markForPurge(String name) {
        validateProjectName(name, false);
        delegate().markForPurge(name);
    }

    @Override
    public void ensureOpen() {
        delegate().ensureOpen();
    }

    protected final ProjectManager delegate() {
        return delegate;
    }

    public static void validateProjectName(String name, boolean allowAdmin) {
        if (!isValidProjectName(name, allowAdmin)) {
            throw new IllegalArgumentException("Illegal access to project '" + name + '\'');
        }
    }

    protected static boolean isValidProjectName(String name, boolean allowAdmin) {
        if (name == null) {
            return false;
        }
        if (!INTERNAL_PROJECT_DOGMA.equals(name)) {
            return true;
        }

        if (!allowAdmin) {
            return false;
        }

        final User currentUserOrNull = AuthUtil.currentUserOrNull();
        if (currentUserOrNull == null) {
            return false;
        }

        return currentUserOrNull.isAdmin();
    }
}
