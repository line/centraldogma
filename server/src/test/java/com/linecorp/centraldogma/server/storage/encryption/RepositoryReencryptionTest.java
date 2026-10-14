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
package com.linecorp.centraldogma.server.storage.encryption;

import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.aesSecretKey;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.ObjectReader.OBJ_ANY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher;
import com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryManager;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.RocksDbRepository;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

class RepositoryReencryptionTest {

    private static final String PROJECT_NAME = "foo";
    private static final String REPO_NAME = "bar";
    private static final String OTHER_REPO_NAME = "otherRepo";
    private static final String R_HEADS_MASTER = "refs/heads/master";

    @TempDir
    private File rootDir;

    private EncryptionStorageManager encryptionStorageManager;
    private GitRepositoryManager gitRepositoryManager;

    @BeforeEach
    void setUp() {
        final Project project = mock(Project.class);
        when(project.name()).thenReturn(PROJECT_NAME);
        final File projectDir = new File(rootDir, PROJECT_NAME);

        encryptionStorageManager = EncryptionStorageManager.of(
                new File(rootDir, "rocksdb").toPath(), false, "kekId");
        gitRepositoryManager = new GitRepositoryManager(
                project, projectDir, commonPool(), MoreExecutors.directExecutor(),
                null, encryptionStorageManager);
    }

    @AfterEach
    void tearDown() {
        if (encryptionStorageManager != null) {
            encryptionStorageManager.close();
        }
    }

    private Repository createRepository(String repoName) {
        final String wdek = encryptionStorageManager.generateWdek().join();
        final WrappedDekDetails wrappedDekDetails =
                new WrappedDekDetails(wdek, 1, encryptionStorageManager.kekId(),
                                      PROJECT_NAME, repoName);
        encryptionStorageManager.storeWdek(wrappedDekDetails);
        return gitRepositoryManager.create(repoName, 0, Author.SYSTEM, true);
    }

    private static EncryptionGitStorage getEncryptionGitStorage(Repository repo) {
        final org.eclipse.jgit.lib.Repository jGitRepo = repo.jGitRepository();
        assertThat(jGitRepo).isInstanceOf(RocksDbRepository.class);
        return ((RocksDbRepository) jGitRepo).encryptionGitStorage();
    }

    private static int getMetadataKeyVersion(byte[] metadata) {
        if (metadata == null || metadata.length < 4) {
            throw new IllegalArgumentException("Invalid metadata");
        }
        return ByteBuffer.wrap(metadata, 0, 4).getInt();
    }

    private static byte[] extractObjectDekBytes(byte[] objectMetadata, SecretKey repoDek) throws Exception {
        // Object metadata format: keyVersion(4) + nonce(12) + type(4) + wrappedObjectDek(48)
        if (objectMetadata == null || objectMetadata.length < 68) {
            throw new IllegalArgumentException("Invalid object metadata");
        }

        // Extract nonce (offset 4, length 12)
        final byte[] nonce = new byte[12];
        System.arraycopy(objectMetadata, 4, nonce, 0, 12);

        // Extract wrapped object DEK (offset 20, length 48)
        final byte[] wrappedObjectDek = new byte[48];
        System.arraycopy(objectMetadata, 20, wrappedObjectDek, 0, 48);

        // Unwrap the object DEK using repository DEK + nonce
        return AesGcmSivCipher.decrypt(repoDek, nonce, wrappedObjectDek);
    }

