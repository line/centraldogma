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

package com.linecorp.centraldogma.server.repository.git;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.util.concurrent.Executor;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.common.DirectoryBasedStorageManager;
import com.linecorp.centraldogma.server.common.StorageExistsException;
import com.linecorp.centraldogma.server.common.StorageNotFoundException;
import com.linecorp.centraldogma.server.project.Project;
import com.linecorp.centraldogma.server.repository.Repository;
import com.linecorp.centraldogma.server.repository.RepositoryExistsException;
import com.linecorp.centraldogma.server.repository.RepositoryManager;
import com.linecorp.centraldogma.server.repository.RepositoryNotFoundException;

public class GitRepositoryManager extends DirectoryBasedStorageManager<Repository>
                                  implements RepositoryManager {

    public GitRepositoryManager(Project parent, File rootDir, Executor repositoryWorker) {
        super(rootDir, Repository.class,
              requireNonNull(parent, "parent"),
              requireNonNull(repositoryWorker, "repositoryWorker"));
    }

    @Override
    protected Repository openChild(File childDir, Object[] childArgs) throws Exception {
        return new GitRepository((Project) childArgs[0], childDir, (Executor) childArgs[1]);
    }

    @Override
    protected Repository createChild(File childDir, Object[] childArgs) throws Exception {
        return new GitRepository((Project) childArgs[0], childDir, (Executor) childArgs[1], Author.SYSTEM);
    }

    @Override
    protected void closeChild(File childDir, Repository child) {
        ((GitRepository) child).close();
    }

    @Override
    protected StorageExistsException newStorageExistsException(String name) {
        return new RepositoryExistsException(name);
    }

    @Override
    protected StorageNotFoundException newStorageNotFoundException(String name) {
        return new RepositoryNotFoundException(name);
    }
}
