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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

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
    void yamlFilesAreHandledLikeJson() throws InterruptedException {
        final BlockingQueue<String> localQueue = new LinkedBlockingQueue<>();
        final CentralDogma client = dogma.client();
        client.createProject("yaml-watch-test").join();
        client.createRepository("yaml-watch-test", "repo").join();
        final Project project = dogma.projectManager().get("yaml-watch-test");

        final XdsResourceWatchingService svc = new XdsResourceWatchingService(
                project, "xds.", Metrics.globalRegistry) {

            private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();

            @Override
            protected ScheduledExecutorService executor() {
                return exec;
            }

            @Override
            protected String pathPattern() {
                return "/**";
            }

            @Override
            protected void handleXdsResource(String path, JsonNode content, String groupName) {
                localQueue.add("handleXdsResource: " + path);
                localQueue.add("content.key=" + content.path("key").asText());
            }

            @Override
            protected void onGroupRemoved(String groupName) {
                localQueue.add(groupName + " removed");
            }

            @Override
            protected void onFileRemoved(String groupName, String path) {
                localQueue.add(path + " removed");
            }

            @Override
            protected void onDiffHandled(String groupName) {
                localQueue.add("diff handled: " + groupName);
            }

            @Override
            protected boolean isStopped() {
                return false;
            }
        };
        svc.init();

        // YAML upsert should trigger handleXdsResource with the content parsed as JsonNode.
        client.forRepo("yaml-watch-test", "repo")
              .commit("Add YAML file", Change.ofYamlUpsert("/c.yaml", "key: value"))
              .push().join();
        assertThat(localQueue.take()).isEqualTo("handleXdsResource: /c.yaml");
        assertThat(localQueue.take()).isEqualTo("content.key=value");
        assertThat(localQueue.take()).isEqualTo("diff handled: repo");

        // Updating an existing YAML file should also pass updated content as JsonNode.
        client.forRepo("yaml-watch-test", "repo")
              .commit("Update YAML file", Change.ofYamlUpsert("/c.yaml", "key: updated"))
              .push().join();
        assertThat(localQueue.take()).isEqualTo("handleXdsResource: /c.yaml");
        assertThat(localQueue.take()).isEqualTo("content.key=updated");
        assertThat(localQueue.take()).isEqualTo("diff handled: repo");

        // Removing a YAML file should trigger onFileRemoved.
        client.forRepo("yaml-watch-test", "repo")
              .commit("Remove YAML file", Change.ofRemoval("/c.yaml"))
              .push().join();
        assertThat(localQueue.take()).isEqualTo("/c.yaml removed");
        assertThat(localQueue.take()).isEqualTo("diff handled: repo");
    }

    @Test
    void yamlFilesLoadedDuringInit() throws InterruptedException {
        final BlockingQueue<String> localQueue = new LinkedBlockingQueue<>();
        final CentralDogma client = dogma.client();
        client.createProject("yaml-init-test").join();
        client.createRepository("yaml-init-test", "repo").join();

        // Push YAML files BEFORE calling init() so they are picked up during the initial scan.
        client.forRepo("yaml-init-test", "repo")
              .commit("Seed YAML files",
                      Change.ofYamlUpsert("/x.yaml", "a: 1"),
                      Change.ofYamlUpsert("/y.yaml", "b: 2"))
              .push().join();

        final Project project = dogma.projectManager().get("yaml-init-test");
        final XdsResourceWatchingService svc = new XdsResourceWatchingService(
                project, "xds.", Metrics.globalRegistry) {

            private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();

            @Override
            protected ScheduledExecutorService executor() {
                return exec;
            }

            @Override
            protected String pathPattern() {
                return "/**";
            }

            @Override
            protected void handleXdsResource(String path, JsonNode content, String groupName) {
                // Record path and its first field value to verify YAML content arrives as JsonNode.
                final String firstValue =
                        content.fields().hasNext() ? content.fields().next().getValue().asText() : "";
                localQueue.add("handleXdsResource: " + path + " val=" + firstValue);
            }

            @Override
            protected void onGroupRemoved(String groupName) {}

            @Override
            protected void onFileRemoved(String groupName, String path) {}

            @Override
            protected void onDiffHandled(String groupName) {}

            @Override
            protected boolean isStopped() {
                return false;
            }
        };
        svc.init();

        // Both YAML files should have been loaded during init with their content as JsonNode.
        final List<String> loaded = new ArrayList<>();
        loaded.add(localQueue.poll(2, TimeUnit.SECONDS));
        loaded.add(localQueue.poll(2, TimeUnit.SECONDS));
        assertThat(loaded).containsExactlyInAnyOrder("handleXdsResource: /x.yaml val=1",
                                                     "handleXdsResource: /y.yaml val=2");
    }

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
        assertThat(queue.take()).isEqualTo("diff handled: bar");

        client.createRepository("foo", "baz").join();
        client.forRepo("foo", "baz").commit("Add a file", Change.ofJsonUpsert("/b.json", "1"))
              .push().join();
        assertThat(queue.take()).isEqualTo("handleXdsResource: /b.json");
        assertThat(queue.take()).isEqualTo("diff handled: baz");

        client.forRepo("foo", "baz").commit("Update the file", Change.ofJsonUpsert("/b.json", "2"))
              .push().join();
        assertThat(queue.take()).isEqualTo("handleXdsResource: /b.json");
        assertThat(queue.take()).isEqualTo("diff handled: baz");

        client.forRepo("foo", "bar").commit("Remove a file", Change.ofRemoval("/a.json"))
              .push().join();
        assertThat(queue.take()).isEqualTo("/a.json removed");
        assertThat(queue.take()).isEqualTo("diff handled: bar");
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
        protected void handleXdsResource(String path, JsonNode content, String groupName)
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
        protected void onDiffHandled(String groupName) {
            queue.add("diff handled: " + groupName);
        }

        @Override
        protected boolean isStopped() {
            return false;
        }
    }
}
