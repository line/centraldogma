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
package com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb;

import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.NONCE_SIZE_BYTES;
import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.aesSecretKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.internal.storage.file.RefDirectory;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Ref.Storage;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.SymbolicRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import com.google.common.base.Strings;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageException;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.encryption.SecretKeyWithVersion;

class EncryptionGitStorageTest {

    private static final String TEST_PROJECT = "foo";
    private static final String TEST_REPO = "bar";
    private static final SecretKey DEK = new SecretKeySpec(AesGcmSivCipher.generateAes256Key(), "AES");

    private static final String HEAD_MASTER_REF = Constants.R_HEADS + Constants.MASTER;

    // Test Data
    private static final ObjectId OBJECT_ID = ObjectId.fromString(Strings.repeat("a", 40));
    private static final byte[] OBJ_DATA = "Object Data 1".getBytes(StandardCharsets.UTF_8);
    private static final Revision REV_1 = new Revision(1);

    @Mock
    EncryptionStorageManager encryptionStorageManager;

    EncryptionGitStorage storage;

    @BeforeEach
    void setUp() {
        // Mock getDek to return our fixed key
        when(encryptionStorageManager.getCurrentDek(TEST_PROJECT, TEST_REPO))
                .thenReturn(new SecretKeyWithVersion(DEK, 1));
        storage = new EncryptionGitStorage(TEST_PROJECT, TEST_REPO, encryptionStorageManager);

        // Leniently stubs.
        lenient().when(encryptionStorageManager.containsMetadata(any())).thenReturn(false);
        lenient().when(encryptionStorageManager.getObject(any(), any())).thenReturn(null);
        lenient().when(encryptionStorageManager.getObjectId(any(), any())).thenReturn(null);
    }

    // object test methods

    @Test
    void insertObject() throws Exception {
        final ArgumentCaptor<byte[]> metadataKeyCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> metadataValueCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> dataKeyCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> dataValueCaptor = ArgumentCaptor.forClass(byte[].class);

        final ObjectId result =
                storage.insertObject(OBJECT_ID, Constants.OBJ_BLOB, OBJ_DATA, 0, OBJ_DATA.length);
        assertThat(result).isEqualTo(OBJECT_ID);

        // Verify the specific put method was called once
        verify(encryptionStorageManager, times(1)).putObject(
                metadataKeyCaptor.capture(), metadataValueCaptor.capture(),
                dataKeyCaptor.capture(), dataValueCaptor.capture()
        );

        // Verify Metadata Entry Key
        final byte[] expectedMetadataKey = storage.objectMetadataKey(OBJECT_ID);
        assertThat(metadataKeyCaptor.getValue()).isEqualTo(expectedMetadataKey);

        // Verify Metadata Entry Value (Nonce + Type + object WDEK)
        final byte[] actualMetadataValue = metadataValueCaptor.getValue();
        assertThat(actualMetadataValue.length).isEqualTo(
                4 + NONCE_SIZE_BYTES + 4 + 48); // 32 for key 16 for tag
        final GitObjectMetadata gitObjectMetadata = GitObjectMetadata.fromBytes(actualMetadataValue);
        assertThat(gitObjectMetadata.keyVersion()).isEqualTo(1);
        assertThat(gitObjectMetadata.type()).isEqualTo(Constants.OBJ_BLOB);

        final SecretKeySpec objectDek = gitObjectMetadata.objectDek(DEK);

        final byte[] expectedEncryptedDataKey =
                encryptObjectId(objectDek, gitObjectMetadata.nonce(), OBJECT_ID);
        assertThat(dataKeyCaptor.getValue()).isEqualTo(expectedEncryptedDataKey);

        final byte[] expectedEncryptedDataValue = encrypt(objectDek, gitObjectMetadata.nonce(), OBJ_DATA);
        assertThat(dataValueCaptor.getValue()).isEqualTo(expectedEncryptedDataValue);
    }

