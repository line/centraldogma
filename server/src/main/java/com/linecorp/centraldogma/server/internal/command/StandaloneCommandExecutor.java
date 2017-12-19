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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import org.apache.shiro.session.mgt.SimpleSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.admin.authentication.CentralDogmaSecurityManager;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

public class StandaloneCommandExecutor extends AbstractCommandExecutor {

    private static final Logger logger = LoggerFactory.getLogger(StandaloneCommandExecutor.class);

    private final ProjectManager projectManager;
    private final CentralDogmaSecurityManager securityManager;
    private final Executor repositoryWorker;
    private volatile Runnable onReleaseLeadership;

    public StandaloneCommandExecutor(ProjectManager projectManager,
                                     @Nullable CentralDogmaSecurityManager securityManager,
                                     Executor repositoryWorker) {
        this("none", projectManager, securityManager, repositoryWorker);
    }

    public StandaloneCommandExecutor(String replicaId,
                                     ProjectManager projectManager,
                                     @Nullable CentralDogmaSecurityManager securityManager,
                                     Executor repositoryWorker) {
        super(replicaId);
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.securityManager = securityManager;
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");
    }

    @Override
    protected void doStart(@Nullable Runnable onTakeLeadership,
                           @Nullable Runnable onReleaseLeadership) {
        this.onReleaseLeadership = onReleaseLeadership;
        if (onTakeLeadership != null) {
            onTakeLeadership.run();
        }
    }

    @Override
    protected void doStop() {
        final Runnable onReleaseLeadership = this.onReleaseLeadership;
        if (onReleaseLeadership != null) {
            this.onReleaseLeadership = null;
            onReleaseLeadership.run();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> CompletableFuture<T> doExecute(String replicaId, Command<T> command) throws Exception {
        if (command instanceof CreateProjectCommand) {
            return (CompletableFuture<T>) createProject((CreateProjectCommand) command);
        }

        if (command instanceof RemoveProjectCommand) {
            return (CompletableFuture<T>) removeProject((RemoveProjectCommand) command);
        }

        if (command instanceof UnremoveProjectCommand) {
            return (CompletableFuture<T>) unremoveProject((UnremoveProjectCommand) command);
        }

        if (command instanceof CreateRepositoryCommand) {
            return (CompletableFuture<T>) createRepository((CreateRepositoryCommand) command);
        }

        if (command instanceof RemoveRepositoryCommand) {
            return (CompletableFuture<T>) removeRepository((RemoveRepositoryCommand) command);
        }

        if (command instanceof UnremoveRepositoryCommand) {
            return (CompletableFuture<T>) unremoveRepository((UnremoveRepositoryCommand) command);
        }

        if (command instanceof PushCommand) {
            return (CompletableFuture<T>) push((PushCommand) command);
        }

        if (command instanceof CreateSessionCommand) {
            return (CompletableFuture<T>) createSession(replicaId, (CreateSessionCommand) command);
        }

        if (command instanceof RemoveSessionCommand) {
            return (CompletableFuture<T>) removeSession(replicaId, (RemoveSessionCommand) command);
        }

        throw new UnsupportedOperationException(command.toString());
    }

    // Project operations

    private CompletableFuture<Void> createProject(CreateProjectCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.create(c.projectName(), c.timestamp());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> removeProject(RemoveProjectCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.remove(c.projectName());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> unremoveProject(UnremoveProjectCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.unremove(c.projectName());
            return null;
        }, repositoryWorker);
    }

    // Repository operations

    private CompletableFuture<Void> createRepository(CreateRepositoryCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.get(c.projectName()).repos().create(c.repositoryName(), c.timestamp());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> removeRepository(RemoveRepositoryCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.get(c.projectName()).repos().remove(c.repositoryName());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> unremoveRepository(UnremoveRepositoryCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.get(c.projectName()).repos().unremove(c.repositoryName());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Revision> push(PushCommand c) {
        return repo(c).commit(c.baseRevision(), c.timestamp(),
                              c.author(), c.summary(), c.detail(), c.markup(), c.changes());
    }

    private Repository repo(RepositoryCommand<?> c) {
        return projectManager.get(c.projectName()).repos().get(c.repositoryName());
    }

    private CompletableFuture<Void> createSession(String replicaId, CreateSessionCommand c) {
        if (securityManager == null) {
            // Security has been disabled for this replica.
            return CompletableFuture.completedFuture(null);
        }

        if (replicaId().equals(replicaId)) {
            // The session commands are used only for replication of session events.
            // We do not use them for local changes, because Shiro persisted them via SessionDAO already.
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            final SimpleSession session = c.session();
            try {
                securityManager.createSession(session);
            } catch (Throwable t) {
                logger.warn("Failed to replicate a session creation: {}", session, t);
            }
            return null;
        }, repositoryWorker); // Not really a repository update, but it will not hurt.
    }

    private CompletableFuture<Void> removeSession(String replicaId, RemoveSessionCommand c) {
        if (securityManager == null) {
            return CompletableFuture.completedFuture(null);
        }

        if (replicaId().equals(replicaId)) {
            // The session commands are used only for replication of session events.
            // We do not use them for local changes, because Shiro persisted them via SessionDAO already.
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            final String sessionId = c.sessionId();
            try {
                securityManager.removeSession(sessionId);
            } catch (Throwable t) {
                logger.warn("Failed to replicate a session removal: {}", sessionId, t);
            }
            return null;
        }, repositoryWorker); // Not really a repository update, but it will not hurt.
    }
}
