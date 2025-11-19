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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.server.storage.encryption.RocksDBStorage.WDEK_COLUMN_FAMILY;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.auth.SessionKey;
import com.linecorp.centraldogma.server.auth.SessionMasterKey;
import com.linecorp.centraldogma.server.internal.storage.AesGcmSivCipher;

final class SessionKeyStorage {

    private static final Logger logger = LoggerFactory.getLogger(SessionKeyStorage.class);

    private final RocksDBStorage rocksDbStorage;
    private final KeyWrapper keyWrapper;
    private final String kekId;

    private final ReentrantShortLock lock = new ReentrantShortLock();
    @Nullable
    @GuardedBy("lock")
    private SessionKey currentSessionKey;
    private final Map<Integer, CompletableFuture<SessionKey>> sessionKeys = new ConcurrentHashMap<>();
    @GuardedBy("lock")
    private final List<Consumer<SessionKey>> listeners = new ArrayList<>();

    SessionKeyStorage(RocksDBStorage rocksDbStorage, KeyWrapper keyWrapper, String kekId) {
        this.rocksDbStorage = requireNonNull(rocksDbStorage, "rocksDbStorage");
        this.keyWrapper = requireNonNull(keyWrapper, "keyWrapper");
        this.kekId = requireNonNull(kekId, "kekId");
    }

    CompletableFuture<SessionMasterKey> generateSessionMasterKey(int version) {
        final byte[] dek = AesGcmSivCipher.generateAes256Key();
        return keyWrapper.wrap(dek, kekId).thenApply(wrappedMasterKey -> {
            // Generate the same size of salt: https://datatracker.ietf.org/doc/html/rfc5869#section-3.1
            // It doesn't have to be a secret value.
            final byte[] salt = AesGcmSivCipher.generateAes256Key();
            return new SessionMasterKey(wrappedMasterKey, version, Base64.getEncoder().encodeToString(salt),
                                        kekId, Instant.now());
        });
    }

    void storeSessionMasterKey(SessionMasterKey sessionMasterKey) {
        storeSessionMasterKey(sessionMasterKey, false);
    }

    void storeSessionMasterKey(SessionMasterKey sessionMasterKey, boolean rotate) {
        requireNonNull(sessionMasterKey, "sessionMasterKey");
        final int version = sessionMasterKey.version();
        if (rotate) {
            final SessionMasterKey currentSessionMasterKey = getCurrentSessionMasterKey();
            if (currentSessionMasterKey.version() + 1 != version) {
                throw new IllegalArgumentException(
                        "The version of the new session master key (" + version +
                        ") must be exactly one greater than the current version (" +
                        currentSessionMasterKey.version() + ')');
            }
        }

        final byte[] masterKeyKey = sessionMasterKeyKey(version);
        try {
            final byte[] existing = rocksDbStorage.get(WDEK_COLUMN_FAMILY, masterKeyKey);
            if (existing != null) {
                throw new EncryptionEntryExistsException(
                        "Session master key of version " + version + " already exists");
            }
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to check the existence of session master key of version " + version, e);
        }

        final byte[] sessionMasterKeyBytes;
        try {
            sessionMasterKeyBytes = Jackson.writeValueAsBytes(sessionMasterKey);
        } catch (JsonProcessingException e) {
            throw new EncryptionStorageException(
                    "Failed to serialize wrapped session master key of version " + version, e);
        }

        try (WriteBatch writeBatch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            writeOptions.setSync(true);
            writeBatch.put(rocksDbStorage.getColumnFamilyHandle(WDEK_COLUMN_FAMILY),
                           masterKeyKey, sessionMasterKeyBytes);
            writeBatch.put(rocksDbStorage.getColumnFamilyHandle(WDEK_COLUMN_FAMILY),
                           currentSessionMasterKeyVersionKey(), Ints.toByteArray(version));
            rocksDbStorage.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to store session master key of version " + version, e);
        }

        maybeSetCurrentSessionKey(sessionMasterKey);
    }

