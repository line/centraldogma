/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.centraldogma.client.armeria;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.ImportResult;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.InvalidPushException;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ArmeriaCentralDogmaTest {

    @RegisterExtension
    static CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
        }
    };

    private static void cleanProjectAndRepo(CentralDogma centralDogma, String project, String repository)
            throws IOException {
        centralDogma.removeRepository(project, repository).exceptionally(ex -> null);
        centralDogma.purgeRepository(project, repository).exceptionally(ex -> null);

        centralDogma.removeProject(project).exceptionally(ex -> null);
        centralDogma.purgeProject(project).exceptionally(ex -> null);
    }

    private static void cleanDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> {
                     try {
                         Files.deleteIfExists(p);
                     } catch (IOException ignored) {
                     }
                 });
        }
        final Path projectRoot = dir.getParent();
        if (projectRoot != null && Files.exists(projectRoot)) {
            try {
                Files.deleteIfExists(projectRoot);
            } catch (IOException ignored) {
            }
        }
    }

    @Test
    void pushFileToMetaRepositoryShouldFail() throws UnknownHostException {
        final CentralDogma client = new ArmeriaCentralDogmaBuilder()
                .host(dogma.serverAddress().getHostString(), dogma.serverAddress().getPort())
                .build();

        assertThatThrownBy(() -> client.forRepo("foo", "meta")
                                       .commit("summary", Change.ofJsonUpsert("/bar.json", "{ \"a\": \"b\" }"))
                                       .push()
                                       .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(InvalidPushException.class);
    }

    @Test
    void pushMirrorsJsonFileToMetaRepository() throws UnknownHostException {
        final CentralDogma client = new ArmeriaCentralDogmaBuilder()
                .host(dogma.serverAddress().getHostString(), dogma.serverAddress().getPort())
                .build();

        final PushResult result = client.forRepo("foo", "meta")
                                        .commit("summary", Change.ofJsonUpsert("/mirrors/foo.json", "{}"))
                                        .push()
                                        .join();
        assertThat(result.revision().major()).isPositive();
    }

    @Test
    void importDir_createsProjectRepo_andImportsFile() throws IOException {
        final Random random = new Random();
        final String project = "foo" + random.nextInt(1000);
        final String repository = "bar";
        final String fileName = "alice.txt";
        final String content = "world";
        final CentralDogma centralDogma = new ArmeriaCentralDogmaBuilder()
                .host(dogma.serverAddress().getHostString(), dogma.serverAddress().getPort())
                .build();

        final Path testRoot = Paths.get(project, repository);
        cleanDirectory(testRoot);
        Files.createDirectories(testRoot);

        final Path file = testRoot.resolve(fileName);
        Files.write(file, content.getBytes(UTF_8));

        try {
            final ImportResult importResult = centralDogma.importDir(Paths.get(project, repository, fileName))
                                                          .join();
            assertThat(importResult).isNotNull();
            assertThat(importResult.revision().major()).isPositive();
            assertThat(importResult.isEmpty()).isFalse();

            final Change<?> diff = centralDogma.forRepo(project, repository)
                                               .diff(Query.ofText('/' + fileName))
                                               .get(Revision.INIT, Revision.HEAD)
                                               .join();
            assertThat(diff.path()).isEqualTo('/' + fileName);
            assertThat(diff.contentAsText()).isEqualTo("world" + '\n');
        } finally {
            cleanProjectAndRepo(centralDogma, project, repository);
            cleanDirectory(testRoot);
        }
    }

    @Test
    void importResourceDir_createsProjectRepo_andImportsFile() throws IOException {
        final Random random = new Random();
        final String project = "foo" + random.nextInt(1000);
        final String repository = "bar";
        final String fileName = "hello.txt";
        final String content = "world";

        final CentralDogma centralDogma = new ArmeriaCentralDogmaBuilder()
                .host(dogma.serverAddress().getHostString(), dogma.serverAddress().getPort())
                .build();

        final Path tempRoot = Files.createTempDirectory("cp-");
        final Path fooBar = tempRoot.resolve(project).resolve(repository);
        cleanDirectory(tempRoot);
        Files.createDirectories(fooBar);
        Files.write(fooBar.resolve(fileName), content.getBytes(UTF_8));

        try (URLClassLoader cl = new URLClassLoader(new URL[] { tempRoot.toUri().toURL() },
                                                    Thread.currentThread().getContextClassLoader())) {

            final ImportResult result = centralDogma.importResourceDir(project + "/" + repository, cl)
                                                    .join();

            assertThat(result).isNotNull();
            assertThat(result.revision().major()).isPositive();
            assertThat(result.isEmpty()).isFalse();

            final Change<?> diff = centralDogma.forRepo(project, repository)
                                               .diff(Query.ofText('/' + fileName))
                                               .get(Revision.INIT, Revision.HEAD)
                                               .join();
            assertThat(diff.path()).isEqualTo('/' + fileName);
            assertThat(diff.content()).isEqualTo(content + '\n');
        } finally {
            cleanProjectAndRepo(centralDogma, project, repository);
            cleanDirectory(tempRoot);
        }
    }
}