    @Test
    void insertExistingObject() throws Exception {
        when(encryptionStorageManager.containsMetadata(storage.objectMetadataKey(OBJECT_ID))).thenReturn(true);

        final ObjectId result =
                storage.insertObject(OBJECT_ID, Constants.OBJ_BLOB, OBJ_DATA, 0, OBJ_DATA.length);
        assertThat(result).isEqualTo(OBJECT_ID);
        verify(encryptionStorageManager, never()).putObject(any(), any(), any(), any());
    }

    @Test
    void getObject() throws Exception {
        final byte[] metadataKey = storage.objectMetadataKey(OBJECT_ID);

        when(encryptionStorageManager.getMetadata(metadataKey)).thenReturn(null);

        ObjectLoader loader = storage.getObject(OBJECT_ID, ObjectReader.OBJ_ANY);
        assertThat(loader).isNull();
        verify(encryptionStorageManager).getMetadata(metadataKey);
        verify(encryptionStorageManager, never())
                .getObject(argThat(key -> !Arrays.equals(key, metadataKey)), any());

        final byte[] nonce = AesGcmSivCipher.generateNonce();
        final byte[] objectDek = AesGcmSivCipher.generateAes256Key();
        final byte[] objectWdek = encryptWithDek(nonce, objectDek);
        final GitObjectMetadata gitObjectMetadata =
                GitObjectMetadata.of(1, nonce, Constants.OBJ_COMMIT, objectWdek);
        final SecretKeySpec key = aesSecretKey(objectDek);
        final byte[] encryptedObjKey = encryptObjectId(key, nonce, OBJECT_ID);
        final byte[] encryptedObjValue = encrypt(key, nonce, OBJ_DATA);

        when(encryptionStorageManager.getMetadata(metadataKey)).thenReturn(gitObjectMetadata.toBytes());
        when(encryptionStorageManager.getObject(encryptedObjKey, metadataKey)).thenReturn(encryptedObjValue);

        loader = storage.getObject(OBJECT_ID, ObjectReader.OBJ_ANY);

        assertThat(loader).isNotNull();
        assertThat(loader.getType()).isEqualTo(Constants.OBJ_COMMIT);
        assertThat(loader.getSize()).isEqualTo(OBJ_DATA.length);
        assertThat(loader.getBytes()).isEqualTo(OBJ_DATA);
        assertThat(loader.getCachedBytes()).isEqualTo(OBJ_DATA);

        verify(encryptionStorageManager, times(2)).getMetadata(metadataKey);
        verify(encryptionStorageManager).getObject(encryptedObjKey, metadataKey);
    }

    @Test
    void getObject_incorrectType() throws Exception {
        final int actualType = Constants.OBJ_BLOB;
        final int wrongTypeHint = Constants.OBJ_COMMIT;
        final byte[] nonce = AesGcmSivCipher.generateNonce();
        final byte[] metadataKey = storage.objectMetadataKey(OBJECT_ID);
        final byte[] objectDek = AesGcmSivCipher.generateAes256Key();
        final byte[] objectWdek = encryptWithDek(nonce, objectDek);
        final GitObjectMetadata gitObjectMetadata = GitObjectMetadata.of(1, nonce, actualType, objectWdek);

        when(encryptionStorageManager.getMetadata(metadataKey)).thenReturn(gitObjectMetadata.toBytes());

        assertThatThrownBy(() -> storage.getObject(OBJECT_ID, wrongTypeHint))
                .isInstanceOf(IncorrectObjectTypeException.class);

        verify(encryptionStorageManager).getMetadata(metadataKey);
        verify(encryptionStorageManager, never()).getMetadata(
                argThat(key -> !Arrays.equals(key, metadataKey)));
    }

