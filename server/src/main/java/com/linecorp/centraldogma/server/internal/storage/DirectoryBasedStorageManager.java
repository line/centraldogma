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

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.internal.Util;

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

    private final String childTypeName;
    private final Object[] childArgs;
    private final File rootDir;
    private final ConcurrentMap<String, T> children = new ConcurrentHashMap<>();
    private final AtomicReference<Supplier<CentralDogmaException>> closed = new AtomicReference<>();

    protected DirectoryBasedStorageManager(File rootDir, Class<? extends T> childType, Object... childArgs) {
        requireNonNull(rootDir, "rootDir");

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
        this.childArgs = requireNonNull(childArgs, "childArgs").clone();

        loadChildren();
    }

    protected Object childArg(int index) {
        return childArgs[index];
    }

    private void loadChildren() {
        boolean success = false;
        try {
            final File[] childFiles = rootDir.listFiles();
            if (childFiles != null) {
                for (File f : childFiles) {
                    loadChild(f);
                }
            }
            success = true;
        } finally {
            if (!success) {
                close(() -> new CentralDogmaException("should never reach here"));
            }
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
            final T child = openChild(f, childArgs);
            children.put(name, child);
            return child;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to open " + childTypeName + ": " + f, e);
        }
    }

    protected abstract T openChild(File childDir, Object[] childArgs) throws Exception;

    protected abstract T createChild(File childDir, Object[] childArgs,
                                     Author author, long creationTimeMillis) throws Exception;

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
            final T newChild = createChild(f, childArgs, author, creationTimeMillis);
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
                    logger.warn("Failed to delete a partially created project: {}", f);
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
    public Set<String> listRemoved() {
        ensureOpen();
        final Set<String> removed = new LinkedHashSet<>();
        final File[] files = rootDir.listFiles();
        if (files == null) {
            return Collections.emptySet();
        }

        Arrays.sort(files);

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

            removed.add(name);
        }

        return Collections.unmodifiableSet(removed);
    }

    @Override
    public void remove(String name) {
        ensureOpen();
        final T child = children.remove(validateChildName(name));
        if (child == null) {
            throw newStorageNotFoundException(name);
        }

        closeChild(name, child, () -> newStorageNotFoundException(name));

        if (!new File(rootDir, name).renameTo(new File(rootDir, name + SUFFIX_REMOVED))) {
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

        final T unremovedChild = loadChild(unremoved);
        if (unremovedChild == null) {
            throw newStorageNotFoundException(name);
        }
        return unremovedChild;
    }

    @Override
    public void ensureOpen() {
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

        return !name.endsWith(SUFFIX_REMOVED);
    }

    @Override
    public String toString() {
        return Util.simpleTypeName(getClass()) + '(' + rootDir + ')';
    }
}
