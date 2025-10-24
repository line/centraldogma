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

import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.KEY_SIZE_BYTES;
import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.NONCE_SIZE_BYTES;
import static com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher.aesSecretKey;
import static com.linecorp.centraldogma.server.internal.storage.encryption.EncryptionUtil.getInt;
import static com.linecorp.centraldogma.server.internal.storage.encryption.EncryptionUtil.putInt;
import static org.eclipse.jgit.lib.Constants.R_REFS;
import static org.eclipse.jgit.lib.Constants.encode;
import static org.eclipse.jgit.lib.ObjectReader.OBJ_ANY;
import static org.eclipse.jgit.lib.Ref.Storage.NEW;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.internal.storage.file.RefDirectory;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Ref.Storage;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.SymbolicRef;
import org.jspecify.annotations.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageException;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.encryption.SecretKeyWithVersion;

public final class EncryptionGitStorage {

    public static final String OBJS = "objs/";
    public static final String REFS = R_REFS; // refs/
    public static final String REV2SHA = "rev2sha/";

    private final String projectName;
    private final String repoName;
    private final byte[] objectKeyPrefix;
    private final byte[] refsKeyPrefix;
    private final byte[] rev2ShaPrefix;
    private final EncryptionStorageManager encryptionStorageManager;
    private final SecretKeyWithVersion currentDek;
    private final ConcurrentHashMap<Integer, SecretKey> deks = new ConcurrentHashMap<>();

    public EncryptionGitStorage(String projectName, String repoName,
                                EncryptionStorageManager encryptionStorageManager) {
        this.projectName = projectName;
        this.repoName = repoName;
        final String projectRepoPrefix = projectName + '/' + repoName + '/';
        objectKeyPrefix = (projectRepoPrefix + OBJS).getBytes(StandardCharsets.UTF_8);
        // Suffixes start with refs/ so do not add REFS.
        refsKeyPrefix = projectRepoPrefix.getBytes(StandardCharsets.UTF_8);
        rev2ShaPrefix = (projectRepoPrefix + REV2SHA).getBytes(StandardCharsets.UTF_8);
        this.encryptionStorageManager = encryptionStorageManager;
        currentDek = encryptionStorageManager.getCurrentDek(projectName, repoName);
        deks.put(currentDek.version(), currentDek.secretKey());
    }

    String projectName() {
        return projectName;
    }

    String repoName() {
        return repoName;
    }

    private SecretKey dek(int version) {
        return deks.computeIfAbsent(version, key -> encryptionStorageManager.getDek(
                projectName, repoName, version));
    }

    ObjectId insertObject(ObjectId objectId, int type, byte[] data, int off, int len) throws IOException {
        if (off < 0 || len < 0 || off + len > data.length) {
            throw new IllegalArgumentException("Invalid offset or length: " + off + ", " + len);
        }
        final byte[] metadataKey = objectMetadataKey(objectId);
        if (encryptionStorageManager.containsMetadata(metadataKey)) {
            return objectId;
        }

        final SecretKeyWithVersion currentDek = this.currentDek;

        final byte[] nonce = AesGcmSivCipher.generateNonce();
        // Generate a new DEK for the data so that we don't decrypt and encrypt the data again when the
        // repository key is rotated.
        final byte[] objectDek = AesGcmSivCipher.generateAes256Key();
        final byte[] objectWdek = encrypt(currentDek.secretKey(), nonce, objectDek, 0, KEY_SIZE_BYTES);

        assert objectWdek.length == KEY_SIZE_BYTES + 16; // 16 bytes for the tag

        final GitObjectMetadata gitObjectMetadata = GitObjectMetadata.of(currentDek.version(), nonce, type,
                                                                         objectWdek);

        final SecretKeySpec keySpec = aesSecretKey(objectDek);

        final byte[] encryptedId = encryptObjectId(keySpec, nonce, objectId);
        final byte[] encryptedValue = encrypt(keySpec, nonce, data, off, len);
        encryptionStorageManager.putObject(metadataKey, gitObjectMetadata.toBytes(),
                                           encryptedId, encryptedValue);
        return objectId;
    }

