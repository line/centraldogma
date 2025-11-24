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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.server.auth.SessionKey;
import com.linecorp.centraldogma.server.auth.SessionMasterKey;

/**
 * A no-operation implementation of {@link EncryptionStorageManager} that does not perform any encryption.
 */
public enum NoopEncryptionStorageManager implements EncryptionStorageManager {

    /**
     * Singleton instance of {@link NoopEncryptionStorageManager}.
     */
    INSTANCE;

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public boolean encryptSessionCookie() {
        return false;
    }

    @Override
    public String kekId() {
        return "";
    }

    @Override
    public CompletableFuture<String> generateWdek() {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<SessionMasterKey> generateSessionMasterKey(int version) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public void storeSessionMasterKey(SessionMasterKey sessionMasterKey) {
        // No-op
    }

    @Nullable
    @Override
    public SessionMasterKey getCurrentSessionMasterKey() {
        return null;
    }

    @Override
    public CompletableFuture<SessionKey> getCurrentSessionKey() {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<SessionKey> getSessionKey(int version) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public void rotateSessionMasterKey(SessionMasterKey sessionMasterKey) {
        // No-op
    }

    @Override
    public List<WrappedDekDetails> wdeks() {
        return ImmutableList.of();
    }

    @Nullable
    @Override
    public SecretKey getDek(String projectName, String repoName, int i) {
        return null;
    }

    @Nullable
    @Override
    public SecretKeyWithVersion getCurrentDek(String projectName, String repoName) {
        return null;
    }

    @Override
    public void storeWdek(WrappedDekDetails wdekDetails) {
    }

    @Override
    public void rotateWdek(WrappedDekDetails wdekDetails) {
    }

    @Override
    public void removeWdek(String projectName, String repoName, int version, boolean removeCurrent) {
    }

    @Override
    public byte[] getObject(byte[] key, byte[] metadataKey) {
        return new byte[0];
    }

    @Override
    public byte[] getObjectId(byte[] key, byte[] metadataKey) {
        return new byte[0];
    }

    @Override
    public byte[] getMetadata(byte[] metadataKey) {
        return null;
    }

    @Override
    public void putObject(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value) {
        // No-op
    }

    @Override
    public void putObjectId(byte[] metadataKey, byte[] metadataValue, byte[] key, byte[] value,
                            @Nullable byte[] previousKeyToRemove) {
        // No-op
    }

    @Override
    public boolean containsMetadata(byte[] key) {
        return false;
    }

    @Override
    public void deleteObjectId(byte[] metadataKey, byte[] key) {
        // No-op
    }

    @Override
    public void deleteRepositoryData(String projectName, String repoName) {
        // No-op
    }

    @Override
    public Map<String, Map<String, byte[]>> getAllData() {
        return ImmutableMap.of();
    }

    @Override
    public void addSessionKeyListener(Consumer<SessionKey> listener) {
        // No-op
    }

    @Override
    public void addCurrentDekListener(String projectName, String repoName,
                                      Consumer<SecretKeyWithVersion> listener) {
        // No-op
    }

    @Override
    public void removeCurrentDekListener(String projectName, String repoName) {
        // No-op
    }

    @Override
    public CompletableFuture<Void> rewrapAllKeys(Executor executor) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        // No-op
    }
}
