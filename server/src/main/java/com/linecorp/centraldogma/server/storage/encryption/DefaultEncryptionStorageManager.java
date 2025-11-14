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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.server.auth.SessionKey;
import com.linecorp.centraldogma.server.auth.SessionMasterKey;

final class DefaultEncryptionStorageManager implements EncryptionStorageManager {

    static final String ROCKSDB_PATH = "_rocks";

    private final boolean encryptSessionCookie;
    private final String kekId;
    private final RocksDBStorage rocksDbStorage;
    private final SessionKeyStorage sessionKeyStorage;
    private final RepositoryEncryptionStorage repositoryEncryptionStorage;

    DefaultEncryptionStorageManager(String rocksDbPath, boolean encryptSessionCookie, String kekId) {
        requireNonNull(rocksDbPath, "rocksDbPath");
        this.encryptSessionCookie = encryptSessionCookie;
        this.kekId = requireNonNull(kekId, "kekId");
        final List<KeyWrapper> keyWrappers = ImmutableList.copyOf(ServiceLoader.load(
                KeyWrapper.class, EncryptionStorageManager.class.getClassLoader()));
        if (keyWrappers.size() != 1) {
            throw new IllegalStateException(
                    "A single KeyWrapper implementation must be provided. found: " + keyWrappers);
        }
        final KeyWrapper keyWrapper = keyWrappers.get(0);

        rocksDbStorage = new RocksDBStorage(rocksDbPath);

        sessionKeyStorage = new SessionKeyStorage(rocksDbStorage, keyWrapper, kekId);
        repositoryEncryptionStorage = new RepositoryEncryptionStorage(rocksDbStorage, keyWrapper, kekId);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean encryptSessionCookie() {
        return encryptSessionCookie;
    }

    @Override
    public String kekId() {
        return kekId;
    }

    @Override
    public CompletableFuture<SessionMasterKey> generateSessionMasterKey(int version) {
        return sessionKeyStorage.generateSessionMasterKey(version);
    }

    @Override
    public void storeSessionMasterKey(SessionMasterKey sessionMasterKey) {
        sessionKeyStorage.storeSessionMasterKey(sessionMasterKey);
    }

    @Override
    public SessionMasterKey getCurrentSessionMasterKey() {
        return sessionKeyStorage.getCurrentSessionMasterKey();
    }

    @Override
    public CompletableFuture<SessionKey> getCurrentSessionKey() {
        return sessionKeyStorage.getCurrentSessionKey();
    }

    @Override
    public CompletableFuture<SessionKey> getSessionKey(int version) {
        return sessionKeyStorage.getSessionKey(version);
    }

    @Override
    public void rotateSessionMasterKey(SessionMasterKey sessionMasterKey) {
        sessionKeyStorage.rotateSessionMasterKey(sessionMasterKey);
    }

    @Override
    public CompletableFuture<String> generateWdek() {
        return repositoryEncryptionStorage.generateWdek();
    }

    @Override
    public List<WrappedDekDetails> wdeks() {
        return repositoryEncryptionStorage.wdeks();
    }

    @Override
    public SecretKey getDek(String projectName, String repoName, int version) {
        return repositoryEncryptionStorage.getDek(projectName, repoName, version);
    }

    @Override
    public SecretKeyWithVersion getCurrentDek(String projectName, String repoName) {
        return repositoryEncryptionStorage.getCurrentDek(projectName, repoName);
    }

    @Override
    public void storeWdek(WrappedDekDetails wdekDetails) {
        repositoryEncryptionStorage.storeWdek(wdekDetails);
    }

    @Override
    public void rotateWdek(WrappedDekDetails wdekDetails) {
        repositoryEncryptionStorage.rotateWdek(wdekDetails);
    }

    @Override
    public void removeWdek(String projectName, String repoName, int version) {
        repositoryEncryptionStorage.removeWdek(projectName, repoName, version);
    }

    @Override
    public byte[] getObject(byte[] key, byte[] metadataKey) {
        return repositoryEncryptionStorage.getObject(key, metadataKey);
    }

    @Override
    public byte[] getObjectId(byte[] key, byte[] metadataKey) {
        return repositoryEncryptionStorage.getObjectId(key, metadataKey);
    }

    @Override
    public byte[] getMetadata(byte[] metadataKey) {
        return repositoryEncryptionStorage.getMetadata(metadataKey);
    }

    @Override
    public void putObject(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value) {
        repositoryEncryptionStorage.putObject(metadataKey, metadataValue, key, value);
    }

    @Override
    public void putObjectId(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value,
                            @Nullable byte[] previousKeyToRemove) {
        repositoryEncryptionStorage.putObjectId(metadataKey, metadataValue, key, value, previousKeyToRemove);
    }

    @Override
    public boolean containsMetadata(byte[] key) {
        return repositoryEncryptionStorage.containsMetadata(key);
    }

    @Override
    public void deleteObjectId(byte[] metadataKey, byte[] key) {
        repositoryEncryptionStorage.deleteObjectId(metadataKey, key);
    }

    @Override
    public void deleteRepositoryData(String projectName, String repoName) {
        repositoryEncryptionStorage.deleteRepositoryData(projectName, repoName);
    }

    @Override
    public Map<String, Map<String, byte[]>> getAllData() {
        return repositoryEncryptionStorage.getAllData();
    }

    @Override
    public void addSessionKeyListener(Consumer<SessionKey> listener) {
        sessionKeyStorage.addSessionKeyListener(listener);
    }

    @Override
    public void close() {
        rocksDbStorage.close();
    }
}
