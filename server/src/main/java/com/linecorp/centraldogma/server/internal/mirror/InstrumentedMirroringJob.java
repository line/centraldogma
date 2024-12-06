/*
 * Copyright 2022 LINE Corporation
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

import java.io.File;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorTask;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

final class InstrumentedMirroringJob {

    private static Iterable<Tag> generateTags(Mirror mirror, String projectName) {
        return ImmutableList.of(
                Tag.of("project", projectName),
                Tag.of("direction", mirror.direction().name()),
                Tag.of("remoteBranch", mirror.remoteBranch()),
                Tag.of("remotePath", mirror.remotePath()),
                Tag.of("localRepo", mirror.localRepo().name()),
                Tag.of("localPath", mirror.localPath()));
    }

    private final MeterRegistry meterRegistry;
    private final MirrorTask mirrorTask;
    private final Iterable<Tag> tags;

    InstrumentedMirroringJob(MirrorTask mirrorTask, MeterRegistry meterRegistry) {
        this.mirrorTask = mirrorTask;
        this.meterRegistry = meterRegistry;
        tags = generateTags(mirrorTask.mirror(), mirrorTask.mirror().localRepo().parent().name());
    }

    private Counter counter(boolean success) {
        return Counter.builder("mirroring.result")
                      .tags(tags)
                      .tag("success", Boolean.toString(success))
                      .register(meterRegistry);
    }

    MirrorResult run(File workDir, CommandExecutor executor, int maxNumFiles, long maxNumBytes) {
        try {
            final MirrorResult mirrorResult =
                    meterRegistry.timer("mirroring.task", tags)
                                 .record(() -> mirrorTask.mirror()
                                                         .mirror(workDir, executor, maxNumFiles, maxNumBytes,
                                                                 mirrorTask.triggeredTime()));
            counter(true).increment();
            return mirrorResult;
        } catch (Exception e) {
            counter(false).increment();
            throw e;
        }
    }
}
