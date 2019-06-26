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

package com.linecorp.centraldogma.server.internal.storage;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.StorageManager;

public abstract class DirectoryBasedStorageManager<T> implements StorageManager<T> {

    private static final Logger logger = LoggerFactory.getLogger(DirectoryBasedStorageManager.class);

    /**
     * Start with an alphanumeric character.
     * An alphanumeric character, minus, plus, underscore and dot are allowed in the middle.
     * End with an alphanumeric character.
     */
    private static final Pattern CHILD_NAME =
            Pattern.compile("^[0-9A-Za-z](?:[-+_0-9A-Za-z.]*[0-9A-Za-z])?$");
    private static final String SUFFIX_REMOVED = ".removed";
    private static final String SUFFIX_PURGED = ".purged";

    private final String childTypeName;
    private final File rootDir;
    private final StorageRemovalManager storageRemovalManager = new StorageRemovalManager();
    private final ConcurrentMap<String, T> children = new ConcurrentHashMap<>();
    private final AtomicReference<Supplier<CentralDogmaException>> closed = new AtomicReference<>();
    private final Executor purgeWorker;
    private boolean initialized;

    protected DirectoryBasedStorageManager(File rootDir, Class<? extends T> childType,
                                           Executor purgeWorker) {
        requireNonNull(rootDir, "rootDir");
        this.purgeWorker = requireNonNull(purgeWorker, "purgeWorker");

        if (!rootDir.exists()) {
            if (!rootDir.mkdirs()) {
                throw new StorageException("failed to create root directory at " + rootDir);
            }
        }

        try {
            rootDir = rootDir.getCanonicalFile();
        } catch (IOException e) {
            throw new StorageException("failed to get the canonical path of: " + rootDir, e);
        }

        if (!rootDir.isDirectory()) {
            throw new StorageException("not a directory: " + rootDir);
        }

        this.rootDir = rootDir;
        childTypeName = Util.simpleTypeName(requireNonNull(childType, "childTypeName"), true);
    }

    protected Executor purgeWorker() {
        return purgeWorker;
    }

    /**
     * Initializes this {@link StorageManager} by loading all children.
     */
    protected final void init() {
        checkState(!initialized, "initialized already");
        Throwable cause = null;
        try {
            final File[] childFiles = rootDir.listFiles();
            if (childFiles != null) {
                for (File f : childFiles) {
                    loadChild(f);
                }
            }
            initialized = true;
        } catch (Throwable t) {
            cause = t;
        }

        if (cause != null) {
            final CentralDogmaException finalCause;
            if (cause instanceof CentralDogmaException) {
                finalCause = (CentralDogmaException) cause;
            } else {
                finalCause = new CentralDogmaException("Failed to load a child: " + cause, cause);
            }
            close(() -> finalCause);
            throw finalCause;
        }
    }

    @Nullable
    private T loadChild(File f) {
        final String name = f.getName();
        if (!isValidChildName(name)) {
            return null;
        }

        if (!f.isDirectory()) {
            return null;
        }

        if (new File(f + SUFFIX_REMOVED).exists()) {
            return null;
        }

        try {
            final T child = openChild(f);
            children.put(name, child);
            return child;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to open " + childTypeName + ": " + f, e);
        }
    }

    protected abstract T openChild(File childDir) throws Exception;

    protected abstract T createChild(File childDir, Author author, long creationTimeMillis) throws Exception;

    private void closeChild(String name, T child, Supplier<CentralDogmaException> failureCauseSupplier) {
        closeChild(new File(rootDir, name), child, failureCauseSupplier);
    }

    protected void closeChild(File childDir, T child, Supplier<CentralDogmaException> failureCauseSupplier) {}

    protected abstract CentralDogmaException newStorageExistsException(String name);

    protected abstract CentralDogmaException newStorageNotFoundException(String name);