    SessionMasterKey getCurrentSessionMasterKey() {
        final int version;
        try {
            final byte[] versionBytes = rocksDbStorage.get(WDEK_COLUMN_FAMILY,
                                                           currentSessionMasterKeyVersionKey());
            if (versionBytes == null) {
                throw new EncryptionEntryNoExistException("Current session master key does not exist");
            }
            version = Ints.fromByteArray(versionBytes);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException("Failed to get the current session master key version", e);
        }

        return getSessionMasterKey(version);
    }

    private SessionMasterKey getSessionMasterKey(int version) {
        try {
            final byte[] wrappedMasterKeyBytes = rocksDbStorage.get(WDEK_COLUMN_FAMILY,
                                                                    sessionMasterKeyKey(version));
            if (wrappedMasterKeyBytes == null) {
                throw new EncryptionStorageException(
                        "Session master key of version " + version + " does not exist");
            }

            return Jackson.readValue(wrappedMasterKeyBytes, SessionMasterKey.class);
        } catch (RocksDBException e) {
            throw new EncryptionStorageException(
                    "Failed to get the session master key of version " + version, e);
        } catch (JsonParseException | JsonMappingException e) {
            throw new EncryptionStorageException(
                    "Failed to read the session master key of version " + version, e);
        }
    }

    CompletableFuture<SessionKey> getCurrentSessionKey() {
        lock.lock();
        try {
            if (currentSessionKey != null) {
                return CompletableFuture.completedFuture(currentSessionKey);
            }
        } finally {
            lock.unlock();
        }
        final SessionMasterKey sessionMasterKey = getCurrentSessionMasterKey();
        return maybeSetCurrentSessionKey(sessionMasterKey);
    }

    private CompletableFuture<SessionKey> maybeSetCurrentSessionKey(SessionMasterKey sessionMasterKey) {
        return keyWrapper.unwrap(sessionMasterKey.wrappedMasterKey(), sessionMasterKey.kekId())
                         .thenApply(unwrapped -> {
                             final SessionKey sessionKey = SessionKey.of(unwrapped, sessionMasterKey);
                             lock.lock();
                             try {
                                 if (currentSessionKey != null) {
                                     if (currentSessionKey.version() >= sessionKey.version()) {
                                         return currentSessionKey;
                                     }
                                 }
                                 currentSessionKey = sessionKey;
                                 for (Consumer<SessionKey> listener : listeners) {
                                     listener.accept(sessionKey);
                                 }
                                 return sessionKey;
                             } finally {
                                 lock.unlock();
                             }
                         });
    }

    public CompletableFuture<SessionKey> getSessionKey(int version) {
        final CompletableFuture<SessionKey> result = sessionKeys.computeIfAbsent(version, v -> {
            final SessionMasterKey sessionMasterKey = getSessionMasterKey(v);
            final CompletableFuture<SessionKey> future = new CompletableFuture<>();
            keyWrapper.unwrap(sessionMasterKey.wrappedMasterKey(), sessionMasterKey.kekId())
                      .handle((unwrapped, cause) -> {
                          if (cause != null) {
                              future.completeExceptionally(cause);
                              return null;
                          } else {
                              future.complete(SessionKey.of(unwrapped, sessionMasterKey));
                              return null;
                          }
                      });
            return future;
        });
        result.exceptionally(unused -> {
            sessionKeys.remove(version);
            return null;
        });
        return result;
    }

    void rotateSessionMasterKey(SessionMasterKey sessionMasterKey) {
        storeSessionMasterKey(sessionMasterKey, true);
    }

    private static byte[] sessionMasterKeyKey(int version) {
        return ("session/master/" + version).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] currentSessionMasterKeyVersionKey() {
        return "session/master/current".getBytes(StandardCharsets.UTF_8);
    }

    void addSessionKeyListener(Consumer<SessionKey> listener) {
        requireNonNull(listener, "listener");
        lock.lock();
        try {
            listeners.add(listener);
            if (currentSessionKey != null) {
                listener.accept(currentSessionKey);
            }
        } finally {
            lock.unlock();
        }
    }