    @Test
    void getObject_decryptionError() throws Exception {
        final int type = Constants.OBJ_BLOB;
        final byte[] nonce = AesGcmSivCipher.generateNonce();
        final byte[] metadataKey = storage.objectMetadataKey(OBJECT_ID);
        final byte[] objectDek = AesGcmSivCipher.generateAes256Key();
        final byte[] objectWdek = encryptWithDek(nonce, objectDek);
        final GitObjectMetadata gitObjectMetadata = GitObjectMetadata.of(1, nonce, type, objectWdek);
        final SecretKeySpec key = aesSecretKey(objectDek);
        final byte[] encryptedObjKey = encryptObjectId(key, nonce, OBJECT_ID);
        // Simulate corrupted data that will cause decrypt to fail
        final byte[] corruptedEncryptedValue = "corrupted data".getBytes();

        when(encryptionStorageManager.getMetadata(metadataKey)).thenReturn(gitObjectMetadata.toBytes());
        when(encryptionStorageManager.getObject(encryptedObjKey, metadataKey))
                .thenReturn(corruptedEncryptedValue);

        assertThatThrownBy(() -> storage.getObject(OBJECT_ID, ObjectReader.OBJ_ANY))
                .isInstanceOf(EncryptionStorageException.class)
                .hasMessageContaining("Failed to decrypt data");
    }

    // ref test methods

    @Test
    void readRef() throws Exception {
        final byte[] nonce = AesGcmSivCipher.generateNonce();
        final byte[] metadata = new byte[4 + NONCE_SIZE_BYTES];
        ByteBuffer.wrap(metadata).putInt(1).put(nonce);
        final byte[] metadataKey = refMetadataKey(HEAD_MASTER_REF);
        final byte[] encryptedRefName = encryptStringWithDek(nonce, HEAD_MASTER_REF);
        final byte[] encryptedValue = encryptObjectIdWithDek(nonce, OBJECT_ID);

        when(encryptionStorageManager.getMetadata(metadataKey)).thenReturn(metadata);
        when(encryptionStorageManager.getObjectId(encryptedRefName, metadataKey)).thenReturn(encryptedValue);

        final Ref ref = storage.readRef(HEAD_MASTER_REF);

        assertThat(ref).isNotNull();
        assertThat(ref.getName()).isEqualTo(HEAD_MASTER_REF);
        assertThat(ref.isSymbolic()).isFalse();
        assertThat(ref.getObjectId()).isEqualTo(OBJECT_ID);
        assertThat(ref.getStorage()).isEqualTo(Storage.LOOSE);

        verify(encryptionStorageManager).getMetadata(metadataKey);
        verify(encryptionStorageManager).getObjectId(encryptedRefName, metadataKey);
    }