    @Override
    public void close(Supplier<CentralDogmaException> failureCauseSupplier) {
        requireNonNull(failureCauseSupplier, "failureCauseSupplier");
        if (!closed.compareAndSet(null, failureCauseSupplier)) {
            return;
        }

        // Close all children.
        for (Map.Entry<String, T> e : children.entrySet()) {
            closeChild(e.getKey(), e.getValue(), failureCauseSupplier);
        }
    }

    @Override
    public boolean exists(String name) {
        ensureOpen();
        return children.containsKey(validateChildName(name));
    }

    @Override
    public T get(String name) {
        ensureOpen();
        final T child = children.get(validateChildName(name));
        if (child == null) {
            throw newStorageNotFoundException(name);
        }

        return child;
    }

    @Override
    public T create(String name, long creationTimeMillis, Author author) {
        ensureOpen();
        requireNonNull(author, "author");
        validateChildName(name);

        final AtomicBoolean created = new AtomicBoolean();
        final T child = children.computeIfAbsent(name, n -> {
            final T c = create0(author, n, creationTimeMillis);
            created.set(true);
            return c;
        });

        if (created.get()) {
            return child;
        } else {
            throw newStorageExistsException(name);
        }
    }

    private T create0(Author author, String name, long creationTimeMillis) {
        if (new File(rootDir, name + SUFFIX_REMOVED).exists()) {
            throw newStorageExistsException(name + " (removed)");
        }

        final File f = new File(rootDir, name);
        boolean success = false;
        try {
            final T newChild = createChild(f, author, creationTimeMillis);
            success = true;
            return newChild;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to create a new " + childTypeName + ": " + f, e);
        } finally {
            if (!success && f.exists()) {
                // Attempt to delete a partially created project.
                try {
                    Util.deleteFileTree(f);
                } catch (IOException e) {
                    logger.warn("Failed to delete a partially created project: {}", f, e);
                }
            }
        }
    }

    @Override
    public Map<String, T> list() {
        ensureOpen();

        final int estimatedSize = children.size();
        final String[] names = children.keySet().toArray(new String[estimatedSize]);
        Arrays.sort(names);

        final Map<String, T> ret = new LinkedHashMap<>(estimatedSize);
        for (String k : names) {
            final T v = children.get(k);
            if (v != null) {
                ret.put(k, v);
            }
        }

        return Collections.unmodifiableMap(ret);
    }

    @Override
    public Map<String, Instant> listRemoved() {
        ensureOpen();
        final File[] files = rootDir.listFiles();
        if (files == null) {
            return ImmutableMap.of();
        }

        Arrays.sort(files);
        final ImmutableMap.Builder<String, Instant> builder = ImmutableMap.builder();

        for (File f : files) {
            if (!f.isDirectory()) {
                continue;
            }

            String name = f.getName();
            if (!name.endsWith(SUFFIX_REMOVED)) {
                continue;
            }

            name = name.substring(0, name.length() - SUFFIX_REMOVED.length());
            if (!isValidChildName(name) || children.containsKey(name)) {
                continue;
            }

            builder.put(name, storageRemovalManager.readRemoval(f));
        }

        return builder.build();
    }

    @Override
    public void remove(String name) {
        ensureOpen();
        final T child = children.remove(validateChildName(name));
        if (child == null) {
            throw newStorageNotFoundException(name);
        }

        closeChild(name, child, () -> newStorageNotFoundException(name));

        final File file = new File(rootDir, name);
        storageRemovalManager.mark(file);
        if (!file.renameTo(new File(rootDir, name + SUFFIX_REMOVED))) {
            throw new StorageException("failed to mark " + childTypeName + " as removed: " + name);
        }
    }

    @Override
    public T unremove(String name) {
        ensureOpen();
        validateChildName(name);

        final File removed = new File(rootDir, name + SUFFIX_REMOVED);
        if (!removed.isDirectory()) {
            throw newStorageNotFoundException(name);
        }

        final File unremoved = new File(rootDir, name);

        if (!removed.renameTo(unremoved)) {
            throw new StorageException("failed to mark " + childTypeName + " as unremoved: " + name);
        }
        storageRemovalManager.unmark(unremoved);

        final T unremovedChild = loadChild(unremoved);
        if (unremovedChild == null) {
            throw newStorageNotFoundException(name);
        }
        return unremovedChild;
    }

