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
import java.util.regex.Pattern;

import com.linecorp.centraldogma.internal.Util;

public abstract class DirectoryBasedStorageManager<T> implements StorageManager<T> {

    /**
     * Start with an alphanumeric character.
     * An alphanumeric character, minus, plus, underscore and dot are allowed in the middle.
     * End with an alphanumeric character.
     */
    private static final Pattern CHILD_NAME =
            Pattern.compile("^[0-9A-Za-z](?:[-+_0-9A-Za-z\\.]*[0-9A-Za-z])?$");
    private static final String SUFFIX_REMOVED = ".removed";

    private final String childTypeName;
    private final Object[] childArgs;
    private final File rootDir;
    private final ConcurrentMap<String, T> children = new ConcurrentHashMap<>();
    private volatile boolean closed;

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
            for (File f : rootDir.listFiles()) {
                loadChild(f);
            }
            success = true;
        } finally {
            if (!success) {
                close();
            }
        }
    }

    private T loadChild(File f) {
        String name = f.getName();
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
            T child = openChild(f, childArgs);
            children.put(name, child);
            return child;
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to open " + childTypeName + ": " + f, e);
        }
    }

    protected abstract T openChild(File childDir, Object[] childArgs) throws Exception;

    protected abstract T createChild(File childDir, Object[] childArgs,
                                     long creationTimeMillis) throws Exception;

    private void closeChild(String name, T child) {
        closeChild(new File(rootDir, name), child);
    }

    protected void closeChild(File childDir, T child) {}

    protected abstract StorageExistsException newStorageExistsException(String name);

    protected abstract StorageNotFoundException newStorageNotFoundException(String name);

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;

        // Close all childrens.
        for (Map.Entry<String, T> e : children.entrySet()) {
            closeChild(e.getKey(), e.getValue());
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
    public T create(String name, long creationTimeMillis) {
        ensureOpen();
        validateChildName(name);

        AtomicBoolean created = new AtomicBoolean();
        T child = children.computeIfAbsent(name, n -> {
            T c = create0(n, creationTimeMillis);
            created.set(true);
            return c;
        });

        if (created.get()) {
            return child;
        } else {
            throw newStorageExistsException(childTypeName + ": " + name);
        }
    }

    private T create0(String name, long creationTimeMillis) {
        if (new File(rootDir, name + SUFFIX_REMOVED).exists()) {
            throw newStorageExistsException(childTypeName + ": " + name + " (removed)");
        }

        File f = new File(rootDir, name);
        try {
            return createChild(f, childArgs, creationTimeMillis);
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to create a new " + childTypeName + ": " + f, e);
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
            T v = children.get(k);
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
        T child = children.remove(validateChildName(name));
        if (child == null) {
            throw newStorageNotFoundException(childTypeName + ": " + name);
        }

        closeChild(name, child);

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
            throw newStorageNotFoundException(childTypeName + ": " + name);
        }

        final File unremoved = new File(rootDir, name);

        if (!removed.renameTo(unremoved)) {
            throw new StorageException("failed to mark " + childTypeName + " as unremoved: " + name);
        }

        return loadChild(unremoved);
    }

    @Override
    public void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("closed already");
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