    CompletableFuture<Void> rewrapAllSessionMasterKeys() {
        final List<SessionMasterKey> allSessionMasterKeys = new ArrayList<>();
        final byte[] prefix = "session/master/".getBytes(StandardCharsets.UTF_8);
        try (RocksIterator iterator = rocksDbStorage.newIterator(
                rocksDbStorage.getColumnFamilyHandle(WDEK_COLUMN_FAMILY))) {
            iterator.seek(prefix);
            while (iterator.isValid()) {
                final byte[] key = iterator.key();
                final String keyStr = new String(key, StandardCharsets.UTF_8);

                if (!keyStr.startsWith("session/master/")) {
                    break;
                }

                if ("session/master/current".equals(keyStr)) {
                    iterator.next();
                    continue;
                }

                try {
                    final SessionMasterKey sessionMasterKey =
                            Jackson.readValue(iterator.value(), SessionMasterKey.class);
                    allSessionMasterKeys.add(sessionMasterKey);
                } catch (JsonParseException | JsonMappingException e) {
                    throw new EncryptionStorageException(
                            "Failed to read session master key: " + keyStr, e);
                }

                iterator.next();
            }
        }

        final List<CompletableFuture<SessionMasterKey>> rewrapFutures = new ArrayList<>();
        for (SessionMasterKey sessionMasterKey : allSessionMasterKeys) {
            final String oldKekId = sessionMasterKey.kekId();
            final CompletableFuture<SessionMasterKey> rewrapFuture =
                    keyWrapper.rewrap(sessionMasterKey.wrappedMasterKey(), oldKekId, kekId)
                              .handle((newWrappedKey, cause) -> {
                                  if (cause != null) {
                                      logger.warn("Failed to rewrap session master key. version: {}",
                                                  sessionMasterKey.version(), cause);
                                      return null;
                                  }
                                  if (oldKekId.equals(kekId) &&
                                      sessionMasterKey.wrappedMasterKey().equals(newWrappedKey)) {
                                      return null;
                                  }

                                  return new SessionMasterKey(newWrappedKey,
                                                              sessionMasterKey.version(),
                                                              sessionMasterKey.salt(),
                                                              kekId,
                                                              sessionMasterKey.creationInstant());
                              });
            rewrapFutures.add(rewrapFuture);
        }

        return CompletableFuture.allOf(rewrapFutures.toArray(new CompletableFuture[0]))
                                .thenAccept(unused -> {
                                    final List<SessionMasterKey> collected = rewrapFutures.stream().map(
                                            CompletableFuture::join).filter(Objects::nonNull).collect(
                                            toImmutableList());
                                    if (collected.isEmpty()) {
                                        logger.info("All session master keys are already wrapped " +
                                                    "with the current KEK. {}", kekId);
                                        return;
                                    }

                                    try (WriteBatch writeBatch = new WriteBatch();
                                         WriteOptions writeOptions = new WriteOptions()) {
                                        writeOptions.setSync(true);

                                        for (SessionMasterKey newSessionMasterKey : collected) {
                                            final byte[] sessionMasterKeyBytes;
                                            try {
                                                sessionMasterKeyBytes =
                                                        Jackson.writeValueAsBytes(newSessionMasterKey);
                                            } catch (JsonProcessingException e) {
                                                logger.warn("Failed to serialize re-wrapped " +
                                                            "session master key. version: {}",
                                                            newSessionMasterKey.version(), e);
                                                continue;
                                            }
                                            final byte[] keyBytes =
                                                    sessionMasterKeyKey(newSessionMasterKey.version());
                                            writeBatch.put(
                                                    rocksDbStorage.getColumnFamilyHandle(WDEK_COLUMN_FAMILY),
                                                    keyBytes, sessionMasterKeyBytes);
                                        }

                                        rocksDbStorage.write(writeOptions, writeBatch);
                                    } catch (RocksDBException e) {
                                        throw new EncryptionStorageException(
                                                "Failed to store re-wrapped session master keys", e);
                                    }
                                });
    }
}
