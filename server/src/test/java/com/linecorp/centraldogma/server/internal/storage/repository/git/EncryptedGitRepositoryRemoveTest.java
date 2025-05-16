/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.R_HEADS_MASTER;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.jgit.lib.ObjectReader.OBJ_ANY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.RocksDbRepository;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageException;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class EncryptedGitRepositoryRemoveTest {

    private static final String PROJECT_NAME = "foo";
    private static final String REPO_NAME = "bar";

    @TempDir
    private static File rootDir;

    @Test
    void deleteRepository() throws Exception {
        final Project project = mock(Project.class);
        when(project.name()).thenReturn(PROJECT_NAME);
        final File projectDir = new File(rootDir, PROJECT_NAME);

        final EncryptionStorageManager encryptionStorageManager =
                EncryptionStorageManager.of(new File(rootDir, "rocksdb").toPath());
        final GitRepositoryManager gitRepositoryManager =
                new GitRepositoryManager(project, projectDir, commonPool(),
                                         MoreExecutors.directExecutor(), null, encryptionStorageManager);

        // Generate a WDEK and store it in the storage manager before creating the corresponding repository.
        encryptionStorageManager.storeWdek(PROJECT_NAME, REPO_NAME,
                                           encryptionStorageManager.generateWdek().join());
        final Repository repo =
                gitRepositoryManager.create(REPO_NAME, 0, Author.SYSTEM, true);
        final org.eclipse.jgit.lib.Repository repository = repo.jGitRepository();
        assertThat(repository).isInstanceOf(RocksDbRepository.class);
        final EncryptionGitStorage encryptionGitStorage =
                ((RocksDbRepository) repository).encryptionGitStorage();
        final byte[] rev2ShaMetadataKey = encryptionGitStorage.rev2ShaMetadataKey(Revision.INIT);
        assertThat(encryptionStorageManager.getMetadata(rev2ShaMetadataKey)).isNotNull();
        final ObjectId revisionObjectId = encryptionGitStorage.getRevisionObjectId(Revision.INIT);
        assertThat(revisionObjectId).isNotNull();

        final byte[] objectMetadataKey = encryptionGitStorage.objectMetadataKey(revisionObjectId);
        assertThat(encryptionStorageManager.getMetadata(objectMetadataKey)).isNotNull();
        final ObjectLoader objectLoader = encryptionGitStorage.getObject(revisionObjectId, OBJ_ANY);
        assertThat(objectLoader).isNotNull();
        assertThat(new String(objectLoader.getBytes())).contains("\"summary\" : \"Create a new repository\"");

        final byte[] refMetadataKey = encryptionGitStorage.refMetadataKey(
                R_HEADS_MASTER.getBytes(StandardCharsets.UTF_8));
        assertThat(encryptionStorageManager.getMetadata(refMetadataKey)).isNotNull();
        final Ref ref = encryptionGitStorage.readRef(R_HEADS_MASTER);
        assertThat(ref).isNotNull();
        assertThat(ref.getObjectId()).isEqualTo(revisionObjectId);
        assertThat(ref.getName()).isEqualTo(R_HEADS_MASTER);

        // Delete the repository.
        gitRepositoryManager.remove(REPO_NAME);
        gitRepositoryManager.markForPurge(REPO_NAME);

        // Now the keys do not exist anymore.
        assertThat(encryptionStorageManager.getMetadata(rev2ShaMetadataKey)).isNull();
        assertThat(encryptionStorageManager.getMetadata(objectMetadataKey)).isNull();
        assertThat(encryptionStorageManager.getMetadata(refMetadataKey)).isNull();

        assertThatThrownBy(() -> encryptionStorageManager.getDek(PROJECT_NAME, REPO_NAME))
                .isInstanceOf(EncryptionStorageException.class)
                .hasMessageContaining("WDEK of foo/bar does not exist");

        encryptionStorageManager.close();
    }
}
