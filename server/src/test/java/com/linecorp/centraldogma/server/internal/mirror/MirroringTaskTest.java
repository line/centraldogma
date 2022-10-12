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

import static com.linecorp.centraldogma.server.internal.mirror.MirroringTestUtils.newMirror;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.centraldogma.server.mirror.Mirror;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MirroringTaskTest {

    @Test
    void testSuccessMetrics() {
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        Mirror mirror = newMirror("git://a.com/b.git", GitMirror.class, "foo", "bar");
        mirror = spy(mirror);
        doNothing().when(mirror).mirror(any(), any(), anyInt(), anyLong());
        new MirroringTask(mirror, meterRegistry).run(null, null, 0, 0L);
        assertThat(MoreMeters.measureAll(meterRegistry))
                .contains(entry("mirror.result#count{direction=LOCAL_TO_REMOTE,localPath=/," +
                                "localRepo=bar,remoteBranch=master,remotePath=/," +
                                "remoteRepo=git://a.com/b.git,success=true}", 1.0));
    }

    @Test
    void testFailureMetrics() {
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        Mirror mirror = newMirror("git://a.com/b.git", GitMirror.class, "foo", "bar");
        mirror = spy(mirror);
        final RuntimeException e = new RuntimeException();
        doThrow(e).when(mirror).mirror(any(), any(), anyInt(), anyLong());
        final MirroringTask task = new MirroringTask(mirror, meterRegistry);
        assertThatThrownBy(() -> task.run(null, null, 0, 0L))
                .isSameAs(e);
        assertThat(MoreMeters.measureAll(meterRegistry))
                .contains(entry("mirror.result#count{direction=LOCAL_TO_REMOTE,localPath=/," +
                                "localRepo=bar,remoteBranch=master,remotePath=/," +
                                "remoteRepo=git://a.com/b.git,success=false}", 1.0));
    }

    @Test
    void testTimerMetrics() {
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        Mirror mirror = newMirror("git://a.com/b.git", GitMirror.class, "foo", "bar");
        mirror = spy(mirror);
        doAnswer(invocation -> {
            Thread.sleep(1000);
            return null;
        }).when(mirror).mirror(any(), any(), anyInt(), anyLong());
        new MirroringTask(mirror, meterRegistry).run(null, null, 0, 0L);
        assertThat(MoreMeters.measureAll(meterRegistry))
                .hasEntrySatisfying(
                        "mirroring.task#total{direction=LOCAL_TO_REMOTE,localPath=/," +
                        "localRepo=bar,remoteBranch=master,remotePath=/," +
                        "remoteRepo=git://a.com/b.git}", v -> assertThat(v).isGreaterThanOrEqualTo(1));
    }
}