    @Test
    void readSymbolicRef() throws Exception {
        final byte[] symRefNonce = AesGcmSivCipher.generateNonce();
        final byte[] symRefMetadata = new byte[4 + NONCE_SIZE_BYTES];
        ByteBuffer.wrap(symRefMetadata).putInt(1).put(symRefNonce);

        final byte[] targetRefNonce = AesGcmSivCipher.generateNonce();
        final byte[] targetRefMetadata = new byte[4 + NONCE_SIZE_BYTES];
        ByteBuffer.wrap(targetRefMetadata).putInt(1).put(targetRefNonce);

        final byte[] symRefMetadataKey = refMetadataKey(Constants.HEAD);
        final byte[] encryptedSymRefName = encryptStringWithDek(symRefNonce, Constants.HEAD);
        final byte[] encryptedSymRefTargetValue = encryptWithDek(
                symRefNonce, symbolicRefBytes(HEAD_MASTER_REF));

        final byte[] targetRefMetadataKey = refMetadataKey(HEAD_MASTER_REF);
        final byte[] encryptedTargetRefName = encryptStringWithDek(targetRefNonce, HEAD_MASTER_REF);
        final byte[] encryptedTargetRefObjectIdValue = encryptObjectIdWithDek(targetRefNonce, OBJECT_ID);

        when(encryptionStorageManager.getMetadata(symRefMetadataKey)).thenReturn(symRefMetadata);
        when(encryptionStorageManager.getObjectId(encryptedSymRefName, symRefMetadataKey))
                .thenReturn(encryptedSymRefTargetValue);
        when(encryptionStorageManager.getMetadata(targetRefMetadataKey)).thenReturn(targetRefMetadata);
        when(encryptionStorageManager.getObjectId(encryptedTargetRefName, targetRefMetadataKey)).thenReturn(
                encryptedTargetRefObjectIdValue);

        final Ref ref = storage.readRef(Constants.HEAD);

        assertThat(ref).isNotNull().isInstanceOf(SymbolicRef.class);
        assertThat(ref.getName()).isEqualTo(Constants.HEAD);
        assertThat(ref.getObjectId()).isEqualTo(OBJECT_ID);

        final Ref target = ref.getTarget();
        assertThat(target).isNotNull();
        assertThat(target.getName()).isEqualTo(HEAD_MASTER_REF);
        assertThat(target.isSymbolic()).isFalse();
        assertThat(target.getObjectId()).isEqualTo(OBJECT_ID);

        verify(encryptionStorageManager, times(2)).getMetadata(any());
        verify(encryptionStorageManager, times(2)).getObjectId(any(), any());
        verify(encryptionStorageManager).getMetadata(symRefMetadataKey);
        verify(encryptionStorageManager).getObjectId(encryptedSymRefName, symRefMetadataKey);
        verify(encryptionStorageManager).getMetadata(targetRefMetadataKey);
        verify(encryptionStorageManager).getObjectId(encryptedTargetRefName, targetRefMetadataKey);
    }

    @Test
    void updateRef_storesCorrectly() throws Exception {
        final Result desiredResult = Result.NEW;

        final ArgumentCaptor<byte[]> metaKeyCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> metadataCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> refNameKeyCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> refValueCaptor = ArgumentCaptor.forClass(byte[].class); // Encrypted ID
        final ArgumentCaptor<byte[]> previousKeyCaptor = ArgumentCaptor.forClass(byte[].class);

        final Result actualResult = storage.updateRef(HEAD_MASTER_REF, OBJECT_ID, desiredResult);
        assertThat(actualResult).isEqualTo(desiredResult);

        verify(encryptionStorageManager, times(1)).putObjectId(
                metaKeyCaptor.capture(), metadataCaptor.capture(),
                refNameKeyCaptor.capture(), refValueCaptor.capture(), previousKeyCaptor.capture()
        );
        reset(encryptionStorageManager);

        final byte[] expectedMetadataKey = refMetadataKey(HEAD_MASTER_REF);
        assertThat(metaKeyCaptor.getValue()).isEqualTo(expectedMetadataKey);
        final byte[] metadata = metadataCaptor.getValue();
        assertThat(metadata).hasSize(4 + NONCE_SIZE_BYTES);
        final int capturedKeyVersion = ByteBuffer.wrap(metadata, 0, 4).getInt();
        assertThat(capturedKeyVersion).isEqualTo(1);
        final byte[] capturedNonce = Arrays.copyOfRange(metadata, 4, 4 + NONCE_SIZE_BYTES);
        assertThat(capturedNonce).hasSize(NONCE_SIZE_BYTES);

        final byte[] expectedEncryptedRefNameKey = encryptStringWithDek(capturedNonce, HEAD_MASTER_REF);
        assertThat(refNameKeyCaptor.getValue()).isEqualTo(expectedEncryptedRefNameKey);
        final byte[] expectedEncryptedRefValue = encryptObjectIdWithDek(capturedNonce, OBJECT_ID);
        assertThat(refValueCaptor.getValue()).isEqualTo(expectedEncryptedRefValue);
        assertThat(previousKeyCaptor.getValue()).isNull();

        when(encryptionStorageManager.getMetadata(expectedMetadataKey)).thenReturn(metadata);
        final Result actualResult2 = storage.updateRef(HEAD_MASTER_REF, OBJECT_ID, desiredResult);
        assertThat(actualResult2).isEqualTo(desiredResult);

        verify(encryptionStorageManager, times(1)).putObjectId(
                metaKeyCaptor.capture(), metadataCaptor.capture(),
                refNameKeyCaptor.capture(), refValueCaptor.capture(), previousKeyCaptor.capture()
        );

        assertThat(metaKeyCaptor.getValue()).isEqualTo(expectedMetadataKey);
        final byte[] metadata2 = metadataCaptor.getValue();
        assertThat(metadata2).hasSize(4 + NONCE_SIZE_BYTES);
        final int capturedKeyVersion2 = ByteBuffer.wrap(metadata2, 0, 4).getInt();
        assertThat(capturedKeyVersion2).isEqualTo(1);
        final byte[] capturedNonce2 = Arrays.copyOfRange(metadata2, 4, 4 + NONCE_SIZE_BYTES);
        assertThat(capturedNonce2).hasSize(NONCE_SIZE_BYTES);

        final byte[] expectedEncryptedRefNameKey2 = encryptStringWithDek(capturedNonce2, HEAD_MASTER_REF);
        assertThat(refNameKeyCaptor.getValue()).isEqualTo(expectedEncryptedRefNameKey2);
        final byte[] expectedEncryptedRefValue2 = encryptObjectIdWithDek(capturedNonce2, OBJECT_ID);
        assertThat(refValueCaptor.getValue()).isEqualTo(expectedEncryptedRefValue2);

        assertThat(previousKeyCaptor.getValue()).isEqualTo(expectedEncryptedRefNameKey);
    }

