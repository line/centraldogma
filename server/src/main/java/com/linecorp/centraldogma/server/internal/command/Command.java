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

import static java.util.Objects.requireNonNull;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @Type(value = CreateProjectCommand.class, name = "CREATE_PROJECT"),
        @Type(value = RemoveProjectCommand.class, name = "REMOVE_PROJECT"),
        @Type(value = UnremoveProjectCommand.class, name = "UNREMOVE_PROJECT"),
        @Type(value = CreateRepositoryCommand.class, name = "CREATE_REPOSITORY"),
        @Type(value = RemoveRepositoryCommand.class, name = "REMOVE_REPOSITORY"),
        @Type(value = UnremoveRepositoryCommand.class, name = "UNREMOVE_REPOSITORY"),
        @Type(value = PushCommand.class, name = "PUSH"),
        @Type(value = CreateRunspaceCommand.class, name = "CREATE_RUNSPACE"),
        @Type(value = RemoveRunspaceCommand.class, name = "REMOVE_RUNSPACE"),
        @Type(value = SaveNamedQueryCommand.class, name = "SAVE_NAMED_QUERY"),
        @Type(value = RemoveNamedQueryCommand.class, name = "REMOVE_NAMED_QUERY"),
        @Type(value = SavePluginCommand.class, name = "SAVE_PLUGIN"),
        @Type(value = RemovePluginCommand.class, name = "REMOVE_PLUGIN"),
})
public interface Command<T> {

    static Command<Void> createProject(String name) {
        return new CreateProjectCommand(name);
    }

    static Command<Void> removeProject(String name) {
        return new RemoveProjectCommand(name);
    }

    static Command<Void> unremoveProject(String name) {
        return new UnremoveProjectCommand(name);
    }

    static Command<Void> createRepository(String projectName, String repositoryName) {
        return new CreateRepositoryCommand(projectName, repositoryName);
    }

    static Command<Void> removeRepository(String projectName, String repositoryName) {
        return new RemoveRepositoryCommand(projectName, repositoryName);
    }

    static Command<Void> unremoveRepository(String projectName, String repositoryName) {
        return new UnremoveRepositoryCommand(projectName, repositoryName);
    }

    static Command<Revision> push(String projectName, String repositoryName,
                                  Revision baseRevision, Author author, String summary, String detail,
                                  Markup markup, Change<?>... changes) {

        requireNonNull(changes, "changes");
        return new PushCommand(projectName, repositoryName,
                               baseRevision, author, summary, detail, markup, Arrays.asList(changes));
    }

    static Command<Revision> push(String projectName, String repositoryName,
                                Revision baseRevision, Author author, String summary, String detail,
                                Markup markup, Iterable<Change<?>> changes) {

        return new PushCommand(projectName, repositoryName,
                               baseRevision, author, summary, detail, markup, changes);
    }

    static Command<Void> createRunspace(String projectName, String repositoryName,
                                        Author author, int baseRevision) {

        return new CreateRunspaceCommand(projectName, repositoryName, author, baseRevision);
    }

    static Command<Void> removeRunspace(String projectName, String repositoryName, int baseRevision) {
        return new RemoveRunspaceCommand(projectName, repositoryName, baseRevision);
    }

    static Command<Void> saveNamedQuery(String projectName, String queryName, boolean enabled,
                                        String repositoryName, Query<?> query, String comment, Markup markup) {

        return new SaveNamedQueryCommand(projectName, queryName, enabled,
                                         repositoryName, query, comment, markup);
    }

    static Command<Void> removeNamedQuery(String projectName, String name) {
        return new RemoveNamedQueryCommand(projectName, name);
    }

    static Command<Void> savePlugin(String projectName, String pluginName, String path) {
        return new SavePluginCommand(projectName, pluginName, path);
    }

    static Command<Void> removePlugin(String projectName, String pluginName) {
        return new RemovePluginCommand(projectName, pluginName);
    }

    CommandType type();

    String executionPath();
}
