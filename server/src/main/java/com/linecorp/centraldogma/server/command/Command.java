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

package com.linecorp.centraldogma.server.command;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.auth.Session;

/**
 * A Central Dogma command which is used to manage projects and repositories.
 *
 * @param <T> the result type of a {@link Command}
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @Type(value = CreateProjectCommand.class, name = "CREATE_PROJECT"),
        @Type(value = RemoveProjectCommand.class, name = "REMOVE_PROJECT"),
        @Type(value = UnremoveProjectCommand.class, name = "UNREMOVE_PROJECT"),
        @Type(value = CreateRepositoryCommand.class, name = "CREATE_REPOSITORY"),
        @Type(value = RemoveRepositoryCommand.class, name = "REMOVE_REPOSITORY"),
        @Type(value = UnremoveRepositoryCommand.class, name = "UNREMOVE_REPOSITORY"),
        @Type(value = PushCommand.class, name = "PUSH"),
        @Type(value = SaveNamedQueryCommand.class, name = "SAVE_NAMED_QUERY"),
        @Type(value = RemoveNamedQueryCommand.class, name = "REMOVE_NAMED_QUERY"),
        @Type(value = SavePluginCommand.class, name = "SAVE_PLUGIN"),
        @Type(value = RemovePluginCommand.class, name = "REMOVE_PLUGIN"),
        @Type(value = CreateSessionCommand.class, name = "CREATE_SESSIONS"),
        @Type(value = RemoveSessionCommand.class, name = "REMOVE_SESSIONS"),
})
public interface Command<T> {

    /**
     * Returns a new {@link Command} which is used to create a new project.
     *
     * @param author the author who is creating the project
     * @param name the name of the project which is supposed to be created
     */
    static Command<Void> createProject(Author author, String name) {
        return createProject(null, author, name);
    }

    /**
     * Returns a new {@link Command} which is used to create a new project.
     *
     * @param timestamp the creation time of the project, in milliseconds
     * @param author the author who is creating the project
     * @param name the name of the project which is supposed to be created
     */
    static Command<Void> createProject(@Nullable Long timestamp, Author author, String name) {
        requireNonNull(author, "author");
        return new CreateProjectCommand(timestamp, author, name);
    }

    /**
     * Returns a new {@link Command} which is used to remove a project.
     *
     * @param author the author who is removing the project
     * @param name the name of the project which is supposed to be removed
     */
    static Command<Void> removeProject(Author author, String name) {
        return removeProject(null, author, name);
    }

    /**
     * Returns a new {@link Command} which is used to remove a project.
     *
     * @param timestamp the removal time of the project, in milliseconds
     * @param author the author who is removing the project
     * @param name the name of the project which is supposed to be removed
     */
    static Command<Void> removeProject(@Nullable Long timestamp, Author author, String name) {
        requireNonNull(author, "author");
        return new RemoveProjectCommand(timestamp, author, name);
    }

    /**
     * Returns a new {@link Command} which is used to restore a project that was removed before.
     *
     * @param author the author who is restoring the project
     * @param name the name of the project which is supposed to be restored
     */
    static Command<Void> unremoveProject(Author author, String name) {
        return unremoveProject(null, author, name);
    }

    /**
     * Returns a new {@link Command} which is used to restore a project that was removed before.
     *
     * @param timestamp the restoration time of the project, in milliseconds
     * @param author the author who is restoring the project
     * @param name the name of the project which is supposed to be restored
     */
    static Command<Void> unremoveProject(@Nullable Long timestamp, Author author, String name) {
        requireNonNull(author, "author");
        return new UnremoveProjectCommand(timestamp, author, name);
    }

    /**
     * Returns a new {@link Command} which is used to create a new repository.
     *
     * @param author the author who is creating the repository
     * @param projectName the name of the project that the new repository is supposed to belong to
     * @param repositoryName the name of the repository which is supposed to be created
     */
    static Command<Void> createRepository(Author author, String projectName, String repositoryName) {
        return createRepository(null, author, projectName, repositoryName);
    }

    /**
     * Returns a new {@link Command} which is used to create a new repository.
     *
     * @param timestamp the creation time of the repository, in milliseconds
     * @param author the author who is creating the repository
     * @param projectName the name of the project that the new repository is supposed to belong to
     * @param repositoryName the name of the repository which is supposed to be created
     */
    static Command<Void> createRepository(@Nullable Long timestamp, Author author,
                                          String projectName, String repositoryName) {
        requireNonNull(author, "author");
        return new CreateRepositoryCommand(timestamp, author, projectName, repositoryName);
    }

    /**
     * Returns a new {@link Command} which is used to remove a repository.
     *
     * @param author the author who is removing the repository
     * @param projectName the name of the project
     * @param repositoryName the name of the repository which is supposed to be removed
     */
    static Command<Void> removeRepository(Author author, String projectName, String repositoryName) {
        return removeRepository(null, author, projectName, repositoryName);
    }

    /**
     * Returns a new {@link Command} which is used to remove a repository.
     *
     * @param timestamp the removal time of the repository, in milliseconds
     * @param author the author who is removing the repository
     * @param projectName the name of the project
     * @param repositoryName the name of the repository which is supposed to be removed
     */
    static Command<Void> removeRepository(@Nullable Long timestamp, Author author,
                                          String projectName, String repositoryName) {
        requireNonNull(author, "author");
        return new RemoveRepositoryCommand(timestamp, author, projectName, repositoryName);
    }

    /**
     * Returns a new {@link Command} which is used to restore a repository that was removed before.
     *
     * @param author the author who is restoring the repository
     * @param projectName the name of the project
     * @param repositoryName the name of the repository which is supposed to be restored
     */
    static Command<Void> unremoveRepository(Author author, String projectName, String repositoryName) {
        return unremoveRepository(null, author, projectName, repositoryName);
    }

    /**
     * Returns a new {@link Command} which is used to restore a repository that was removed before.
     *
     * @param timestamp the restoration time of the project, in milliseconds
     * @param author the author who is restoring the repository
     * @param projectName the name of the project
     * @param repositoryName the name of the repository which is supposed to be restored
     */
    static Command<Void> unremoveRepository(@Nullable Long timestamp, Author author,
                                            String projectName, String repositoryName) {
        requireNonNull(author, "author");
        return new UnremoveRepositoryCommand(timestamp, author, projectName, repositoryName);
    }

    /**
     * Returns a new {@link Command} which is used to push the changes.
     *
     * @param author the author who is pushing the changes
     * @param projectName the name of the project
     * @param repositoryName the name of the repository which is supposed to be restored
     * @param baseRevision the revision which is supposed to apply the changes
     * @param summary the summary of the changes
     * @param detail the detail message of the changes
     * @param markup the markup for the detail message
     * @param changes the changes to be applied
     */
    static Command<Revision> push(Author author, String projectName, String repositoryName,
                                  Revision baseRevision, String summary, String detail,
                                  Markup markup, Change<?>... changes) {

        return push(null, author, projectName, repositoryName, baseRevision, summary, detail, markup, changes);
    }

    /**
     * Returns a new {@link Command} which is used to push the changes.
     *
     * @param timestamp the time when pushing the changes, in milliseconds
     * @param author the author who is pushing the changes
     * @param projectName the name of the project
     * @param repositoryName the name of the repository which is supposed to be restored
     * @param baseRevision the revision which is supposed to apply the changes
     * @param summary the summary of the changes
     * @param detail the detail message of the changes
     * @param markup the markup for the detail message
     * @param changes the changes to be applied
     */
    static Command<Revision> push(@Nullable Long timestamp, Author author,
                                  String projectName, String repositoryName,
                                  Revision baseRevision, String summary, String detail,
                                  Markup markup, Change<?>... changes) {

        requireNonNull(author, "author");
        requireNonNull(changes, "changes");
        return new PushCommand(timestamp, author, projectName, repositoryName, baseRevision,
                               summary, detail, markup, Arrays.asList(changes));
    }

    /**
     * Returns a new {@link Command} which is used to push the changes.
     *
     * @param author the author who is pushing the changes
     * @param projectName the name of the project
     * @param repositoryName the name of the repository which is supposed to be restored
     * @param baseRevision the revision which is supposed to apply the changes
     * @param summary the summary of the changes
     * @param detail the detail message of the changes
     * @param markup the markup for the detail message
     * @param changes the changes to be applied
     */
    static Command<Revision> push(Author author, String projectName, String repositoryName,
                                  Revision baseRevision, String summary, String detail,
                                  Markup markup, Iterable<Change<?>> changes) {

        return push(null, author, projectName, repositoryName, baseRevision, summary, detail, markup, changes);
    }

    /**
     * Returns a new {@link Command} which is used to push the changes.
     *
     * @param timestamp the time when pushing the changes, in milliseconds
     * @param author the author who is pushing the changes
     * @param projectName the name of the project
     * @param repositoryName the name of the repository which is supposed to be restored
     * @param baseRevision the revision which is supposed to apply the changes
     * @param summary the summary of the changes
     * @param detail the detail message of the changes
     * @param markup the markup for the detail message
     * @param changes the changes to be applied
     */
    static Command<Revision> push(@Nullable Long timestamp, Author author,
                                  String projectName, String repositoryName,
                                  Revision baseRevision, String summary, String detail,
                                  Markup markup, Iterable<Change<?>> changes) {

        requireNonNull(author, "author");
        return new PushCommand(timestamp, author, projectName, repositoryName, baseRevision,
                               summary, detail, markup, changes);
    }

    /**
     * Not implemented.
     */
    static Command<Void> saveNamedQuery(Author author, String projectName,
                                        String queryName, boolean enabled, String repositoryName,
                                        Query<?> query, String comment, Markup markup) {

        return saveNamedQuery(null, author, projectName,
                              queryName, enabled, repositoryName,
                              query, comment, markup);
    }

    /**
     * Not implemented.
     */
    static Command<Void> saveNamedQuery(@Nullable Long timestamp, Author author, String projectName,
                                        String queryName, boolean enabled, String repositoryName,
                                        Query<?> query, String comment, Markup markup) {

        requireNonNull(author, "author");
        return new SaveNamedQueryCommand(timestamp, author, projectName,
                                         queryName, enabled, repositoryName,
                                         query, comment, markup);
    }

    /**
     * Not implemented.
     */
    static Command<Void> removeNamedQuery(Author author, String projectName, String name) {
        return removeNamedQuery(null, author, projectName, name);
    }

    /**
     * Not implemented.
     */
    static Command<Void> removeNamedQuery(@Nullable Long timestamp, Author author,
                                          String projectName, String name) {
        requireNonNull(author, "author");
        return new RemoveNamedQueryCommand(timestamp, author, projectName, name);
    }

    /**
     * Not implemented.
     */
    static Command<Void> savePlugin(Author author, String projectName, String pluginName, String path) {
        return savePlugin(null, author, projectName, pluginName, path);
    }

    /**
     * Not implemented.
     */
    static Command<Void> savePlugin(@Nullable Long timestamp, Author author,
                                    String projectName, String pluginName, String path) {
        requireNonNull(author, "author");
        return new SavePluginCommand(timestamp, author, projectName, pluginName, path);
    }

    /**
     * Not implemented.
     */
    static Command<Void> removePlugin(Author author, String projectName, String pluginName) {
        return removePlugin(null, author, projectName, pluginName);
    }

    /**
     * Not implemented.
     */
    static Command<Void> removePlugin(@Nullable Long timestamp, Author author,
                                      String projectName, String pluginName) {
        requireNonNull(author, "author");
        return new RemovePluginCommand(timestamp, author, projectName, pluginName);
    }

    /**
     * Returns a new {@link Command} which is used to create a new session.
     *
     * @param session the session supposed to be created
     */
    static Command<Void> createSession(Session session) {
        return new CreateSessionCommand(null, null, session);
    }

    /**
     * Returns a new {@link Command} which is used to remove an existing session.
     *
     * @param sessionId the session ID supposed to be removed
     */
    static Command<Void> removeSession(String sessionId) {
        return new RemoveSessionCommand(null, null, sessionId);
    }

    /**
     * Returns the {@link CommandType} of the command.
     */
    CommandType type();

    /**
     * Returns the time when performing the command, in milliseconds.
     */
    @JsonProperty
    long timestamp();

    /**
     * Returns the author who initiated the command.
     */
    @JsonProperty
    Author author();

    /**
     * Returns the target that the command is supposed to affect, i.e. the project name for the commands
     * affecting to the project, or the project and repository names for the commands affecting to the
     * repository.
     */
    String executionPath();
}