    @VisibleForTesting
    public byte[] objectMetadataKey(ObjectId objectId) {
        final ByteBuffer byteBuffer = ByteBuffer.allocate(objectKeyPrefix.length + 20);
        byteBuffer.put(objectKeyPrefix);
        objectId.copyRawTo(byteBuffer);
        return byteBuffer.array();
    }

    private byte[] encryptObjectId(SecretKey dek, byte[] nonce, ObjectId objectId) {
        final byte[] idBytes = new byte[20];
        objectId.copyRawTo(idBytes, 0);
        return encrypt(dek, nonce, idBytes, 0, 20);
    }

    private byte[] encrypt(SecretKey dek, byte[] nonce, byte[] data, int offset, int length) {
        try {
            return AesGcmSivCipher.encrypt(dek, nonce, data, offset, length);
        } catch (Exception e) {
            throw new EncryptionStorageException(
                    "Failed to encrypt data in " + projectName + '/' + repoName, e);
        }
    }

    private byte[] decrypt(SecretKey key, byte[] nonce, byte[] ciphertext) {
        try {
            return AesGcmSivCipher.decrypt(key, nonce, ciphertext);
        } catch (Exception e) {
            throw new EncryptionStorageException(
                    "Failed to decrypt data in " + projectName + '/' + repoName, e);
        }
    }

    @Nullable
    public ObjectLoader getObject(ObjectId objectId, int typeHint) throws IncorrectObjectTypeException {
        final byte[] metadataKey = objectMetadataKey(objectId);
        final byte[] metadata = encryptionStorageManager.getMetadata(metadataKey);
        if (metadata == null) {
            return null;
        }

        final int actualType = getInt(metadata, 4 + NONCE_SIZE_BYTES);
        if (typeHint != OBJ_ANY && actualType != typeHint) {
            throw new IncorrectObjectTypeException(objectId.copy(), typeHint);
        }

        final int keyVersion = getInt(metadata, 0);
        final SecretKey dek = dek(keyVersion);

        final GitObjectMetadata gitObjectMetadata = GitObjectMetadata.fromBytes(metadata);
        final SecretKeySpec objectDek;
        try {
            objectDek = gitObjectMetadata.objectDek(dek);
        } catch (Exception e) {
            throw new EncryptionStorageException(
                    "Failed to get object dek in " + projectName + '/' + repoName + " for " + objectId, e);
        }

        final byte[] encryptedKey = encryptObjectId(objectDek,
                                                    gitObjectMetadata.nonce(), objectId);
        final byte[] value = encryptionStorageManager.getObject(encryptedKey, metadataKey);
        if (value == null) {
            return null;
        }

        final byte[] decrypted = decrypt(objectDek, gitObjectMetadata.nonce(), value);
        return new DecryptedObjectLoader(decrypted, actualType);
    }

    @Nullable
    @VisibleForTesting
    public Ref readRef(String refName) {
        final byte[] refNameBytes = refName.getBytes(StandardCharsets.UTF_8);
        final byte[] metadataKey = refMetadataKey(refNameBytes);
        final byte[] metadata = encryptionStorageManager.getMetadata(metadataKey);
        if (metadata == null) {
            return null;
        }
        final byte[] nonce = new byte[NONCE_SIZE_BYTES];
        System.arraycopy(metadata, 4, nonce, 0, NONCE_SIZE_BYTES);
        final int keyVersion = getInt(metadata, 0);
        final SecretKey dek = dek(keyVersion);

        final byte[] encryptedRefName = encrypt(dek, nonce, refNameBytes, 0, refNameBytes.length);
        final byte[] encryptedRefValue = encryptionStorageManager.getObjectId(encryptedRefName, metadataKey);
        if (encryptedRefValue == null) {
            return null;
        }

        final byte[] refValue = decrypt(dek, nonce, encryptedRefValue);
        if (!isSymRef(refValue, refValue.length)) {
            // We don't use annotated tag.
            return new ObjectIdRef.PeeledNonTag(
                    // Just use LOOSE because we don't distinguish between LOOSE and PACKED.
                    Storage.LOOSE, refName, ObjectId.fromRaw(refValue));
        }
        final byte[] refValueWithoutSymRef = new byte[refValue.length - 5];
        System.arraycopy(refValue, 5, refValueWithoutSymRef, 0, refValueWithoutSymRef.length);
        final String targetRefName = new String(refValueWithoutSymRef, StandardCharsets.UTF_8);
        final Ref targetRef = readRef(targetRefName);
        if (targetRef != null) {
            return new SymbolicRef(refName, targetRef);
        }
        return new SymbolicRef(refName, new ObjectIdRef.Unpeeled(NEW, targetRefName, null));
    }

