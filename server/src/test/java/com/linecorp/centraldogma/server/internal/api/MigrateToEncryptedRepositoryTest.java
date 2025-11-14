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

package com.linecorp.centraldogma.server.internal.api;

import static com.linecorp.centraldogma.server.internal.storage.MigratingMetaToDogmaRepositoryService.META_TO_DOGMA_MIGRATION_JOB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.EncryptionAtRestConfig;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.RocksDbRepository;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MigrateToEncryptedRepositoryTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.encryptionAtRest(new EncryptionAtRestConfig(true, false, "kekId"));
        }

        @Override
        protected void scaffold(CentralDogma client) {
            // Commit the META_TO_DOGMA_MIGRATION_JOB file to the dogma/dogma repository so that
            // a meta repository is not created when creating a project.
            // This will be removed once meta repository migration is done.
            final Project project = projectManager().get(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA);
            project.repos().get(Project.REPO_DOGMA).commit(
                    Revision.HEAD, 0, Author.SYSTEM, "Add",
                    Change.ofJsonUpsert(META_TO_DOGMA_MIGRATION_JOB, "{ \"a\": \"b\" }")).join();

            client.createProject("foo").join();
            client.createRepository("foo", "bar")
                  .join()
                  .commit("commit2", ImmutableList.of(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }")))
                  .push()
                  .join();

            client.forRepo("foo", "bar")
                  .commit("commit3", ImmutableList.of(Change.ofJsonUpsert("/bar.json", "{ \"a\": \"c\" }")))
                  .push()
                  .join();
        }
    };

    @Test
    void migrate() {
        final Repository oldRepository = dogma.projectManager().get("foo").repos().get("bar");
        assertThat(oldRepository.isEncrypted()).isFalse();
        assertThat(oldRepository.jGitRepository()).isInstanceOf(FileRepository.class);
        assertThat(oldRepository.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(3));

        final CentralDogmaRepository centralDogmaRepository = dogma.client().forRepo("foo", "bar");
        final Map<String, Entry<?>> oldRevision2 = getFiles(centralDogmaRepository, 2);
        final Map<String, Entry<?>> oldRevision3 = getFiles(centralDogmaRepository, 3);
        assertThat(oldRevision2).hasSize(1);
        assertThat(oldRevision2.get("/foo.json").contentAsText()).isEqualTo("{\"a\":\"b\"}");
        assertThat(oldRevision3).hasSize(2);
        assertThat(oldRevision3.get("/foo.json").contentAsText()).isEqualTo("{\"a\":\"b\"}");
        assertThat(oldRevision3.get("/bar.json").contentAsText()).isEqualTo("{\"a\":\"c\"}");

        final CompletableFuture<? extends Entry<?>> watch = centralDogmaRepository.watch("/foo.json")
                                                                                  .start();
        final BlockingWebClient client = dogma.blockingHttpClient();
        final AggregatedHttpResponse response =
                client.post("/api/v1/projects/foo/repos/bar/migrate/encrypted", "{}");
        assertThat(response.status()).isSameAs(HttpStatus.OK);

        // Watch fails when the migration happens.
        assertThatThrownBy(watch::join)
                .hasCauseInstanceOf(CentralDogmaException.class)
                .hasMessageContaining("foo/bar is migrated to an encrypted repository. Try again.");

        final Repository encrypted = dogma.projectManager().get("foo").repos().get("bar");
        assertThat(encrypted.jGitRepository()).isInstanceOf(RocksDbRepository.class);
        assertThat(encrypted.isEncrypted()).isTrue();
        assertThat(encrypted.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(3));

        // The content of the files should be the same as before migration.
        assertThat(getFiles(centralDogmaRepository, 2)).isEqualTo(oldRevision2);
        assertThat(getFiles(centralDogmaRepository, 3)).isEqualTo(oldRevision3);

        centralDogmaRepository.commit("commit4",
                                      ImmutableList.of(Change.ofJsonUpsert("/baz.json", "{ \"a\": \"d\" }")))
                              .push()
                              .join();

        // Even though the repository is closed, we can still query the normalizeNow.
        assertThat(oldRepository.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(3));
        assertThat(encrypted.normalizeNow(Revision.HEAD)).isEqualTo(new Revision(4));

        // Cannot migrate again.
        assertThat(client.post("/api/v1/projects/foo/repos/bar/migrate/encrypted", "{}").status())
                .isSameAs(HttpStatus.BAD_REQUEST);
    }

    private static Map<String, Entry<?>> getFiles(
            CentralDogmaRepository centralDogmaRepository, int revision) {
        return centralDogmaRepository.file(PathPattern.all())
                                     .get(new Revision(revision))
                                     .join();
    }
}
