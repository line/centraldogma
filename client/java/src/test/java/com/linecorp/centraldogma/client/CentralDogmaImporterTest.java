package com.linecorp.centraldogma.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
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
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CentralDogmaRepository repo = new CentralDogmaRepository(
                dogma, "foo", "bar", scheduler, null);

        final Path tempDir = Files.createTempDirectory("test-root");
        final Path fooBar = tempDir.resolve("foo/bar");
        Files.createDirectories(fooBar);
        Path file = fooBar.resolve("hello.txt");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

        //when
        CompletableFuture<Void> ok = CompletableFuture.completedFuture(null);
        when(dogma.createProject("foo")).thenReturn(ok);
        when(dogma.createRepository(anyString(), anyString())).thenReturn(
                CompletableFuture.completedFuture(null));

        when(dogma.push(anyString(), anyString(),
                        any(), anyString(), anyString(), any(), anyCollection()))
                .thenReturn(CompletableFuture.completedFuture(
                        new PushResult(Revision.HEAD, System.currentTimeMillis())));

        repo.importDir(fooBar)        // project/repo path
            .join();                               // ← 내부에서 repo.importDir(dir) 호출

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

        // cleanUp
        scheduler.shutdownNow();
        removeCreatedDir(fooBar);
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

        // when
        final ImportResult importResult = repo.importDir(fooBar).join();

        // then
        //ImportDir() should ignore placeholders such as .gitkeep and .gitignore
        final ArgumentCaptor<Collection<Change<?>>> changeCaptor = ArgumentCaptor.forClass(Collection.class);

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

        // cleanup
        scheduler.shutdownNow();
        removeCreatedDir(tempDir);
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

        // when
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
}