    @Test
    void deleteRef_deletesBothKeys() throws Exception {
        final byte[] nonce = AesGcmSivCipher.generateNonce();
        final byte[] metadata = new byte[4 + NONCE_SIZE_BYTES];
        ByteBuffer.wrap(metadata).putInt(1).put(nonce);
        final byte[] metadataKey = refMetadataKey(HEAD_MASTER_REF);
        final byte[] encryptedRefNameKey = encryptStringWithDek(nonce, HEAD_MASTER_REF);

        when(encryptionStorageManager.getMetadata(metadataKey)).thenReturn(metadata);

        storage.deleteRef(HEAD_MASTER_REF);

        final ArgumentCaptor<byte[]> metaKeyCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> refNameKeyCaptor = ArgumentCaptor.forClass(byte[].class);

        verify(encryptionStorageManager).getMetadata(metadataKey);
        verify(encryptionStorageManager, times(1))
                .deleteObjectId(metaKeyCaptor.capture(), refNameKeyCaptor.capture());
        assertThat(metaKeyCaptor.getValue()).isEqualTo(metadataKey);
        assertThat(refNameKeyCaptor.getValue()).isEqualTo(encryptedRefNameKey);
    }

    @Test
    void linkRef_storesCorrectly() throws Exception {
        final String target = HEAD_MASTER_REF;

        final ArgumentCaptor<byte[]> metaKeyCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> metadataCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> refNameKeyCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> refValueCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> previousKeyCaptor = ArgumentCaptor.forClass(byte[].class);

        storage.linkRef(Constants.HEAD, target);

        verify(encryptionStorageManager, times(1)).putObjectId(
                metaKeyCaptor.capture(), metadataCaptor.capture(),
                refNameKeyCaptor.capture(), refValueCaptor.capture(), previousKeyCaptor.capture()
        );

        final byte[] expectedMetadataKey = refMetadataKey(Constants.HEAD);
        assertThat(metaKeyCaptor.getValue()).isEqualTo(expectedMetadataKey);
        final byte[] metadata = metadataCaptor.getValue();
        assertThat(metadata).hasSize(4 + NONCE_SIZE_BYTES);
        final int capturedKeyVersion = ByteBuffer.wrap(metadata, 0, 4).getInt();
        assertThat(capturedKeyVersion).isEqualTo(1);
        final byte[] capturedNonce = Arrays.copyOfRange(metadata, 4, 4 + NONCE_SIZE_BYTES);
        assertThat(capturedNonce).hasSize(NONCE_SIZE_BYTES);

        final byte[] expectedEncryptedRefNameKey = encryptStringWithDek(capturedNonce, Constants.HEAD);
        assertThat(refNameKeyCaptor.getValue()).isEqualTo(expectedEncryptedRefNameKey);
        final byte[] expectedEncryptedRefValue = encryptWithDek(capturedNonce, symbolicRefBytes(target));
        assertThat(refValueCaptor.getValue()).isEqualTo(expectedEncryptedRefValue);
        assertThat(previousKeyCaptor.getValue()).isNull();
    }

