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
import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.ObjectReader.OBJ_ANY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.RocksDbRepository;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageException;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.encryption.WrappedDekDetails;
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
                EncryptionStorageManager.of(new File(rootDir, "rocksdb").toPath(), false, "kekId");
        final GitRepositoryManager gitRepositoryManager =
                new GitRepositoryManager(project, projectDir, commonPool(),
                                         MoreExecutors.directExecutor(), null, encryptionStorageManager);

        // Generate a WDEK and store it in the storage manager before creating the corresponding repository.
        final String wdek = encryptionStorageManager.generateWdek().join();
        final WrappedDekDetails wrappedDekDetails =
                new WrappedDekDetails(wdek, 1, encryptionStorageManager.kekId(),
                                      PROJECT_NAME, REPO_NAME);
        encryptionStorageManager.storeWdek(wrappedDekDetails);
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

        final Ref headRef = encryptionGitStorage.readRef(HEAD);
        assertThat(headRef).isNotNull();
        assertThat(headRef.getObjectId()).isEqualTo(revisionObjectId);
        assertThat(headRef.getName()).isEqualTo(HEAD);
        assertThat(headRef.getTarget().getObjectId()).isEqualTo(ref.getObjectId());

        int wdekSize = 2; // version 1 and current
        int encryptionMetadataSize = 5; // The sum of the following:
        int encryptedObjectIdSize = 3; // refs/heads/master, HEAD, revision 1 number
        int encryptedObjectSize = 2; // revision 1 tree and commit
        assertEntrySize(encryptionStorageManager, wdekSize,
                        encryptionMetadataSize, encryptedObjectIdSize, encryptedObjectSize);

        repo.commit(Revision.INIT, 0, Author.SYSTEM, "Add a file",
                    ImmutableList.of(Change.ofJsonUpsert("/foo.json", "{\"a:\":\"b\"}"))).join();

        encryptionMetadataSize = 9; // The sum of the following:
        encryptedObjectIdSize = 4; // refs/heads/master, HEAD, revision 1 number, revision 2 number
        encryptedObjectSize = 5; // revision 1 tree and commit, revision 2 tree and commit, foo.json
        assertEntrySize(encryptionStorageManager, wdekSize,
                        encryptionMetadataSize, encryptedObjectIdSize, encryptedObjectSize);

        final String wdek2 = encryptionStorageManager.generateWdek().join();
        final WrappedDekDetails wrappedDekDetails2 =
                new WrappedDekDetails(wdek2, 1, encryptionStorageManager.kekId(),
                                      PROJECT_NAME, "bar2");
        // Create another repository.
        encryptionStorageManager.storeWdek(wrappedDekDetails2);
        gitRepositoryManager.create("bar2", 0, Author.SYSTEM, true);

        wdekSize = 2 + 2;
        encryptionMetadataSize = 5 + 9;
        encryptedObjectIdSize = 3 + 4;
        encryptedObjectSize = 2 + 5;
        assertEntrySize(encryptionStorageManager, wdekSize,
                        encryptionMetadataSize, encryptedObjectIdSize, encryptedObjectSize);

        // Delete the repository.
        gitRepositoryManager.remove(REPO_NAME);
        gitRepositoryManager.markForPurge(REPO_NAME);

        // Now the keys do not exist anymore.
        assertThat(encryptionStorageManager.getMetadata(rev2ShaMetadataKey)).isNull();
        assertThat(encryptionStorageManager.getMetadata(objectMetadataKey)).isNull();
        assertThat(encryptionStorageManager.getMetadata(refMetadataKey)).isNull();

        assertThatThrownBy(() -> encryptionStorageManager.getCurrentDek(PROJECT_NAME, REPO_NAME))
                .isInstanceOf(EncryptionStorageException.class)
                .hasMessageContaining("WDEK of foo/bar does not exist");

        wdekSize = 2; // version 1 and current
        encryptionMetadataSize = 5; // The sum of the following:
        encryptedObjectIdSize = 3; // refs/heads/master, HEAD, revision 1 number
        encryptedObjectSize = 2; // revision 1 tree and commit
        assertEntrySize(encryptionStorageManager, wdekSize,
                        encryptionMetadataSize, encryptedObjectIdSize, encryptedObjectSize);

        // Delete the rest repository.
        gitRepositoryManager.remove("bar2");
        gitRepositoryManager.markForPurge("bar2");
        assertEntrySize(encryptionStorageManager, 0, 0, 0, 0);

        encryptionStorageManager.close();
    }

    private static void assertEntrySize(EncryptionStorageManager encryptionStorageManager, int wdekSize,
                                        int encryptionMetadataSize, int encryptedObjectIdSize,
                                        int encryptedObjectSize) {
        final Map<String, Map<String, byte[]>> allData = encryptionStorageManager.getAllData();
        assertThat(allData.get("wdek").size()).isEqualTo(wdekSize);
        assertThat(allData.get("encryption_metadata").size()).isEqualTo(encryptionMetadataSize);
        assertThat(allData.get("encrypted_object_id").size()).isEqualTo(encryptedObjectIdSize);
        assertThat(allData.get("encrypted_object").size()).isEqualTo(encryptedObjectSize);
    }
}
