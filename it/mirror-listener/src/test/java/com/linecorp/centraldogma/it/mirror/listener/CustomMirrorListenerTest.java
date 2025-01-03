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

package com.linecorp.centraldogma.it.mirror.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.internal.mirror.AbstractMirror;
import com.linecorp.centraldogma.server.internal.mirror.DefaultMirrorAccessController;
import com.linecorp.centraldogma.server.internal.mirror.MirrorAccessControl;
import com.linecorp.centraldogma.server.internal.mirror.MirrorSchedulingService;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorAccessController;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorStatus;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.testing.internal.CrudRepositoryExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class CustomMirrorListenerTest {

    private static final Cron EVERY_SECOND = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)).parse("* * * * * ?");

    @TempDir
    static File temporaryFolder;

    @RegisterExtension
    static CrudRepositoryExtension<MirrorAccessControl> repositoryExtension =
            new CrudRepositoryExtension<>(MirrorAccessControl.class, "dogma", "dogma",
                                          "mirror_access_control");

    @BeforeEach
    void setUp() {
        TestMirrorListener.reset();
    }

    @AfterEach
    void tearDown() {
        TestMirrorListener.reset();
    }

    @Test
    void shouldNotifyMirrorEvents() {
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

        final Mirror mirror = new AbstractMirror("my-mirror-1", true, EVERY_SECOND,
                                                 MirrorDirection.REMOTE_TO_LOCAL,
                                                 Credential.FALLBACK, r, "/",
                                                 URI.create("unused://uri"), "/", "", null, null) {
            @Override
            protected MirrorResult mirrorLocalToRemote(File workDir, int maxNumFiles, long maxNumBytes,
                                                       Instant triggeredTime) {
                throw new UnsupportedOperationException();
            }

            @Override
            protected MirrorResult mirrorRemoteToLocal(File workDir, CommandExecutor executor,
                                                       int maxNumFiles, long maxNumBytes, Instant triggeredTime)
                    throws Exception {
                final int counter = taskCounter.incrementAndGet();
                if (counter == 1) {
                    return newMirrorResult(MirrorStatus.SUCCESS, "1", Instant.now());
                } else if (counter == 2) {
                    return newMirrorResult(MirrorStatus.UP_TO_DATE, "2", Instant.now());
                } else {
                    throw new IllegalStateException("failed");
                }
            }
        };

        when(mr.mirrors()).thenReturn(CompletableFuture.completedFuture(ImmutableList.of(mirror)));

        final MirrorAccessController ac =
                new DefaultMirrorAccessController(repositoryExtension.crudRepository());
        final MirrorSchedulingService service = new MirrorSchedulingService(
                temporaryFolder, pm, new SimpleMeterRegistry(), 1, 1, 1, null,
                ac);
        final CommandExecutor executor = mock(CommandExecutor.class);
        service.start(executor);

        try {
            await().until(() -> taskCounter.get() >= 3);
        } finally {
            service.stop();
        }
        final Integer startCount = TestMirrorListener.startCount.get(mirror);
        assertThat(startCount).isGreaterThanOrEqualTo(3);

        final List<MirrorResult> completions = TestMirrorListener.completions.get(mirror);
        assertThat(completions).hasSize(2);
        assertThat(completions.get(0).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        assertThat(completions.get(1).mirrorStatus()).isEqualTo(MirrorStatus.UP_TO_DATE);

        final List<Throwable> errors = TestMirrorListener.errors.get(mirror);
        assertThat(errors).hasSizeGreaterThanOrEqualTo(1);
        assertThat(errors.get(0).getCause())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed");
    }
}
