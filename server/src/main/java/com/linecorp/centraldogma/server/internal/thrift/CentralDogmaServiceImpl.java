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
package com.linecorp.centraldogma.server.internal.thrift;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static com.linecorp.centraldogma.common.Author.SYSTEM;
import static com.linecorp.centraldogma.server.internal.thrift.Converter.convert;
import static com.spotify.futures.CompletableFutures.allAsList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import org.apache.thrift.async.AsyncMethodCallback;

import com.linecorp.centraldogma.common.QueryResult;
import com.linecorp.centraldogma.internal.thrift.Author;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaConstants;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaException;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService;
import com.linecorp.centraldogma.internal.thrift.Change;
import com.linecorp.centraldogma.internal.thrift.Comment;
import com.linecorp.centraldogma.internal.thrift.DiffFileResult;
import com.linecorp.centraldogma.internal.thrift.Entry;
import com.linecorp.centraldogma.internal.thrift.ErrorCode;
import com.linecorp.centraldogma.internal.thrift.GetFileResult;
import com.linecorp.centraldogma.internal.thrift.NamedQuery;
import com.linecorp.centraldogma.internal.thrift.Plugin;
import com.linecorp.centraldogma.internal.thrift.Project;
import com.linecorp.centraldogma.internal.thrift.Query;
import com.linecorp.centraldogma.internal.thrift.Revision;
import com.linecorp.centraldogma.internal.thrift.Schema;
import com.linecorp.centraldogma.internal.thrift.WatchFileResult;
import com.linecorp.centraldogma.internal.thrift.WatchRepositoryResult;
import com.linecorp.centraldogma.server.internal.api.WatchService;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.FindOption;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

public class CentralDogmaServiceImpl implements CentralDogmaService.AsyncIface {

    private static final Map<FindOption<?>, Object> LIST_FILES_FIND_OPTIONS = new IdentityHashMap<>();

    static {
        LIST_FILES_FIND_OPTIONS.put(FindOption.FETCH_CONTENT, false);
    }

    private final ProjectManager projectManager;
    private final CommandExecutor executor;
    private final WatchService watchService;

    public CentralDogmaServiceImpl(ProjectManager projectManager, CommandExecutor executor,
                                   WatchService watchService) {
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.executor = requireNonNull(executor, "executor");
        this.watchService = requireNonNull(watchService, "watchService");
    }

    private static void handle(CompletableFuture<?> future, AsyncMethodCallback resultHandler) {
        future.handle((res, cause) -> {
            if (cause != null) {
                resultHandler.onError(convert(cause));
            } else {
                resultHandler.onComplete(res);
            }
            return null;
        });
    }

    private static void handle(Callable<?> task, AsyncMethodCallback resultHandler) {
        try {
            resultHandler.onComplete(task.call());
        } catch (Throwable cause) {
            resultHandler.onError(convert(cause));
        }
    }

    @Override
    public void createProject(String name, AsyncMethodCallback resultHandler) {
        handle(executor.execute(Command.createProject(SYSTEM, name)), resultHandler);
    }

    @Override
    public void removeProject(String name, AsyncMethodCallback resultHandler) {
        handle(executor.execute(Command.removeProject(SYSTEM, name)), resultHandler);
    }

    @Override
    public void unremoveProject(String name, AsyncMethodCallback resultHandler) {
        handle(executor.execute(Command.unremoveProject(SYSTEM, name)), resultHandler);
    }

    @Override
    public void listProjects(AsyncMethodCallback resultHandler) {
        handle(() -> {
            final Map<String, com.linecorp.centraldogma.server.internal.storage.project.Project> projects =
                    projectManager.list();
            final List<Project> ret = new ArrayList<>(projects.size());
            projects.forEach((key, value) -> ret.add(convert(key, value)));
            return ret;
        }, resultHandler);
    }

    @Override
    public void listRemovedProjects(AsyncMethodCallback resultHandler) {
        handle(projectManager::listRemoved, resultHandler);
    }

    @Override
    public void createRepository(
            String projectName, String repositoryName, AsyncMethodCallback resultHandler) {
        handle(executor.execute(Command.createRepository(SYSTEM, projectName, repositoryName)),
               resultHandler);
    }

    @Override
    public void removeRepository(
            String projectName, String repositoryName, AsyncMethodCallback resultHandler) {
        handle(executor.execute(Command.removeRepository(SYSTEM, projectName, repositoryName)), resultHandler);
    }

    @Override
    public void unremoveRepository(
            String projectName, String repositoryName, AsyncMethodCallback resultHandler) {
        handle(executor.execute(Command.unremoveRepository(SYSTEM, projectName, repositoryName)),
               resultHandler);
    }