    // Revision mapping test methods

    @Test
    void getRevisionObjectId_found() throws Exception {
        final byte[] nonce = AesGcmSivCipher.generateNonce();
        final byte[] metadata = new byte[4 + NONCE_SIZE_BYTES];
        ByteBuffer.wrap(metadata).putInt(1).put(nonce);
        final byte[] metadataKey = storage.rev2ShaMetadataKey(REV_1);
        final byte[] encryptedRevisionKey = encryptWithDek(nonce, revisionBytes(REV_1));
        final byte[] encryptedIdValue = encryptObjectIdWithDek(nonce, OBJECT_ID);

        when(encryptionStorageManager.getMetadata(metadataKey)).thenReturn(metadata);
        when(encryptionStorageManager.getObjectId(encryptedRevisionKey, metadataKey))
                .thenReturn(encryptedIdValue);

        final ObjectId retrievedId = storage.getRevisionObjectId(REV_1);

        assertThat(retrievedId).isEqualTo(OBJECT_ID);
        verify(encryptionStorageManager).getMetadata(metadataKey);
        verify(encryptionStorageManager).getObjectId(encryptedRevisionKey, metadataKey);
    }

    @Test
    void getRevisionObjectId_metadataNotFound() {
        final byte[] metadataKey = storage.rev2ShaMetadataKey(REV_1);
        when(encryptionStorageManager.getMetadata(metadataKey)).thenReturn(null);

        assertThatThrownBy(() -> storage.getRevisionObjectId(REV_1))
                .isInstanceOf(RevisionNotFoundException.class);

        verify(encryptionStorageManager).getMetadata(metadataKey);
        verify(encryptionStorageManager, never()).getMetadata(
                argThat(key -> !Arrays.equals(key, metadataKey)));
    }

    @Test
    void getRevisionObjectId_valueNotFound() throws Exception {
        final byte[] nonce = AesGcmSivCipher.generateNonce();
        final byte[] metadata = new byte[4 + NONCE_SIZE_BYTES];
        ByteBuffer.wrap(metadata).putInt(1).put(nonce);
        final byte[] metadataKey = storage.rev2ShaMetadataKey(REV_1);
        final byte[] encryptedRevisionKey = encryptWithDek(nonce, revisionBytes(REV_1));

        when(encryptionStorageManager.getMetadata(metadataKey)).thenReturn(metadata);
        when(encryptionStorageManager.getObjectId(encryptedRevisionKey, metadataKey))
                .thenReturn(null); // Value missing

        assertThatThrownBy(() -> storage.getRevisionObjectId(REV_1))
                .isInstanceOf(RevisionNotFoundException.class);

        verify(encryptionStorageManager).getMetadata(metadataKey);
        verify(encryptionStorageManager).getObjectId(encryptedRevisionKey, metadataKey);
    }