    private static boolean isSymRef(byte[] buf, int n) {
        if (n < 6) {
            return false;
        }
        return buf[0] == 'r' && buf[1] == 'e' && buf[2] == 'f' && buf[3] == ':' && buf[4] == ' ';
    }

    Result updateRef(String refName, ObjectId objectId, Result desiredResult) {
        final byte[] refNameBytes = refName.getBytes(StandardCharsets.UTF_8);

        final SecretKeyWithVersion currentDek = this.currentDek;
        final byte[] metadataKey = refMetadataKey(refNameBytes);
        final byte[] metadata = new byte[4 + NONCE_SIZE_BYTES];
        putInt(metadata, 0, currentDek.version());
        final byte[] nonce = AesGcmSivCipher.generateNonce();
        System.arraycopy(nonce, 0, metadata, 4, NONCE_SIZE_BYTES);

        final byte[] encryptedRefName =
                encrypt(currentDek.secretKey(), nonce, refNameBytes, 0, refNameBytes.length);
        final byte[] encryptedId = encryptObjectId(currentDek.secretKey(), nonce, objectId);

        // We should remove the previous ref name if it exists.

        final byte[] previousEncryptedRefName;
        final byte[] previousMetadata = encryptionStorageManager.getMetadata(metadataKey);
        if (previousMetadata == null) {
            previousEncryptedRefName = null;
        } else {
            final byte[] previousNonce = new byte[NONCE_SIZE_BYTES];
            System.arraycopy(previousMetadata, 4, previousNonce, 0, NONCE_SIZE_BYTES);
            final SecretKey previousDek = dek(getInt(previousMetadata, 0));
            previousEncryptedRefName =
                    encrypt(previousDek, previousNonce, refNameBytes, 0, refNameBytes.length);
        }
        encryptionStorageManager.putObjectId(metadataKey, metadata, encryptedRefName,
                                             encryptedId, previousEncryptedRefName);
        return desiredResult;
    }

    @VisibleForTesting
    public byte[] refMetadataKey(byte[] refNameBytes) {
        final ByteBuffer byteBuffer = ByteBuffer.allocate(refsKeyPrefix.length + refNameBytes.length);
        byteBuffer.put(refsKeyPrefix);
        byteBuffer.put(refNameBytes);
        return byteBuffer.array();
    }

    void deleteRef(String refName) {
        final byte[] refNameBytes = refName.getBytes(StandardCharsets.UTF_8);
        final byte[] metadataKey = refMetadataKey(refNameBytes);
        final byte[] metadata = encryptionStorageManager.getMetadata(metadataKey);
        if (metadata == null) {
            return;
        }

        final byte[] nonce = new byte[NONCE_SIZE_BYTES];
        System.arraycopy(metadata, 4, nonce, 0, NONCE_SIZE_BYTES);
        final int keyVersion = getInt(metadata, 0);
        final SecretKey dek = dek(keyVersion);

        final byte[] encryptedRefName = encrypt(dek, nonce, refNameBytes, 0, refNameBytes.length);
        encryptionStorageManager.deleteObjectId(metadataKey, encryptedRefName);
    }