    @Override
    public void listRepositories(String projectName, AsyncMethodCallback resultHandler) {
        handle(allAsList(projectManager.get(projectName).repos().list().entrySet().stream()
                                       .map(e -> convert(e.getKey(), e.getValue()))
                                       .collect(toList())),
               resultHandler);
    }

    @Override
    public void listRemovedRepositories(String projectName, AsyncMethodCallback resultHandler) {
        handle(() -> projectManager.get(projectName).repos().listRemoved(), resultHandler);
    }

    @Override
    public void normalizeRevision(String projectName, String repositoryName, Revision revision,
                                  AsyncMethodCallback resultHandler) {

        handle(projectManager.get(projectName).repos().get(repositoryName)
                             .normalize(convert(revision))
                             .thenApply(Converter::convert),
               resultHandler);
    }

    @Override
    public void listFiles(String projectName, String repositoryName, Revision revision, String pathPattern,
                          AsyncMethodCallback resultHandler) {

        handle(projectManager.get(projectName).repos().get(repositoryName)
                             .find(convert(revision), pathPattern, LIST_FILES_FIND_OPTIONS)
                             .thenApply(entries -> {
                                 final List<Entry> ret = new ArrayList<>(entries.size());
                                 entries.forEach((path, entry) -> ret.add(
                                         new Entry(path, convert(entry.type()))));
                                 return ret;
                             }),
               resultHandler);
    }

    @Override
    public void getFiles(String projectName, String repositoryName, Revision revision, String pathPattern,
                         AsyncMethodCallback resultHandler) {

        handle(projectManager.get(projectName).repos().get(repositoryName)
                             .find(convert(revision), pathPattern)
                             .thenApply(entries -> {
                                 final List<Entry> ret = new ArrayList<>(entries.size());
                                 ret.addAll(entries.entrySet().stream()
                                                   .map(e -> convert(e.getValue()))
                                                   .collect(toList()));
                                 return ret;
                             }),
               resultHandler);
    }

    @Override
    public void getHistory(String projectName, String repositoryName, Revision from, Revision to,
                           String pathPattern, AsyncMethodCallback resultHandler) {

        handle(projectManager.get(projectName).repos().get(repositoryName)
                             .history(convert(from), convert(to), pathPattern)
                             .thenApply(commits -> commits.stream()
                                                          .map(Converter::convert)
                                                          .collect(toList())),
               resultHandler);
    }

    @Override
    public void getDiffs(String projectName, String repositoryName, Revision from, Revision to,
                         String pathPattern, AsyncMethodCallback resultHandler) {

        handle(projectManager.get(projectName).repos().get(repositoryName)
                             .diff(convert(from), convert(to), pathPattern)
                             .thenApply(diffs -> convert(diffs.values(), Converter::convert)),
               resultHandler);
    }

    @Override
    public void getPreviewDiffs(String projectName, String repositoryName, Revision baseRevision,
                                List<Change> changes, AsyncMethodCallback resultHandler) {

        handle(projectManager.get(projectName).repos().get(repositoryName)
                             .previewDiff(convert(baseRevision), convert(changes, Converter::convert))
                             .thenApply(diffs -> convert(diffs.values(), Converter::convert)),
               resultHandler);
    }

    @Override
    public void push(String projectName, String repositoryName, Revision baseRevision, Author author,
                     String summary, Comment detail, List<Change> changes, AsyncMethodCallback resultHandler) {

        // TODO(trustin): Change Repository.commit() to return a Commit.
        handle(executor.execute(Command.push(convert(author), projectName, repositoryName,
                                             convert(baseRevision), summary, detail.getContent(),
                                             convert(detail.getMarkup()), convert(changes, Converter::convert)))
                       .thenCompose(newRev -> projectManager.get(projectName).repos().get(repositoryName)
                                                            .history(newRev, newRev, "/**"))
                       .thenApply(commits -> convert(commits.get(0))),
               resultHandler);
    }

    @Override
    public void getFile(String projectName, String repositoryName, Revision revision, Query query,
                        AsyncMethodCallback resultHandler) {

        handle(projectManager.get(projectName).repos().get(repositoryName)
                             .get(convert(revision), convert(query))
                             .thenApply(res -> new GetFileResult(convert(res.type()), res.contentAsText())),
               resultHandler);
    }

    @Override
    public void diffFile(String projectName, String repositoryName, Revision from, Revision to, Query query,
                         AsyncMethodCallback resultHandler) {

        // FIXME(trustin): Remove the firstNonNull() on the change content once we make it optional.
        handle(projectManager.get(projectName).repos().get(repositoryName)
                             .diff(convert(from), convert(to), convert(query))
                             .thenApply(change -> new DiffFileResult(convert(change.type()),
                                                                     firstNonNull(change.contentAsText(), ""))),
               resultHandler);
    }

