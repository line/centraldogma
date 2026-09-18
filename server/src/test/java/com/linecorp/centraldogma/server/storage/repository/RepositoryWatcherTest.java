/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.centraldogma.server.storage.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.project.Project;

class RepositoryWatcherTest {

    private static final Revision LAST_KNOWN_REVISION = new Revision(10);
    private static final Revision RECOVERY_REVISION = new Revision(11);
    private static final Query<String> QUERY = Query.ofText("/foo.txt");

    @Test
    void returnsRecoveredResultWhenStartedAtHeadEvenIfContentDidNotChange() {
        final Repository repository = mock(Repository.class);
        final Entry<String> oldEntry =
                Entry.of(LAST_KNOWN_REVISION, QUERY.path(), EntryType.TEXT, "same");
        final Entry<String> recovered =
                Entry.of(RECOVERY_REVISION, QUERY.path(), EntryType.TEXT, "same");
        when(repository.lastRecoveryRevision()).thenReturn(RECOVERY_REVISION);
        when(repository.normalizeNow(Revision.HEAD))
                .thenReturn(LAST_KNOWN_REVISION, RECOVERY_REVISION);
        when(repository.getOrNull(eq(LAST_KNOWN_REVISION), eq(QUERY), any()))
                .thenReturn(CompletableFuture.completedFuture(oldEntry));
        when(repository.watch(LAST_KNOWN_REVISION, QUERY.path(), false))
                .thenReturn(CompletableFuture.completedFuture(RECOVERY_REVISION));
        when(repository.getOrNull(eq(RECOVERY_REVISION), eq(QUERY), any()))
                .thenReturn(CompletableFuture.completedFuture(recovered));

        final Entry<String> result = new RepositoryWatcher<>(
                repository, Revision.HEAD, QUERY, false, null, null, null).watch().join();

        assertThat(result).isSameAs(recovered);
        verify(repository).watch(LAST_KNOWN_REVISION, QUERY.path(), false);
    }

    @Test
    void keepsWatchingWhenContentDidNotChangeWithoutRecovery() {
        final Repository repository = mock(Repository.class);
        final Entry<String> oldEntry =
                Entry.of(LAST_KNOWN_REVISION, QUERY.path(), EntryType.TEXT, "same");
        final Entry<String> unchanged =
                Entry.of(RECOVERY_REVISION, QUERY.path(), EntryType.TEXT, "same");
        final CompletableFuture<Revision> nextWatch = new CompletableFuture<>();
        when(repository.lastRecoveryRevision()).thenReturn(Revision.INIT);
        when(repository.getOrNull(eq(LAST_KNOWN_REVISION), eq(QUERY), any()))
                .thenReturn(CompletableFuture.completedFuture(oldEntry));
        when(repository.watch(LAST_KNOWN_REVISION, QUERY.path(), false))
                .thenReturn(CompletableFuture.completedFuture(RECOVERY_REVISION));
        when(repository.getOrNull(eq(RECOVERY_REVISION), eq(QUERY), any()))
                .thenReturn(CompletableFuture.completedFuture(unchanged));
        when(repository.watch(RECOVERY_REVISION, QUERY.path(), false)).thenReturn(nextWatch);

        final CompletableFuture<Entry<String>> result = new RepositoryWatcher<>(
                repository, LAST_KNOWN_REVISION, QUERY, false, null, null, null).watch();

        assertThat(result).isNotDone();
        verify(repository).watch(RECOVERY_REVISION, QUERY.path(), false);
    }

    @Test
    void doesNotChaseANewerRecoveryBeforeReturning() {
        final Repository repository = mock(Repository.class);
        final Revision nextRecoveryRevision = RECOVERY_REVISION.forward(1);
        final Entry<String> oldEntry =
                Entry.of(LAST_KNOWN_REVISION, QUERY.path(), EntryType.TEXT, "same");
        final Entry<String> firstRecovery =
                Entry.of(RECOVERY_REVISION, QUERY.path(), EntryType.TEXT, "same");
        when(repository.lastRecoveryRevision()).thenReturn(nextRecoveryRevision);
        when(repository.getOrNull(eq(LAST_KNOWN_REVISION), eq(QUERY), any()))
                .thenReturn(CompletableFuture.completedFuture(oldEntry));
        when(repository.watch(LAST_KNOWN_REVISION, QUERY.path(), false))
                .thenReturn(CompletableFuture.completedFuture(RECOVERY_REVISION));
        when(repository.normalizeNow(Revision.HEAD)).thenReturn(nextRecoveryRevision);
        when(repository.getOrNull(eq(RECOVERY_REVISION), eq(QUERY), any()))
                .thenReturn(CompletableFuture.completedFuture(firstRecovery));

        final Entry<String> result = new RepositoryWatcher<>(
                repository, LAST_KNOWN_REVISION, QUERY, false, null, null, null).watch().join();

        assertThat(result).isSameAs(firstRecovery);
    }

    @Test
    void refreshesWhenTemplateRecoveryWakesAnActiveWatchWithoutChangingContent() {
        final Repository repository = mock(Repository.class);
        final Repository dogmaRepository = mock(Repository.class);
        final Project project = mock(Project.class);
        final RepositoryManager repositoryManager = mock(RepositoryManager.class);
        final Revision oldTemplateRevision = new Revision(20);
        final Revision templateRecoveryRevision = new Revision(21);
        final EntryTransformer<String> transformer = EntryTransformer.identity();
        @SuppressWarnings("unchecked")
        final Function<Revision, EntryTransformer<String>> transformerFactory = mock(Function.class);
        final Entry<String> oldEntry = Entry.of(
                LAST_KNOWN_REVISION, QUERY.path(), EntryType.TEXT, "same", oldTemplateRevision);
        final Entry<String> refreshed = Entry.of(
                LAST_KNOWN_REVISION, QUERY.path(), EntryType.TEXT, "same", templateRecoveryRevision);
        final CompletableFuture<Revision> repositoryWatch = new CompletableFuture<>();

        when(repository.parent()).thenReturn(project);
        when(project.repos()).thenReturn(repositoryManager);
        when(repositoryManager.get(Project.REPO_DOGMA)).thenReturn(dogmaRepository);
        when(repository.lastRecoveryRevision()).thenReturn(Revision.INIT);
        when(dogmaRepository.lastRecoveryRevision()).thenReturn(templateRecoveryRevision);
        when(transformerFactory.apply(oldTemplateRevision)).thenReturn(transformer);
        when(transformerFactory.apply(templateRecoveryRevision)).thenReturn(transformer);
        when(repository.watch(LAST_KNOWN_REVISION, "/foo.txt,/**/.variables.*", false))
                .thenReturn(repositoryWatch);
        when(dogmaRepository.watch(oldTemplateRevision, "/**/variables/**/*.json"))
                .thenReturn(CompletableFuture.completedFuture(templateRecoveryRevision));
        when(repository.normalizeNow(Revision.HEAD)).thenReturn(LAST_KNOWN_REVISION);
        when(dogmaRepository.normalizeNow(Revision.HEAD)).thenReturn(templateRecoveryRevision);
        when(repository.getOrNull(LAST_KNOWN_REVISION, QUERY, transformer))
                .thenReturn(CompletableFuture.completedFuture(oldEntry),
                            CompletableFuture.completedFuture(refreshed));

        final Entry<String> result = new RepositoryWatcher<>(
                repository, LAST_KNOWN_REVISION, QUERY, false, null, oldTemplateRevision,
                transformerFactory).watch().join();

        assertThat(result).isSameAs(refreshed);
    }
}