    @Override
    public void markForPurge(String name) {
        ensureOpen();
        validateChildName(name);
        File marked;
        final File removed = new File(rootDir, name + SUFFIX_REMOVED);

        final Supplier<File> newMarkedFile = () -> {
            final String interfix = '.' + Long.toHexString(ThreadLocalRandom.current().nextLong());
            return new File(rootDir, name + interfix + SUFFIX_PURGED);
        };

        synchronized (this) {
            if (!removed.exists() || !removed.isDirectory()) {
                throw newStorageNotFoundException(name + SUFFIX_REMOVED);
            }

            marked = newMarkedFile.get();
            boolean moved = false;
            while (!moved) {
                try {
                    Files.move(removed.toPath(), marked.toPath());
                    moved = true;
                } catch (FileAlreadyExistsException e) {
                    marked = newMarkedFile.get();
                } catch (IOException e) {
                    throw new StorageException("failed to mark " + childTypeName + " for purge: " + removed, e);
                }
            }
        }
        final File purged = marked;
        purgeWorker.execute(() -> deletePurgedFile(purged));
    }

    @Override
    public void purgeMarked() {
        ensureOpen();
        final File[] files = rootDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (!f.isDirectory()) {
                continue;
            }
            final String name = f.getName();
            if (name.endsWith(SUFFIX_PURGED)) {
                deletePurgedFile(f);
            }
        }
    }

    private void deletePurgedFile(File file) {
        try {
            logger.info("Deleting a purged {}: {} ..", childTypeName, file);
            Util.deleteFileTree(file);
            logger.info("Deleted a purged {}: {}.", childTypeName, file);
        } catch (IOException e) {
            logger.warn("Failed to delete a purged {}: {}", childTypeName, file, e);
        }
    }

    @Override
    public void ensureOpen() {
        checkState(initialized, "not initialized yet");
        if (closed.get() != null) {
            throw closed.get().get();
        }
    }

    private String validateChildName(String name) {
        if (!isValidChildName(requireNonNull(name, "name"))) {
            throw new IllegalArgumentException("invalid " + childTypeName + " name: " + name);
        }
        return name;
    }

    private static boolean isValidChildName(String name) {
        if (name == null) {
            return false;
        }

        if (!CHILD_NAME.matcher(name).matches()) {
            return false;
        }

        return !name.endsWith(SUFFIX_REMOVED) && !name.endsWith(SUFFIX_PURGED);
    }

    @Override
    public String toString() {
        return Util.simpleTypeName(getClass()) + '(' + rootDir + ')';
    }

    private final class StorageRemovalManager {

        private static final String REMOVAL_TIMESTAMP_NAME = "removal.timestamp";

        void mark(File file) {
            final File removal = new File(file, REMOVAL_TIMESTAMP_NAME);
            final String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            try {
                Files.write(removal.toPath(), timestamp.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new StorageException(
                        "failed to write a removal timestamp for " + childTypeName + ": " + removal);
            }
        }

        void unmark(File file) {
            final File removal = new File(file, REMOVAL_TIMESTAMP_NAME);
            if (removal.exists()) {
                if (!removal.delete()) {
                    logger.warn("Failed to delete a removal timestamp for {}: {}", childTypeName, removal);
                }
            }
        }

        Instant readRemoval(File file) {
            final File removal = new File(file, REMOVAL_TIMESTAMP_NAME);
            if (!removal.exists()) {
                return Instant.ofEpochMilli(file.lastModified());
            }
            try {
                final String timestamp = new String(Files.readAllBytes(removal.toPath()),
                                                    StandardCharsets.UTF_8);
                return Instant.from(DateTimeFormatter.ISO_INSTANT.parse(timestamp));
            } catch (Exception e) {
                logger.warn("Failed to read a removal timestamp for {}: {}", childTypeName, removal, e);
                return Instant.ofEpochMilli(file.lastModified());
            }
        }
    }
}
