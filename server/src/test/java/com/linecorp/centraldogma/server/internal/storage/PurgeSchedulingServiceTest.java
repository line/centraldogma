/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.storage;

import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.concurrent.Executor;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.PurgeProjectCommand;
import com.linecorp.centraldogma.server.command.PurgeRepositoryCommand;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.testing.internal.ProjectManagerRule;

public class PurgeSchedulingServiceTest {

    private static final String PROJA_ACTIVE = "proja";
    private static final String REPOA_REMOVED = "repoa";
    private static final String PROJB_REMOVED = "projb";
    private static final Author AUTHOR = Author.SYSTEM;
    private static final long MAX_REMOVED_REPOSITORY_AGE_MILLIS = 1;

    private PurgeSchedulingService service;
    private MetadataService metadataService;

    @Rule
    public final ProjectManagerRule rule = new ProjectManagerRule() {
        @Override
        protected ProjectManager newProjectManager(Executor repositoryWorker, Executor purgeWorker) {
            return spy(super.newProjectManager(repositoryWorker, unused -> { /* noop for test */}));
        }

        @Override
        protected CommandExecutor newCommandExecutor(ProjectManager projectManager, Executor worker) {
            return spy(super.newCommandExecutor(projectManager, worker));
        }

        @Override
        protected void afterExecutorStarted() {
            metadataService = new MetadataService(projectManager(), executor());

            executor().execute(Command.createProject(AUTHOR, PROJA_ACTIVE)).join();
            executor().execute(Command.createRepository(AUTHOR, PROJA_ACTIVE, REPOA_REMOVED)).join();
            metadataService.addRepo(AUTHOR, PROJA_ACTIVE, REPOA_REMOVED).join();
            executor().execute(Command.removeRepository(AUTHOR, PROJA_ACTIVE, REPOA_REMOVED)).join();
            metadataService.removeRepo(AUTHOR, PROJA_ACTIVE, REPOA_REMOVED).join();

            executor().execute(Command.createProject(AUTHOR, PROJB_REMOVED)).join();
            executor().execute(Command.removeProject(AUTHOR, PROJB_REMOVED)).join();
        }
    };

    @Before
    public void init() {
        service = new PurgeSchedulingService(rule.projectManager(),
                                             rule.purgeWorker(),
                                             MAX_REMOVED_REPOSITORY_AGE_MILLIS);
    }

    @Test
    public void testClear() throws InterruptedException {
        rule.executor().execute(Command.purgeRepository(AUTHOR, PROJA_ACTIVE, REPOA_REMOVED)).join();
        rule.executor().execute(Command.purgeProject(AUTHOR, PROJB_REMOVED)).join();

        service.start(rule.executor(), metadataService);
        verify(rule.projectManager()).purgeMarked();
        service.stop();
    }

    @Test
    public void testSchedule() throws InterruptedException {
        Thread.sleep(10); // let removed files be purged
        service.schedulePurgeData(rule.executor(), metadataService);
        verify(rule.executor()).execute(isA(PurgeProjectCommand.class));
        verify(rule.executor()).execute(isA(PurgeRepositoryCommand.class));
    }
}