    @Override
    public void watchRepository(
            String projectName, String repositoryName, Revision lastKnownRevision,
            String pathPattern, long timeoutMillis, AsyncMethodCallback resultHandler) {

        if (watchService.isServerStopping()) {
            resultHandler.onError(new CentralDogmaException(ErrorCode.SHUTTING_DOWN));
            return;
        }

        final Repository repo = projectManager.get(projectName).repos().get(repositoryName);
        final CompletableFuture<com.linecorp.centraldogma.common.Revision> future =
                watchService.watchRepository(repo, convert(lastKnownRevision), pathPattern, timeoutMillis);
        handleWatchRepositoryResult(future, resultHandler);
    }

    private void handleWatchRepositoryResult(
            CompletableFuture<com.linecorp.centraldogma.common.Revision> future,
            AsyncMethodCallback resultHandler) {
        future.handle(voidFunction((res, cause) -> {
            if (cause == null) {
                final WatchRepositoryResult wrr = new WatchRepositoryResult();
                wrr.setRevision(convert(res));
                resultHandler.onComplete(wrr);
            } else if (cause instanceof CancellationException) {
                if (watchService.isServerStopping()) {
                    resultHandler.onError(new CentralDogmaException(ErrorCode.SHUTTING_DOWN));
                } else {
                    resultHandler.onComplete(CentralDogmaConstants.EMPTY_WATCH_REPOSITORY_RESULT);
                }
            } else {
                logAndInvokeOnError("watchRepository", resultHandler, cause);
            }
        }));
    }

    @Override
    public void watchFile(
            String projectName, String repositoryName, Revision lastKnownRevision,
            Query query, long timeoutMillis, AsyncMethodCallback resultHandler) {
        final Repository repo = projectManager.get(projectName).repos().get(repositoryName);
        final CompletableFuture<QueryResult<Object>> future =
                watchService.watchFile(repo, convert(lastKnownRevision), convert(query), timeoutMillis);

        handleWatchFileResult(future, resultHandler);
    }

    private void handleWatchFileResult(
            CompletableFuture<QueryResult<Object>> future, AsyncMethodCallback resultHandler) {
        future.handle(voidFunction((res, cause) -> {
            if (cause == null) {
                final WatchFileResult wfr = new WatchFileResult();
                wfr.setRevision(convert(res.revision()));
                wfr.setType(convert(res.type()));
                wfr.setContent(res.contentAsText());
                resultHandler.onComplete(wfr);
            } else if (cause instanceof CancellationException) {
                if (watchService.isServerStopping()) {
                    resultHandler.onError(new CentralDogmaException(ErrorCode.SHUTTING_DOWN));
                } else {
                    resultHandler.onComplete(CentralDogmaConstants.EMPTY_WATCH_FILE_RESULT);
                }
            } else {
                logAndInvokeOnError("watchFile", resultHandler, cause);
            }
        }));
    }

    private static void logAndInvokeOnError(
            String operationName, AsyncMethodCallback resultHandler, Throwable cause) {
        final CentralDogmaException cde = convert(cause);
        CentralDogmaExceptions.log(operationName, cde);
        resultHandler.onError(cde);
    }

    // Unimplemented methods

    @Override
    public void getSchema(String projectName, AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    @Override
    public void saveSchema(String projectName, Schema schema, AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    @Override
    public void getNamedQuery(String projectName, String name, AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    @Override
    public void saveNamedQuery(String projectName, NamedQuery namedQuery, AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    @Override
    public void removeNamedQuery(String projectName, String name, AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    @Override
    public void listNamedQueries(String projectName, AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    @Override
    public void getPlugin(String projectName, String pluginName, AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    @Override
    public void savePlugin(String projectName, Plugin plugin, AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    @Override
    public void removePlugin(String projectName, String pluginName, AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    @Override
    public void listPlugins(String projectName, AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    @Override
    public void listPluginOperations(String projectName, AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    @Override
    public void performPluginOperation(String projectName, String pluginName, String operationName,
                                       String params, AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    @Override
    public void queryByNamedQuery(String projectName, String namedQuery, Revision revision,
                                  AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    @Override
    public void listSubscribers(String projectName, String repositoryName, String path,
                                AsyncMethodCallback resultHandler) {
        unimplemented(resultHandler);
    }

    private static void unimplemented(AsyncMethodCallback resultHandler) {
        resultHandler.onError(new CentralDogmaException(ErrorCode.UNIMPLEMENTED));
    }
}
