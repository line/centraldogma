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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import javax.annotation.Nullable;

import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.StorageException;

/**
 * A file-based database that reads and writes the last {@link Revision} when git gc was run.
 */
final class GitGcRevision implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(GitGcRevision.class);

    private static final int REVISION_LEN = 4; // 32-bit integer + 160-bit SHA1 hash

    private static final ThreadLocal<ByteBuffer> threadLocalBuffer =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(REVISION_LEN));

    private final Path path;
    private final FileChannel channel;
    private volatile Revision lastRevision;

    GitGcRevision(Repository repo) {
        // NB: We enable fsync only when our Git repository has been configured so,
        //     because there's no point of doing fsync only on this file when the
        //     Git repository does not.
        this(repo.getDirectory());
    }

    @VisibleForTesting
    GitGcRevision(File rootDir) {
        this(rootDir, false);
    }

    private GitGcRevision(File rootDir, boolean fsync) {
        path = new File(rootDir, "git-gc.revision").toPath();
        try {
            channel = FileChannel.open(path,
                                       StandardOpenOption.CREATE,
                                       StandardOpenOption.READ,
                                       StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new StorageException("failed to open the last git revision: " + path, e);
        }

        this.fsync = fsync;
        boolean success = false;
        try {
            final long size;
            try {
                size = channel.size();
            } catch (IOException e) {
                throw new StorageException("failed to get the file length: " + path, e);
            }

            if (size == 0) {
                lastRevision = null;
            } else if (size == REVISION_LEN){
                lastRevision = read();
            } else {
                throw new StorageException("incorrect revision length: " + path + " (" + size + " bytes)");
            }
            success = true;
        } finally {
            if (!success) {
                close();
            }
        }
    }

    @Nullable
    Revision lastRevision() {
        return lastRevision;
    }

    private Revision read() {
        final ByteBuffer buf = threadLocalBuffer.get();
        buf.clear();
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
            throw new StorageException("failed to read the last git gc revision: " + path, e);
        }

        buf.flip();
        return new Revision(buf.getInt());
    }

    void write(Revision gcRevision) {
        if (lastRevision != null && lastRevision.major() > gcRevision.major()) {
            // less than the last gc revision.
            return;
        }

        // Build a record.
        final ByteBuffer buf = threadLocalBuffer.get();
        buf.clear();
        buf.putInt(gcRevision.major());
        buf.flip();

        // Overwrite the gc revision to the file.
        long pos = 0;
        try {
            do {
                pos += channel.write(buf, pos);
            } while (buf.hasRemaining());
        } catch (IOException e) {
            throw new StorageException("failed to update the last gc revision: " + path, e);
        }

        if (lastRevision == null ||
            lastRevision.major() < gcRevision.major()) {
            lastRevision = gcRevision;
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            logger.warn("Failed to close the commit ID database: {}", path, e);
        }
    }
}
