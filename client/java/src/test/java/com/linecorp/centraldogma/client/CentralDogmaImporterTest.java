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
package com.linecorp.centraldogma.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Revision;

class CentralDogmaImporterTest {

    CentralDogma dogma = mock(CentralDogma.class);

    private static void removeCreatedDir(Path dir) throws IOException {
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
    void importDir_createsProjectRepo_andPushes() throws Exception {
        //given
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        final CentralDogmaRepository repo = new CentralDogmaRepository(
                dogma, "foo", "bar", scheduler, null);

        final Path tempDir = Files.createTempDirectory("test-root");
        final Path fooBar = tempDir.resolve("foo/bar");
        Files.createDirectories(fooBar);
        final Path file = fooBar.resolve("hello.txt");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

        //when
        final CompletableFuture<Void> ok = CompletableFuture.completedFuture(null);
        when(dogma.createProject("foo")).thenReturn(ok);
        when(dogma.createRepository(anyString(), anyString())).thenReturn(
                CompletableFuture.completedFuture(null));

        when(dogma.push(anyString(), anyString(),
                        any(), anyString(), anyString(), any(), anyCollection()))
                .thenReturn(CompletableFuture.completedFuture(
                        new PushResult(Revision.HEAD, System.currentTimeMillis())));

        try {
            repo.importDir(fooBar).join();

            //then
            verify(dogma).createProject("foo");
            verify(dogma).createRepository("foo", "bar");

            //verify file successfully imported
            ArgumentCaptor<Collection<Change<?>>> changeCaptor = ArgumentCaptor.forClass(Collection.class);

            verify(dogma).push(
                    eq("foo"), eq("bar"),
                    any(), anyString(), anyString(), any(), changeCaptor.capture());

            Collection<Change<?>> changes = changeCaptor.getValue();
            assertThat(changes).hasSize(1);

            Change<?> ch = changes.iterator().next();
            assertThat(ch.path()).isEqualTo("/hello.txt");
            assertThat(ch.type()).isEqualTo(ChangeType.UPSERT_TEXT);
            assertThat(ch.content()).isEqualTo("hello");
        } finally {
            scheduler.shutdownNow();
            removeCreatedDir(fooBar);
        }
    }

    @Test
    void importDir_shouldIgnoresPlaceholders() throws Exception {
        // given
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        final CentralDogmaRepository repo = new CentralDogmaRepository(
                dogma, "foo", "bar", scheduler, null);

        final Path tempDir = Files.createTempDirectory("test-ignore");
        final Path fooBar = tempDir.resolve("foo/bar");
        final Path boundaryFile = fooBar.resolve("hello.txt");

        // setUp Test Directory
        Files.createDirectories(fooBar);
        Files.write(fooBar.resolve(".gitkeep"), "".getBytes(StandardCharsets.UTF_8));
        Files.write(fooBar.resolve(".gitignore"), "".getBytes(StandardCharsets.UTF_8));
        Files.write(boundaryFile, "hello".getBytes(StandardCharsets.UTF_8));

        final CompletableFuture<Void> ok = CompletableFuture.completedFuture(null);
        when(dogma.createProject("foo")).thenReturn(CompletableFuture.completedFuture(null));
        when(dogma.createRepository(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(dogma.push(any(), any(), any(), any(), any(), any(), anyCollection()))
                .thenReturn(CompletableFuture.completedFuture(
                        new PushResult(Revision.HEAD, System.currentTimeMillis())));

        try {
            // when
            final ImportResult importResult = repo.importDir(fooBar).join();

            // then
            //ImportDir() should ignore placeholders such as .gitkeep and .gitignore
            final ArgumentCaptor<Collection<Change<?>>> changeCaptor = ArgumentCaptor.forClass(
                    Collection.class);

            verify(dogma).createProject("foo");
            verify(dogma).createRepository("foo", "bar");
            verify(dogma).push(
                    eq("foo"), eq("bar"),
                    any(), anyString(), anyString(), any(), changeCaptor.capture());

            final Collection<Change<?>> changes = changeCaptor.getValue();
            final List<String> importedPaths = changes.stream()
                                                      .map(Change::path)
                                                      .collect(Collectors.toList());

            assertThat(importedPaths).containsExactly("/hello.txt");
            assertThat(importedPaths).doesNotContain("/.gitkeep", "/.gitignore");
        } finally {
            scheduler.shutdownNow();
            removeCreatedDir(tempDir);
        }
    }

    @Test
    void importDir_onlyCreateRepositoryWhenFilesNotExists() throws IOException {
        // given
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        final CentralDogmaRepository repo = new CentralDogmaRepository(
                dogma, "foo", "bar", scheduler, null);

        // directory setUp
        final Path tempDir = Files.createTempDirectory("test-ignore");
        final Path fooBar = tempDir.resolve("foo/bar");
        Files.createDirectories(fooBar);

        // mock setUp
        when(dogma.createProject("foo")).thenReturn(CompletableFuture.completedFuture(null));
        when(dogma.createRepository(eq("foo"), eq("bar"))).thenReturn(CompletableFuture.completedFuture(null));
        try {
            final ImportResult result = repo.importDir(fooBar).join();

            // then
            verify(dogma).createProject("foo");
            verify(dogma).createRepository("foo", "bar");
            verify(dogma, never()).push(any(), any(), any(), any(), any(), any(), anyCollection());

        } finally {
            scheduler.shutdownNow();
            removeCreatedDir(tempDir);
        }
    }

    @Test
    void importResourceDir_createsAndPushes() throws IOException {
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        final Path tmpRoot = Files.createTempDirectory("classpath-");
        final Path fooBar = tmpRoot.resolve("foo/bar");
        Files.createDirectories(fooBar);
        Files.write(fooBar.resolve("hello.txt"), "hello".getBytes(UTF_8));

        try (URLClassLoader cl = new URLClassLoader(new URL[] { tmpRoot.toUri().toURL() },
                                                    Thread.currentThread().getContextClassLoader())) {

            when(dogma.createProject("foo")).thenReturn(CompletableFuture.completedFuture(null));
            when(dogma.createRepository("foo", "bar")).thenReturn(CompletableFuture.completedFuture(null));
            when(dogma.push(any(), any(), any(), any(), any(), any(), anyCollection()))
                    .thenReturn(CompletableFuture.completedFuture(
                            new PushResult(Revision.HEAD, System.currentTimeMillis())));

            final CentralDogmaRepository repo = new CentralDogmaRepository(
                    dogma, "foo", "bar", scheduler, null);

            final ImportResult importResult = repo.importResourceDir("foo/bar", cl).join();

            final ArgumentCaptor<Collection<Change<?>>> captor = ArgumentCaptor.forClass(
                    Collection.class);
            verify(dogma).createProject("foo");
            verify(dogma).createRepository("foo", "bar");
            verify(dogma).push(eq("foo"), eq("bar"), any(),
                               argThat(s -> s.contains("Import")), anyString(), any(), captor.capture());

            assertThat(captor.getValue())
                    .singleElement()
                    .extracting(Change::path, Change::content)
                    .containsExactly("/hello.txt", "hello");
        } finally {
            scheduler.shutdownNow();
            removeCreatedDir(tmpRoot);
        }
    }
}
