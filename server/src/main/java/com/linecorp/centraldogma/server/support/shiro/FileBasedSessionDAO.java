/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.centraldogma.server.support.shiro;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOError;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.CacheManager;
import org.apache.shiro.cache.CacheManagerAware;
import org.apache.shiro.io.SerializationException;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.UnknownSessionException;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

/**
 * Stores {@link Session}s in a file system.
 */
public class FileBasedSessionDAO implements SessionDAO, CacheManagerAware {

    private static final Logger logger = LoggerFactory.getLogger(FileBasedSessionDAO.class);

    private static final String CACHE_NAME = FileBasedSessionDAO.class.getSimpleName();

    private static final Supplier<String> nextSessionId = () -> UUID.randomUUID().toString();

    private static final int SESSION_ID_LENGTH = nextSessionId.get().length();
    private static final int SESSION_ID_1ST_PART_LENGTH = 2;
    private static final Pattern SESSION_ID_1ST_PART_PATTERN = Pattern.compile(
            "^[0-9a-f]{" + SESSION_ID_1ST_PART_LENGTH + "}$");
    private static final int SESSION_ID_2ND_PART_LENGTH = SESSION_ID_LENGTH - SESSION_ID_1ST_PART_LENGTH;
    private static final Pattern SESSION_ID_2ND_PART_PATTERN =
            Pattern.compile("^[-0-9a-f]{" + SESSION_ID_2ND_PART_LENGTH + "}$");
    private static final Pattern SESSION_ID_PATTERN =
            Pattern.compile("^[-0-9a-f]{" + SESSION_ID_LENGTH + "}$");

    private final Path rootDir;
    private final Path tmpDir;

    @VisibleForTesting
    Cache<String, SimpleSession> cache;

    /**
     * Creates a new instance.
     */
    public FileBasedSessionDAO(Path rootDir) throws IOException {
        requireNonNull(rootDir, "rootDir");
        this.rootDir = rootDir;
        tmpDir = rootDir.resolve("tmp");
        Files.createDirectories(tmpDir);
    }

    @Override
    public void setCacheManager(CacheManager cacheManager) {
        cache = requireNonNull(cacheManager, "cacheManager").getCache(CACHE_NAME);
    }

    /**
     * Returns whether the session with the given ID exists.
     */
    public boolean exists(String sessionId) {
        requireNonNull(sessionId, "sessionId");

        final SimpleSession cachedSession = getFromCache(sessionId);
        if (cachedSession != null) {
            return true;
        }

        return Files.isRegularFile(sessionId2Path(sessionId));
    }

    @Override
    public Serializable create(Session session) {
        final SimpleSession simpleSession = ensureSimpleSession(session);
        final boolean generateSessionId = simpleSession.getId() == null;
        for (;;) {
            final String sessionId = generateSessionId ? nextSessionId.get() : ensureStringSessionId(session);
            final Path newPath = sessionId2Path(sessionId);
            boolean success = false;
            try {
                // Create the parent directory if not exists.
                try {
                    Files.createDirectories(newPath.getParent());
                } catch (FileAlreadyExistsException e) {
                    throw new SerializationException(e);
                }

                final Path tmpPath = Files.createTempFile(tmpDir, null, null);
                simpleSession.setId(sessionId);
                Files.write(tmpPath, serialize(simpleSession));
                Files.move(tmpPath, newPath, StandardCopyOption.ATOMIC_MOVE);
                updateCache(sessionId, simpleSession);
                success = true;
                return sessionId;
            } catch (FileAlreadyExistsException unused) {
                // Session ID collision
                if (generateSessionId) {
                    // Try again with a newly generated ID.
                } else {
                    // Can't retry if session ID was specified.
                    throw new SerializationException("duplicate session: " + sessionId);
                }
            } catch (IOException e) {
                throw new SerializationException(e);
            } finally {
                if (!success) {
                    simpleSession.setId(null);
                }
            }
        }
    }

    @Override
    public SimpleSession readSession(Serializable sessionIdObj) {
        final String sessionId = ensureStringSessionId(sessionIdObj);
        final SimpleSession cachedSession = getFromCache(sessionId);
        if (cachedSession != null) {
            return cachedSession;
        }

        try {
            final SimpleSession session = uncachedRead(sessionId);
            updateCache(sessionId, session);
            return session;
        } catch (FileNotFoundException | NoSuchFileException unused) {
            // Unknown session
            throw new UnknownSessionException(sessionId);
        } catch (ClassNotFoundException | IOException e) {
            throw new SerializationException(e);
        }
    }

