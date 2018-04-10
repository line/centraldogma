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

package com.linecorp.centraldogma.server.internal.command;

import static com.linecorp.centraldogma.server.internal.command.Command.createProject;
import static com.linecorp.centraldogma.server.internal.command.Command.createRepository;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.server.internal.storage.project.Project;

// TODO(trustin): Generate more useful set of sample files.
public final class ProjectInitializer {

    public static final String INTERNAL_PROJECT_NAME = "dogma";
    public static final String INTERNAL_REPOSITORY_NAME = "dogma";

    /**
     * Creates an internal project and repositories such as a token storage.
     */
    public static void initializeInternalProject(CommandExecutor executor) {
        try {
            executor.execute(createProject(Author.SYSTEM, INTERNAL_PROJECT_NAME))
                    .get();
        } catch (Throwable cause) {
            cause = Exceptions.peel(cause);
            if (!(cause instanceof ProjectExistsException)) {
                throw new Error("failed to initialize an internal project", cause);
            }
        }
        // These repositories might be created when creating an internal project, but we try to create them
        // again here in order to make sure them exist because sometimes their names are changed.
        for (final String repo : ImmutableList.of(Project.REPO_META,
                                                  INTERNAL_REPOSITORY_NAME)) {
            try {
                executor.execute(createRepository(Author.SYSTEM, INTERNAL_PROJECT_NAME, repo))
                        .get();
            } catch (Throwable cause) {
                cause = Exceptions.peel(cause);
                if (!(cause instanceof RepositoryExistsException)) {
                    throw new Error(cause);
                }
            }
        }
    }

    private ProjectInitializer() {}
}
