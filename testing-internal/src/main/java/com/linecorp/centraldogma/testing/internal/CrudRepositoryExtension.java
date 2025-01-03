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

package com.linecorp.centraldogma.testing.internal;

import static com.google.common.base.Preconditions.checkState;

import javax.annotation.Nullable;

import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.internal.storage.repository.CrudRepository;
import com.linecorp.centraldogma.server.internal.storage.repository.git.GitCrudRepository;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.testing.junit.AbstractAllOrEachExtension;

/**
 * An extension which provides a {@link CrudRepository} for testing.
 */
public class CrudRepositoryExtension<T> extends AbstractAllOrEachExtension {

    private final ProjectManagerExtension projectManagerExtension = new ProjectManagerExtension();
    private final Class<T> entityType;
    private final String projectName;
    private final String repoName;
    private final String targetPath;

    @Nullable
    private CrudRepository<T> crudRepository;

    /**
     * Creates a new instance.
     */
    public CrudRepositoryExtension(Class<? extends T> entityType, String projectName, String repoName,
                                   String targetPath) {
        //noinspection unchecked
        this.entityType = (Class<T>) entityType;
        this.projectName = projectName;
        this.repoName = repoName;
        this.targetPath = targetPath;
    }

    @Override
    protected final void before(ExtensionContext context) throws Exception {
        projectManagerExtension.before(context);

        final ProjectManager projectManager = projectManagerExtension.projectManager();
        final Project project = projectManager.create(projectName, Author.DEFAULT);
        project.repos().create(repoName, Author.DEFAULT);
        crudRepository = new GitCrudRepository<>(entityType, projectManagerExtension.executor(),
                                                 projectManager, projectName,
                                                 repoName, targetPath);
    }

    @Override
    protected final void after(ExtensionContext context) throws Exception {
        projectManagerExtension.after(context);
    }

    /**
     * Returns the {@link CrudRepository} which is created by this extension.
     */
    public final CrudRepository<T> crudRepository() {
        checkState(crudRepository != null, "crudRepository not initialized yet.");
        return crudRepository;
    }
}