    void linkRef(String refName, String target) {
        final byte[] refNameBytes = refName.getBytes(StandardCharsets.UTF_8);
        final byte[] metadataKey = refMetadataKey(refNameBytes);
        final byte[] nonce = AesGcmSivCipher.generateNonce();
        final byte[] metadata = new byte[4 + NONCE_SIZE_BYTES];
        final SecretKeyWithVersion currentDek = this.currentDek;
        putInt(metadata, 0, currentDek.version());
        System.arraycopy(nonce, 0, metadata, 4, NONCE_SIZE_BYTES);

        final byte[] encryptedRefName =
                encrypt(currentDek.secretKey(), nonce, refNameBytes, 0, refNameBytes.length);
        final byte[] encoded = encode(RefDirectory.SYMREF + target);
        final byte[] encryptedTarget = encrypt(currentDek.secretKey(), nonce, encoded, 0, encoded.length);
        encryptionStorageManager.putObjectId(metadataKey, metadata, encryptedRefName, encryptedTarget, null);
    }

    @VisibleForTesting
    public ObjectId getRevisionObjectId(Revision revision) {
        final byte[] metadataKey = rev2ShaMetadataKey(revision);
        final byte[] metadata = encryptionStorageManager.getMetadata(metadataKey);
        if (metadata == null) {
            throw new RevisionNotFoundException(revision);
        }
        final int keyVersion = getInt(metadata, 0);
        final SecretKey dek = dek(keyVersion);

        final byte[] nonce = new byte[NONCE_SIZE_BYTES];
        System.arraycopy(metadata, 4, nonce, 0, NONCE_SIZE_BYTES);

        final byte[] encryptedKey = encrypt(dek, nonce, Ints.toByteArray(revision.major()), 0, 4);
        final byte[] value = encryptionStorageManager.getObjectId(encryptedKey, metadataKey);
        if (value == null) {
            throw new RevisionNotFoundException(revision);
        }
        final byte[] raw = decrypt(dek, nonce, value);
        if (raw.length != 20) {
            throw new EncryptionStorageException(
                    "Corrupted commit ID for " + revision + " in " + projectName + '/' + repoName + ": " +
                    " expected 20 bytes but got " + raw.length);
        }
        return ObjectId.fromRaw(raw);
    }

    @VisibleForTesting
    public byte[] rev2ShaMetadataKey(Revision revision) {
        final ByteBuffer byteBuffer = ByteBuffer.allocate(rev2ShaPrefix.length + 4);
        byteBuffer.put(rev2ShaPrefix);
        byteBuffer.putInt(revision.major());
        return byteBuffer.array();
    }

    void putRevisionObjectId(Revision revision, ObjectId objectId) {
        final byte[] metadataKey = rev2ShaMetadataKey(revision);
        if (encryptionStorageManager.containsMetadata(metadataKey)) {
            throw new EncryptionStorageException(
                    "Revision already exists: " + revision + " in " + projectName + '/' + repoName);
        }
        final SecretKeyWithVersion currentDek = this.currentDek;
        final byte[] metadata = new byte[4 + NONCE_SIZE_BYTES];
        final byte[] nonce = AesGcmSivCipher.generateNonce();
        putInt(metadata, 0, currentDek.version());
        System.arraycopy(nonce, 0, metadata, 4, NONCE_SIZE_BYTES);

        final byte[] encryptedRevision =
                encrypt(currentDek.secretKey(), nonce, Ints.toByteArray(revision.major()), 0, 4);
        final byte[] encryptedId = encryptObjectId(currentDek.secretKey(), nonce, objectId);
        encryptionStorageManager.putObjectId(metadataKey, metadata, encryptedRevision, encryptedId, null);
    }

    private static final class DecryptedObjectLoader extends ObjectLoader {
        private final byte[] decrypted;
        private final int type;

        DecryptedObjectLoader(byte[] decrypted, int type) {
            this.decrypted = decrypted;
            this.type = type;
        }

        @Override
        public int getType() {
            return type;
        }

        @Override
        public long getSize() {
            return decrypted.length;
        }

        @Override
        public byte[] getCachedBytes() {
            return decrypted;
        }

        @Override
        public ObjectStream openStream() {
            return new ObjectStream.SmallStream(this);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("type", type)
                              .add("size", decrypted.length)
                              .toString();
        }
    }
}
