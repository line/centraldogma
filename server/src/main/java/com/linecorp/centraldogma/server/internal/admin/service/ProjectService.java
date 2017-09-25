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

package com.linecorp.centraldogma.server.internal.admin.service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.admin.dto.ProjectDto;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.SafeProjectManager;

/**
 * Annotated service object for managing projects.
 */
public class ProjectService extends AbstractService {

    public ProjectService(ProjectManager projectManager,
                          CommandExecutor executor) {
        super(new SafeProjectManager(projectManager), executor);
    }

    /**
     * GET /projects
     * Returns the list of the projects.
     */
    @Get("/projects")
    public CompletionStage<List<ProjectDto>> listProjects() {
        return CompletableFuture.supplyAsync(
                () -> projectManager().list().values().stream()
                                      .map(DtoConverter::convert)
                                      .collect(Collectors.toList()));
    }

    /**
     * POST /projects
     * Create a new project.
     */
    @Post("/projects")
    public CompletionStage<ProjectDto> createProject(AggregatedHttpMessage message) throws IOException {
        final ProjectDto dto =
                Jackson.readValue(message.content().toStringAscii(), ProjectDto.class);
        return execute(Command.createProject(dto.getName())).thenApply(unused -> dto);
    }
}
