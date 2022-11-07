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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.mirror.Mirror;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

final class MirroringTask {

    private static List<Tag> generateTags(Mirror mirror) {
        return ImmutableList.of(
                Tag.of("direction", mirror.direction().name()),
                Tag.of("localRepo", mirror.localRepo().name()),
                Tag.of("localPath", mirror.localPath()));
    }

    // -1: failure, 1: success
    private static final Map<List<Tag>, AtomicLong> lastSuccess = new ConcurrentHashMap<>();

    private final MeterRegistry meterRegistry;
    private final Mirror mirror;
    private final List<Tag> tags;

    MirroringTask(Mirror mirror, MeterRegistry meterRegistry) {
        this.mirror = mirror;
        this.meterRegistry = meterRegistry;
        tags = generateTags(mirror);
        lastSuccess.putIfAbsent(tags, new AtomicLong());
        tryRegisterGauge();
    }

    // 1: success, -1: failure, NaN: not registered yet
    private Gauge tryRegisterGauge() {
        return Gauge.builder("mirroring.result", () -> lastSuccess.get(tags))
                    .tags(tags)
                    .register(meterRegistry);
    }

    void run(File workDir, CommandExecutor executor, int maxNumFiles, long maxNumBytes) {
        final AtomicLong result = lastSuccess.get(tags);
        try {
            meterRegistry.timer("mirroring.task", tags)
                         .record(() -> mirror.mirror(workDir, executor, maxNumFiles, maxNumBytes));
            result.set(1);
        } catch (Exception e) {
            result.set(-1);
            throw e;
        }
    }
}