    @Test
    void putRevisionObjectId_new() throws Exception {
        final byte[] metadataKey = storage.rev2ShaMetadataKey(REV_1);

        // Capture args
        final ArgumentCaptor<byte[]> metaKeyCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> metadataCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> revKeyCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> idValueCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<byte[]> previousKeyCaptor = ArgumentCaptor.forClass(byte[].class);

        storage.putRevisionObjectId(REV_1, OBJECT_ID);

        verify(encryptionStorageManager, times(1)).putObjectId(
                metaKeyCaptor.capture(), metadataCaptor.capture(),
                revKeyCaptor.capture(), idValueCaptor.capture(), previousKeyCaptor.capture()
        );

        // Verify Metadata
        assertThat(metaKeyCaptor.getValue()).isEqualTo(metadataKey);
        final byte[] metadata = metadataCaptor.getValue();
        assertThat(metadata).hasSize(4 + NONCE_SIZE_BYTES);
        final int capturedKeyVersion = ByteBuffer.wrap(metadata, 0, 4).getInt();
        assertThat(capturedKeyVersion).isEqualTo(1);
        final byte[] capturedNonce = Arrays.copyOfRange(metadata, 4, 4 + NONCE_SIZE_BYTES);
        assertThat(capturedNonce).hasSize(NONCE_SIZE_BYTES);

        // Verify Encrypted Revision/ID Entry
        final byte[] expectedEncryptedRevKey = encryptWithDek(capturedNonce, revisionBytes(REV_1));
        assertThat(revKeyCaptor.getValue()).isEqualTo(expectedEncryptedRevKey);
        final byte[] expectedEncryptedIdValue = encryptObjectIdWithDek(capturedNonce, OBJECT_ID);
        assertThat(idValueCaptor.getValue()).isEqualTo(expectedEncryptedIdValue);
        assertThat(previousKeyCaptor.getValue()).isNull();
    }

    @Test
    void putRevisionObjectId_exists() {
        final byte[] metadataKey = storage.rev2ShaMetadataKey(REV_1);
        when(encryptionStorageManager.containsMetadata(metadataKey)).thenReturn(true);

        assertThatThrownBy(() -> storage.putRevisionObjectId(REV_1, OBJECT_ID))
                .isInstanceOf(EncryptionStorageException.class)
                .hasMessageContaining("Revision already exists: " + REV_1);

        verify(encryptionStorageManager).containsMetadata(metadataKey);
        verify(encryptionStorageManager, never()).putObjectId(any(), any(), any(), any(), any());
    }

    private static byte[] refMetadataKey(String name) {
        final byte[] prefix = (TEST_PROJECT + '/' + TEST_REPO + '/').getBytes(StandardCharsets.UTF_8);
        final byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(prefix.length + nameBytes.length)
                         .put(prefix)
                         .put(nameBytes)
                         .array();
    }

    private static byte[] encryptWithDek(byte[] nonce, byte[] data) {
        return encrypt(DEK, nonce, data);
    }

    private static byte[] encrypt(SecretKey key, byte[] nonce, byte[] data) {
        try {
            return AesGcmSivCipher.encrypt(key, nonce, data, 0, data.length);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed in test helper", e);
        }
    }

    private static byte[] encryptObjectIdWithDek(byte[] nonce, ObjectId id) {
        return encryptObjectId(DEK, nonce, id);
    }

    private static byte[] encryptObjectId(SecretKey key, byte[] nonce, ObjectId id) {
        final byte[] idBytes = new byte[20];
        id.copyRawTo(idBytes, 0);
        return encrypt(key, nonce, idBytes);
    }

    private static byte[] encryptStringWithDek(byte[] nonce, String str) {
        return encryptWithDek(nonce, str.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] symbolicRefBytes(String target) {
        return Constants.encode(RefDirectory.SYMREF + target);
    }

    private static byte[] revisionBytes(Revision rev) {
        return ByteBuffer.allocate(4).putInt(rev.major()).array();
    }
}
