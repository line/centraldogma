/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.mirror;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public class DefaultMirroringServiceTest {

    @ClassRule
    public static final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final Cron EVERY_SECOND = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)).parse("* * * * * ?");

    @Test
    public void mirroringTaskShouldNeverBeRejected() throws Exception {
        final AtomicInteger taskCounter = new AtomicInteger();
        final ProjectManager pm = mock(ProjectManager.class);
        final Project p = mock(Project.class);
        final MetaRepository mr = mock(MetaRepository.class);
        final Repository r = mock(Repository.class);
        when(pm.list()).thenReturn(ImmutableMap.of("foo", p));
        when(p.name()).thenReturn("foo");
        when(p.metaRepo()).thenReturn(mr);
        when(r.parent()).thenReturn(p);
        when(r.name()).thenReturn("bar");

        final Mirror mirror = new AbstractMirror(EVERY_SECOND, MirrorDirection.REMOTE_TO_LOCAL,
                                                 MirrorCredential.FALLBACK, r, "/",
                                                 URI.create("unused://uri"), "/", null) {
            @Override
            protected void mirrorLocalToRemote(File workDir, int maxNumFiles, long maxNumBytes) {}

            @Override
            protected void mirrorRemoteToLocal(File workDir, CommandExecutor executor,
                                               int maxNumFiles, long maxNumBytes) throws Exception {
                // Sleep longer than mirroring interval so that the workers fall behind.
                taskCounter.incrementAndGet();
                Thread.sleep(2000);
            }
        };

        when(mr.mirrors()).thenReturn(ImmutableSet.of(mirror));

        final DefaultMirroringService service =
                new DefaultMirroringService(temporaryFolder.getRoot(), pm, 1, 1, 1);
        service.start(mock(CommandExecutor.class));

        try {
            // The mirroring task should run more than once.
            await().until(() -> taskCounter.get() > 1);
        } finally {
            service.stop();
        }
    }
}