    @Test
    void shouldReencryptAllDataWithNewDek() throws Exception {
        final Repository repo = createRepository(REPO_NAME);
        final EncryptionGitStorage encryptionGitStorage = getEncryptionGitStorage(repo);

        repo.commit(Revision.INIT, 0, Author.SYSTEM, "Add files",
                   ImmutableList.of(
                           Change.ofJsonUpsert("/file1.json", "{\"key\":\"value1\"}"),
                           Change.ofJsonUpsert("/file2.json", "{\"key\":\"value2\"}")
                   )).join();

        // Verify initial state - all metadata should be version 1
        final byte[] rev1MetadataKey = encryptionGitStorage.rev2ShaMetadataKey(Revision.INIT);
        final byte[] rev2MetadataKey = encryptionGitStorage.rev2ShaMetadataKey(new Revision(2));
        final byte[] refMetadataKey = encryptionGitStorage.refMetadataKey(
                R_HEADS_MASTER.getBytes(StandardCharsets.UTF_8));
        final byte[] headMetadataKey = encryptionGitStorage.refMetadataKey(
                HEAD.getBytes(StandardCharsets.UTF_8));

        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(rev1MetadataKey)))
                .isEqualTo(1);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(rev2MetadataKey)))
                .isEqualTo(1);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(refMetadataKey)))
                .isEqualTo(1);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(headMetadataKey)))
                .isEqualTo(1);

        // Verify objects (commits, trees, blobs) are accessible
        final ObjectId rev1ObjectId = encryptionGitStorage.getRevisionObjectId(Revision.INIT);
        final ObjectId rev2ObjectId = encryptionGitStorage.getRevisionObjectId(new Revision(2));
        assertThat(rev1ObjectId).isNotNull();
        assertThat(rev2ObjectId).isNotNull();

        final ObjectLoader rev1Loader = encryptionGitStorage.getObject(rev1ObjectId, OBJ_ANY);
        final ObjectLoader rev2Loader = encryptionGitStorage.getObject(rev2ObjectId, OBJ_ANY);
        assertThat(rev1Loader).isNotNull();
        assertThat(rev2Loader).isNotNull();
        assertThat(new String(rev1Loader.getBytes())).contains("Create a new repository");
        assertThat(new String(rev2Loader.getBytes())).contains("Add files");

        // Extract object DEK bytes before re-encryption to verify they don't change
        final byte[] rev2ObjectMetadataKey = encryptionGitStorage.objectMetadataKey(rev2ObjectId);
        final byte[] rev2ObjectMetadataBefore = encryptionStorageManager.getMetadata(rev2ObjectMetadataKey);
        final SecretKey oldDekV1 = encryptionStorageManager.getDek(PROJECT_NAME, REPO_NAME, 1);
        final byte[] objectDekBytesBefore =
                extractObjectDekBytes(rev2ObjectMetadataBefore, oldDekV1);

        // Get encrypted object key and data BEFORE re-encryption
        // This will be used to verify that the encrypted object itself doesn't change
        final byte[] nonceBefore = new byte[12];
        System.arraycopy(rev2ObjectMetadataBefore, 4, nonceBefore, 0, 12);
        final SecretKeySpec objectDekBefore = aesSecretKey(objectDekBytesBefore);
        final byte[] rev2ObjectIdBytes = new byte[20];
        rev2ObjectId.copyRawTo(rev2ObjectIdBytes, 0);
        final byte[] encryptedObjectKeyBefore = AesGcmSivCipher.encrypt(
                objectDekBefore, nonceBefore, rev2ObjectIdBytes);
        final byte[] encryptedObjectDataBefore = encryptionStorageManager.getObject(
                encryptedObjectKeyBefore, rev2ObjectMetadataKey);

        // Get ref metadata and nonce
        final byte[] refMetadataBeforeV1 = encryptionStorageManager.getMetadata(refMetadataKey);
        final byte[] oldRefNonce = new byte[12];
        System.arraycopy(refMetadataBeforeV1, 4, oldRefNonce, 0, 12);
        final byte[] oldRefEncryptedKey = AesGcmSivCipher.encrypt(
                oldDekV1, oldRefNonce, R_HEADS_MASTER.getBytes(StandardCharsets.UTF_8));

        // Get HEAD metadata and nonce (HEAD has its own separate nonce)
        final byte[] headMetadataBeforeV1 = encryptionStorageManager.getMetadata(headMetadataKey);
        final byte[] oldHeadNonce = new byte[12];
        System.arraycopy(headMetadataBeforeV1, 4, oldHeadNonce, 0, 12);
        final byte[] oldHeadEncryptedKey = AesGcmSivCipher.encrypt(
                oldDekV1, oldHeadNonce, HEAD.getBytes(StandardCharsets.UTF_8));

        // Verify old encrypted ref data exists before re-encryption
        assertThat(encryptionStorageManager.getObjectId(oldRefEncryptedKey, refMetadataKey))
                .isNotNull();
        assertThat(encryptionStorageManager.getObjectId(oldHeadEncryptedKey, headMetadataKey))
                .isNotNull();

        // Rotate DEK to version 2
        final String rotatedWdek = encryptionStorageManager.generateWdek().join();
        final WrappedDekDetails rotatedWdekDetails =
                new WrappedDekDetails(rotatedWdek, 2, encryptionStorageManager.kekId(),
                                      PROJECT_NAME, REPO_NAME);
        encryptionStorageManager.rotateWdek(rotatedWdekDetails);

        assertThat(encryptionStorageManager.getCurrentDek(PROJECT_NAME, REPO_NAME).version())
                .isEqualTo(2);

        // Re-encrypt repository data
        encryptionStorageManager.reencryptRepositoryData(PROJECT_NAME, REPO_NAME);

        // Verify all metadata is now version 2
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(rev1MetadataKey)))
                .isEqualTo(2);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(rev2MetadataKey)))
                .isEqualTo(2);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(refMetadataKey)))
                .isEqualTo(2);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(headMetadataKey)))
                .isEqualTo(2);

        // Verify data is still accessible (objects remain unchanged, only wrapped DEK changed)
        final ObjectLoader rev1LoaderAfter = encryptionGitStorage.getObject(rev1ObjectId, OBJ_ANY);
        final ObjectLoader rev2LoaderAfter = encryptionGitStorage.getObject(rev2ObjectId, OBJ_ANY);
        assertThat(rev1LoaderAfter).isNotNull();
        assertThat(rev2LoaderAfter).isNotNull();
        assertThat(new String(rev1LoaderAfter.getBytes())).contains("Create a new repository");
        assertThat(new String(rev2LoaderAfter.getBytes())).contains("Add files");

        // Object DEK itself is not changed, only the wrapping is changed.
        final byte[] rev2ObjectMetadataAfter = encryptionStorageManager.getMetadata(rev2ObjectMetadataKey);
        final SecretKey newDekV2 = encryptionStorageManager.getDek(PROJECT_NAME, REPO_NAME, 2);
        final byte[] objectDekBytesAfter = extractObjectDekBytes(rev2ObjectMetadataAfter, newDekV2);
        assertThat(objectDekBytesAfter).isEqualTo(objectDekBytesBefore);

        // Since object DEK and nonce don't change, the encrypted object key should also remain the same
        final byte[] nonceAfter = new byte[12];
        System.arraycopy(rev2ObjectMetadataAfter, 4, nonceAfter, 0, 12);
        assertThat(nonceAfter).isEqualTo(nonceBefore);

        final SecretKeySpec objectDekAfter = aesSecretKey(objectDekBytesAfter);
        final byte[] encryptedObjectKeyAfter = AesGcmSivCipher.encrypt(
                objectDekAfter, nonceAfter, rev2ObjectIdBytes);
        assertThat(encryptedObjectKeyAfter).isEqualTo(encryptedObjectKeyBefore);

        // Since encrypted object key doesn't change,
        // the encrypted object data itself should also remain completely unchanged
        final byte[] encryptedObjectDataAfter = encryptionStorageManager.getObject(
                encryptedObjectKeyAfter, rev2ObjectMetadataKey);
        assertThat(encryptedObjectDataAfter)
                .isEqualTo(encryptedObjectDataBefore);

        // Verify refs are still accessible
        final Ref masterRef = encryptionGitStorage.readRef(R_HEADS_MASTER);
        final Ref headRef = encryptionGitStorage.readRef(HEAD);
        assertThat(masterRef).isNotNull();
        assertThat(headRef).isNotNull();
        assertThat(masterRef.getObjectId()).isEqualTo(rev2ObjectId);
        assertThat(headRef.getTarget().getObjectId()).isEqualTo(rev2ObjectId);

        // Old version 1 encrypted keys should be DELETED after re-encryption.
        // Since refs/HEAD/rev2sha are re-encrypted with new nonce and new DEK,
        // the old encrypted keys should no longer exist.
        assertThat(encryptionStorageManager.getObjectId(oldRefEncryptedKey, refMetadataKey))
                .isNull();
        assertThat(encryptionStorageManager.getObjectId(oldHeadEncryptedKey, headMetadataKey))
                .isNull();

        // Get new ref metadata and nonce after re-encryption
        final byte[] refMetadataAfter = encryptionStorageManager.getMetadata(refMetadataKey);
        final byte[] newRefNonce = new byte[12];
        System.arraycopy(refMetadataAfter, 4, newRefNonce, 0, 12);
        final byte[] newRefEncryptedKey = AesGcmSivCipher.encrypt(
                newDekV2, newRefNonce, R_HEADS_MASTER.getBytes(StandardCharsets.UTF_8));

        // Get new HEAD metadata and nonce after re-encryption (HEAD has its own separate nonce)
        final byte[] headMetadataAfter = encryptionStorageManager.getMetadata(headMetadataKey);
        final byte[] newHeadNonce = new byte[12];
        System.arraycopy(headMetadataAfter, 4, newHeadNonce, 0, 12);
        final byte[] newHeadEncryptedKey = AesGcmSivCipher.encrypt(
                newDekV2, newHeadNonce, HEAD.getBytes(StandardCharsets.UTF_8));

        // New encrypted keys should work
        assertThat(encryptionStorageManager.getObjectId(newRefEncryptedKey, refMetadataKey))
                .as("New ref encrypted data with version 2 should be accessible")
                .isNotNull();
        assertThat(encryptionStorageManager.getObjectId(newHeadEncryptedKey, headMetadataKey))
                .as("New HEAD encrypted data with version 2 should be accessible")
                .isNotNull();
    }

    @Test
    void shouldSkipAlreadyReencryptedData() throws Exception {
        final Repository repo = createRepository(REPO_NAME);
        final EncryptionGitStorage encryptionGitStorage = getEncryptionGitStorage(repo);

        repo.commit(Revision.INIT, 0, Author.SYSTEM, "Add file",
                   ImmutableList.of(Change.ofJsonUpsert("/test.json", "{\"test\":\"data\"}"))).join();

        final byte[] refMetadataKey = encryptionGitStorage.refMetadataKey(
                R_HEADS_MASTER.getBytes(StandardCharsets.UTF_8));

        // Rotate and re-encrypt
        final String rotatedWdek = encryptionStorageManager.generateWdek().join();
        final WrappedDekDetails rotatedWdekDetails =
                new WrappedDekDetails(rotatedWdek, 2, encryptionStorageManager.kekId(),
                                      PROJECT_NAME, REPO_NAME);
        encryptionStorageManager.rotateWdek(rotatedWdekDetails);
        encryptionStorageManager.reencryptRepositoryData(PROJECT_NAME, REPO_NAME);

        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(refMetadataKey)))
                .isEqualTo(2);

        // Re-run re-encryption
        encryptionStorageManager.reencryptRepositoryData(PROJECT_NAME, REPO_NAME);

        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(refMetadataKey))).isEqualTo(2);

        final Ref masterRef = encryptionGitStorage.readRef(R_HEADS_MASTER);
        assertThat(masterRef).isNotNull();
    }

    @Test
    void shouldOnlyAffectTargetRepository() throws Exception {
        final Repository targetRepo = createRepository(REPO_NAME);
        final Repository otherRepo = createRepository(OTHER_REPO_NAME);

        final EncryptionGitStorage targetStorage = getEncryptionGitStorage(targetRepo);
        final EncryptionGitStorage otherStorage = getEncryptionGitStorage(otherRepo);

        // Add commits to both repositories
        targetRepo.commit(Revision.INIT, 0, Author.SYSTEM, "Target commit",
                         ImmutableList.of(Change.ofJsonUpsert("/target.json", "{}"))).join();
        otherRepo.commit(Revision.INIT, 0, Author.SYSTEM, "Other commit",
                        ImmutableList.of(Change.ofJsonUpsert("/other.json", "{}"))).join();

        final byte[] targetRefKey = targetStorage.refMetadataKey(
                R_HEADS_MASTER.getBytes(StandardCharsets.UTF_8));
        final byte[] otherRefKey = otherStorage.refMetadataKey(
                R_HEADS_MASTER.getBytes(StandardCharsets.UTF_8));

        // Both should be version 1
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(targetRefKey)))
                .isEqualTo(1);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(otherRefKey)))
                .isEqualTo(1);

        // Rotate DEK only for target repository
        final String rotatedWdek = encryptionStorageManager.generateWdek().join();
        final WrappedDekDetails rotatedWdekDetails =
                new WrappedDekDetails(rotatedWdek, 2, encryptionStorageManager.kekId(),
                                      PROJECT_NAME, REPO_NAME);
        encryptionStorageManager.rotateWdek(rotatedWdekDetails);

        // Re-encrypt only target repository
        encryptionStorageManager.reencryptRepositoryData(PROJECT_NAME, REPO_NAME);

        // Verify target repository is updated to version 2
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(targetRefKey)))
                .isEqualTo(2);
        assertThat(encryptionStorageManager.getCurrentDek(PROJECT_NAME, REPO_NAME).version())
                .isEqualTo(2);

        // Verify other repository is still at version 1
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(otherRefKey)))
                .isEqualTo(1);
        assertThat(encryptionStorageManager.getCurrentDek(PROJECT_NAME, OTHER_REPO_NAME).version())
                .isEqualTo(1);

        // Verify both repositories' data is still accessible
        final Ref targetRef = targetStorage.readRef(R_HEADS_MASTER);
        final Ref otherRef = otherStorage.readRef(R_HEADS_MASTER);
        assertThat(targetRef).isNotNull();
        assertThat(otherRef).isNotNull();
    }

    @Test
    @Timeout(60)
    void withLargeNumberOfCommits_shouldHandleBatching() throws Exception {
        final Repository repo = createRepository(REPO_NAME);
        final EncryptionGitStorage encryptionGitStorage = getEncryptionGitStorage(repo);

        // Add many commits so the number of entries become more than (BATCH_WRITE_SIZE = 1000)
        Revision currentRev = Revision.INIT;
        for (int i = 0; i < 400; i++) {
            currentRev = repo.commit(currentRev, 0, Author.SYSTEM, "Commit " + i,
                                    ImmutableList.of(
                                            Change.ofJsonUpsert("/file" + i + ".json",
                                                                "{\"index\":" + i + '}')
                                    )).join().revision();
        }

        // Rotate DEK
        final String rotatedWdek = encryptionStorageManager.generateWdek().join();
        final WrappedDekDetails rotatedWdekDetails =
                new WrappedDekDetails(rotatedWdek, 2, encryptionStorageManager.kekId(),
                                      PROJECT_NAME, REPO_NAME);
        encryptionStorageManager.rotateWdek(rotatedWdekDetails);

        // Re-encrypt (should handle batching internally)
        encryptionStorageManager.reencryptRepositoryData(PROJECT_NAME, REPO_NAME);

        // Verify ALL revisions are updated to version 2
        // This ensures batching works correctly for all 401 revisions (including INIT)
        for (int i = 1; i <= 401; i++) {
            final byte[] revKey = encryptionGitStorage.rev2ShaMetadataKey(new Revision(i));
            final byte[] metadata = encryptionStorageManager.getMetadata(revKey);
            assertThat(metadata).isNotNull();
            assertThat(getMetadataKeyVersion(metadata)).isEqualTo(2);
        }

        // Verify refs are also updated to version 2
        final byte[] refMetadataKey = encryptionGitStorage.refMetadataKey(
                R_HEADS_MASTER.getBytes(StandardCharsets.UTF_8));
        final byte[] headMetadataKey = encryptionGitStorage.refMetadataKey(
                HEAD.getBytes(StandardCharsets.UTF_8));
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(refMetadataKey)))
                .isEqualTo(2);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(headMetadataKey)))
                .isEqualTo(2);

        // Verify data is still accessible
        final Ref masterRef = encryptionGitStorage.readRef(R_HEADS_MASTER);
        assertThat(masterRef).isNotNull();
        final ObjectLoader loader = encryptionGitStorage.getObject(masterRef.getObjectId(), OBJ_ANY);
        assertThat(loader).isNotNull();
    }

    @Test
    void shouldReencryptAllDataTypes() throws Exception {
        // 1. Create repository with diverse data
        final Repository repo = createRepository(REPO_NAME);
        final EncryptionGitStorage encryptionGitStorage = getEncryptionGitStorage(repo);

        // Add multiple commits with different file types
        repo.commit(Revision.INIT, 0, Author.SYSTEM, "Add diverse files",
                   ImmutableList.of(
                           Change.ofJsonUpsert("/config.json", "{\"config\":true}"),
                           Change.ofTextUpsert("/readme.txt", "README content"),
                           Change.ofJsonUpsert("/data/nested.json", "{\"nested\":true}")
                   )).join();

        // 2. Collect metadata keys for different types
        final byte[] rev1Key = encryptionGitStorage.rev2ShaMetadataKey(Revision.INIT);
        final byte[] rev2Key = encryptionGitStorage.rev2ShaMetadataKey(new Revision(2));
        final byte[] refKey = encryptionGitStorage.refMetadataKey(
                R_HEADS_MASTER.getBytes(StandardCharsets.UTF_8));
        final byte[] headKey = encryptionGitStorage.refMetadataKey(
                HEAD.getBytes(StandardCharsets.UTF_8));

        // Get object IDs to verify object metadata
        final ObjectId rev2ObjectId = encryptionGitStorage.getRevisionObjectId(new Revision(2));
        final byte[] objectKey = encryptionGitStorage.objectMetadataKey(rev2ObjectId);

        // All should be version 1
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(rev1Key))).isEqualTo(1);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(rev2Key))).isEqualTo(1);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(refKey))).isEqualTo(1);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(headKey))).isEqualTo(1);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(objectKey))).isEqualTo(1);

        // 3. Rotate and re-encrypt
        final String rotatedWdek = encryptionStorageManager.generateWdek().join();
        final WrappedDekDetails rotatedWdekDetails =
                new WrappedDekDetails(rotatedWdek, 2, encryptionStorageManager.kekId(),
                                      PROJECT_NAME, REPO_NAME);
        encryptionStorageManager.rotateWdek(rotatedWdekDetails);
        encryptionStorageManager.reencryptRepositoryData(PROJECT_NAME, REPO_NAME);

        // 4. Verify all types are updated to version 2
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(rev1Key))).isEqualTo(2);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(rev2Key))).isEqualTo(2);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(refKey))).isEqualTo(2);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(headKey))).isEqualTo(2);
        assertThat(getMetadataKeyVersion(encryptionStorageManager.getMetadata(objectKey))).isEqualTo(2);

        // 5. Verify all data is still accessible
        final ObjectLoader loader = encryptionGitStorage.getObject(rev2ObjectId, OBJ_ANY);
        assertThat(loader).isNotNull();
        assertThat(new String(loader.getBytes())).contains("Add diverse files");
    }
}

