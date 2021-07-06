/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.utils.VisibleForTesting;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.StorageException;

/**
 * Simple file-based database that has the suffix of the primary Git repository.
 */
final class RepositoryMetadataDatabase implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryMetadataDatabase.class);

    private static final String INITIAL_PRIMARY_SUFFIX = "0000000000";

    private static final int PRIMARY_SUFFIX_LEN = INITIAL_PRIMARY_SUFFIX.length();

    private static final ThreadLocal<ByteBuffer> threadLocalBuffer =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(PRIMARY_SUFFIX_LEN));

    static File initialPrimaryRepoDir(File rootDir) {
        return repoDir(rootDir, INITIAL_PRIMARY_SUFFIX);
    }

    private static File repoDir(File rootDir, String suffix) {
        return new File(rootDir, rootDir.getName() + '_' + suffix);
    }

    private final File rootDir;
    private final Path path;
    private final FileChannel channel;
    @Nullable
    private volatile Revision headRevision;
    @Nullable
    private volatile Revision firstRevision;

    private String primarySuffix;

    RepositoryMetadataDatabase(File rootDir, boolean create) {
        this.rootDir = rootDir;
        path = new File(rootDir, "metadata.dat").toPath();
        try {
            if (create) {
                channel = FileChannel.open(path,
                                           StandardOpenOption.CREATE,
                                           StandardOpenOption.READ,
                                           StandardOpenOption.WRITE);
            } else {
                channel = FileChannel.open(path,
                                           StandardOpenOption.READ,
                                           StandardOpenOption.WRITE);
            }
        } catch (IOException e) {
            throw new StorageException("failed to open a repository database: " + path, e);
        }

        if (create) {
            writeSuffix(INITIAL_PRIMARY_SUFFIX);
            primarySuffix = INITIAL_PRIMARY_SUFFIX;
        } else {
            boolean success = false;
            try {
                final long size;
                try {
                    size = channel.size();
                } catch (IOException e) {
                    throw new StorageException("failed to get the file length: " + path, e);
                }

                if (size != PRIMARY_SUFFIX_LEN) {
                    throw new StorageException("incorrect file length: " + path + " (" + size + " bytes)");
                }

                final ByteBuffer buf = threadLocalBuffer.get();
                buf.clear();
                readTo(buf);
                buf.flip();
                primarySuffix = StandardCharsets.UTF_8.decode(buf).toString();
                // To check if the suffix is a correct integer value.
                // noinspection ResultOfMethodCallIgnored
                Integer.parseInt(primarySuffix);
                success = true;
            } finally {
                if (!success) {
                    close();
                }
            }
        }
    }

    private void writeSuffix(String suffix) {
        final ByteBuffer buf = threadLocalBuffer.get();
        buf.clear();
        buf.put(suffix.getBytes());
        buf.flip();

        long pos = 0;
        try {
            do {
                pos += channel.write(buf, pos);
            } while (buf.hasRemaining());
            channel.force(true);
        } catch (IOException e) {
            throw new StorageException("failed to update the suffix (" +  suffix +
                                       ") of the primary repository: " + path, e);
        }
    }

    private void readTo(ByteBuffer buf) {
        long pos = 0;
        try {
            do {
                final int readBytes = channel.read(buf, pos);
                if (readBytes < 0) {
                    throw new EOFException();
                }
                pos += readBytes;
            } while (buf.hasRemaining());
        } catch (IOException e) {
            throw new StorageException("failed to read the primary repository database: " + path, e);
        }
    }

    File primaryRepoDir() {
        return repoDir(rootDir, primarySuffix);
    }

    File secondaryRepoDir() {
        return repoDir(rootDir, addOne(primarySuffix));
    }

    void setPrimaryRepoDir(File newRepoDir) {
        final String newPrimarySuffix = addOne(primarySuffix);
        final File secondary = new File(rootDir, rootDir.getName() + '_' + newPrimarySuffix);
        assert newRepoDir.equals(secondaryRepoDir());
        primarySuffix = newPrimarySuffix;
        writeSuffix(newPrimarySuffix);
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            logger.warn("Failed to close the commit ID database: {}", path, e);
        }
    }

    void closeAndDelete() {
        try {
            channel.close();
        } catch (IOException e) {
            logger.warn("Failed to close the commit ID database: {}", path, e);
        }
        if (!path.toFile().delete()) {
            logger.warn("Failed to delete the commit ID database: {}", path);
        }
    }

    @VisibleForTesting
    static String addOne(String suffix) {
        final int intSuffix = Integer.parseInt(suffix);
        final String str = String.valueOf(intSuffix + 1);
        if (str.length() < 10) {
            return INITIAL_PRIMARY_SUFFIX.substring(0, 10 - str.length()) + str;
        }
        return str;
    }
}
