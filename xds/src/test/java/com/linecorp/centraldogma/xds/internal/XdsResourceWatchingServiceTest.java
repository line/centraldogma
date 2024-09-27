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
package com.linecorp.centraldogma.xds.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.micrometer.core.instrument.Metrics;

class XdsResourceWatchingServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    private static final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

    @Test
    void foo() throws InterruptedException {
        final CentralDogma client = dogma.client();
        client.createProject("foo").join();
        client.createRepository("foo", "bar").join();
        final Project project = dogma.projectManager().get("foo");
        final TestXdsResourceWatchingService watchingService = new TestXdsResourceWatchingService(project);
        watchingService.init();
        client.forRepo("foo", "bar").commit("Add a file", Change.ofJsonUpsert("/a.json", "1"))
              .push().join();
        assertThat(queue.take()).isEqualTo("handleXdsResource: /a.json");

        client.createRepository("foo", "baz").join();
        client.forRepo("foo", "baz").commit("Add a file", Change.ofJsonUpsert("/b.json", "1"))
              .push().join();
        assertThat(queue.take()).isEqualTo("handleXdsResource: /b.json");

        client.forRepo("foo", "baz").commit("Update the file", Change.ofJsonUpsert("/b.json", "2"))
              .push().join();
        assertThat(queue.take()).isEqualTo("handleXdsResource: /b.json");

        client.forRepo("foo", "bar").commit("Remove a file", Change.ofRemoval("/a.json"))
              .push().join();
        assertThat(queue.take()).isEqualTo("/a.json removed");
        client.removeRepository("foo", "bar").join();
        assertThat(queue.take()).isEqualTo("bar removed");
    }

    private static class TestXdsResourceWatchingService extends XdsResourceWatchingService {

        private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        TestXdsResourceWatchingService(Project project) {
            super(project, "xds.", Metrics.globalRegistry);
        }

        @Override
        protected ScheduledExecutorService executor() {
            return executor;
        }

        @Override
        protected String pathPattern() {
            return "/**";
        }

        @Override
        protected void handleXdsResource(String path, String contentAsText, String groupName)
                throws IOException {
            queue.add("handleXdsResource: " + path);
        }

        @Override
        protected void onGroupRemoved(String groupName) {
            queue.add(groupName + " removed");
        }

        @Override
        protected void onFileRemoved(String groupName, String path) {
            queue.add(path + " removed");
        }

        @Override
        protected void onDiffHandled() {}

        @Override
        protected boolean isStopped() {
            return false;
        }
    }
}