    @VisibleForTesting
    SimpleSession uncachedRead(String sessionId) throws IOException, ClassNotFoundException {
        return deserialize(Files.readAllBytes(sessionId2Path(sessionId)));
    }

    @Override
    public void update(Session session) {
        final String sessionId = ensureStringSessionId(session);
        final Path oldPath = sessionId2Path(sessionId);
        if (!Files.exists(oldPath)) {
            throw new UnknownSessionException(sessionId);
        }

        try {
            final Path newPath = Files.createTempFile(tmpDir, null, null);
            Files.write(newPath, serialize(session));
            Files.move(newPath, oldPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new SerializationException(e);
        }
    }

    @Override
    public void delete(Session session) {
        final String sessionId = ensureStringSessionId(session);
        delete(sessionId);
    }

    /**
     * Deletes the session with the given ID.
     */
    public void delete(String sessionId) {
        try {
            Files.delete(sessionId2Path(sessionId));
            deleteFromCache(sessionId);
        } catch (NoSuchFileException ignored) {
            // Unknown session
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    private SimpleSession getFromCache(String sessionId) {
        final Cache<String, SimpleSession> cache = this.cache;
        return cache != null ? cache.get(sessionId) : null;
    }

    private void updateCache(String sessionId, SimpleSession session) {
        final Cache<String, SimpleSession> cache = this.cache;
        if (cache != null) {
            cache.put(sessionId, session);
        }
    }

    private void deleteFromCache(String sessionId) {
        final Cache<String, SimpleSession> cache = this.cache;
        if (cache != null) {
            cache.remove(sessionId);
        }
    }

    @Override
    public Collection<Session> getActiveSessions() {
        // Here, we rely on the implementation detail of AbstractValidationSessionManager.validateSessions(),
        // which calls no other methods than isEmpty() and iterator() on the Collection returned by this method.
        return new AbstractCollection<Session>() {
            @Override
            public Iterator<Session> iterator() {
                final Stream<Path> stream;
                try {
                    stream = Files.walk(rootDir, 2);
                } catch (IOException e) {
                    throw new IOError(e);
                }

                return stream.map(path -> {
                    if (!isSessionFile(path)) {
                        // Not a session file.
                        return null;
                    }

                    try {
                        return (Session) deserialize(Files.readAllBytes(path));
                    } catch (FileNotFoundException | NoSuchFileException ignored) {
                        // - Session deleted by other party.
                    } catch (Exception e) {
                        logger.warn("Failed to deserialize a session: {}", path, e);
                    }
                    return null;
                }).filter(Objects::nonNull).iterator();
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public int size() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private Path sessionId2Path(String sessionId) {
        return rootDir.resolve(sessionId.substring(0, SESSION_ID_1ST_PART_LENGTH))
                      .resolve(sessionId.substring(SESSION_ID_1ST_PART_LENGTH));
    }

    private static boolean isSessionFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        final int nameCount = path.getNameCount();
        if (nameCount < 2) {
            return false;
        }

        final String first = path.getName(nameCount - 2).toString();
        if (!SESSION_ID_1ST_PART_PATTERN.matcher(first).matches()) {
            return false;
        }

        final String second = path.getName(nameCount - 1).toString();
        return SESSION_ID_2ND_PART_PATTERN.matcher(second).matches();
    }

    private static SimpleSession ensureSimpleSession(Session session) {
        requireNonNull(session, "session");
        checkArgument(session instanceof SimpleSession,
                      "session: %s (expected: SimpleSession)", session);
        return (SimpleSession) session;
    }

    private static String ensureStringSessionId(Session session) {
        return ensureStringSessionId(requireNonNull(session, "session").getId());
    }

    private static String ensureStringSessionId(Serializable sessionId) {
        requireNonNull(sessionId, "sessionId");
        checkArgument(sessionId instanceof String,
                      "sessionId: %s (expected: String)", sessionId);
        checkArgument(SESSION_ID_PATTERN.matcher((CharSequence) sessionId).matches(),
                      "sessionId: %s (expected: UUID)", sessionId);
        return (String) sessionId;
    }

    /**
     * Serializes a {@link Session} into a byte array using an {@link ObjectOutputStream}.
     */
    static byte[] serialize(Session session) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(session);
            oos.flush();
            return baos.toByteArray();
        }
    }

    /**
     * Deserializes a {@link Session} from a byte array using an {@link ObjectInputStream}.
     */
    static SimpleSession deserialize(byte[] encoded) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(encoded);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (SimpleSession) ois.readObject();
        }
    }
}
