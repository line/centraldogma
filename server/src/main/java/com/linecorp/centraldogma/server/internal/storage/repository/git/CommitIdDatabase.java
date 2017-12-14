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
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.R_HEADS_MASTER;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_CORE_SECTION;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import javax.annotation.Nullable;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.StorageException;
import com.linecorp.centraldogma.server.internal.storage.repository.RevisionNotFoundException;

/**
 * Simple file-based database of {@link Revision}-to-{@link ObjectId} mappings.
 *
 * <h3>File layout</h3>
 *
 * <pre>{@code
 * database = record*
 * record = revision commitId (24 bytes)
 * revision = 32-bit signed big-endian integer (4 bytes)
 * commitId = 160-bit SHA1 hash (20 bytes)
 * }</pre>
 *
 * {@link CommitIdDatabase} makes use of the invariant where:
 * <ul>
 *   <li>A {@link Revision} in a repository always starts from 1 and monotonically increases by 1.</li>
 *   <li>A record has fixed length of 24 bytes.</li>
 * </ul>
 * Therefore, {@link #put(Revision, ObjectId)} is always appending at the end of the database file and
 * {@link #get(Revision)} is always reading a record at the offset {@code (revision - 1) * 24}.
 */
final class CommitIdDatabase implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(CommitIdDatabase.class);

    private static final int RECORD_LEN = 4 + 20; // 32-bit integer + 160-bit SHA1 hash

    private static final ThreadLocal<ByteBuffer> threadLocalBuffer =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(RECORD_LEN));

    private final Path path;
    private final FileChannel channel;
    private final boolean fsync;
    private volatile Revision headRevision;

    CommitIdDatabase(Repository repo) {
        // NB: We enable fsync only when our Git repository has been configured so,
        //     because there's no point of doing fsync only on this file when the
        //     Git repository does not.
        this(repo.getDirectory(), repo.getConfig().getBoolean(CONFIG_CORE_SECTION, "fsyncObjectFiles", false));
    }

    @VisibleForTesting
    CommitIdDatabase(File rootDir) {
        this(rootDir, false);
    }

    private CommitIdDatabase(File rootDir, boolean fsync) {
        path = new File(rootDir, "commit_ids.dat").toPath();
        try {
            channel = FileChannel.open(path,
                                       StandardOpenOption.CREATE,
                                       StandardOpenOption.READ,
                                       StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new StorageException("failed to open a commit ID database: " + path, e);
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

            if (size % RECORD_LEN != 0) {
                throw new StorageException("incorrect file length: " + path + " (" + size + " bytes)");
            }

            final int numRecords = (int) (size / RECORD_LEN);
            headRevision = numRecords > 0 ? new Revision(numRecords) : null;
            success = true;
        } finally {
            if (!success) {
                close();
            }
        }
    }

    @Nullable Revision headRevision() {
        return headRevision;
    }

    ObjectId get(Revision revision) {
        final Revision headRevision = this.headRevision;
        checkState(headRevision != null, "initial commit not available yet: %s", path);
        checkArgument(!revision.isRelative(), "revision: %s (expected: an absolute revision)", revision);
        if (revision.major() > headRevision.major()) {
            throw new RevisionNotFoundException(revision);
        }

        final ByteBuffer buf = threadLocalBuffer.get();
        buf.clear();
        long pos = (long) (revision.major() - 1) * RECORD_LEN;
        try {
            do {
                final int readBytes = channel.read(buf, pos);
                if (readBytes < 0) {
                    throw new EOFException();
                }
                pos += readBytes;
            } while (buf.hasRemaining());
        } catch (IOException e) {
            throw new StorageException("failed to read the commit ID database: " + path, e);
        }

        buf.flip();

        final int actualRevision = buf.getInt();
        if (actualRevision != revision.major()) {
            throw new StorageException("incorrect revision number in the commit ID database: " + path +
                                       "(actual: " + actualRevision + ", expected: " + revision.major() + ')');
        }

        return new ObjectId(buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt());
    }

    void put(Revision revision, ObjectId commitId) {
        put(revision, commitId, true);
    }

    private synchronized void put(Revision revision, ObjectId commitId, boolean safeMode) {
        if (safeMode) {
            final Revision expected;
            if (headRevision == null) {
                expected = Revision.INIT;
            } else {
                expected = headRevision.forward(1);
            }
            checkState(revision.equals(expected), "incorrect revision: %s (expected: %s)", revision, expected);
        }

        // Build a record.
        final ByteBuffer buf = threadLocalBuffer.get();
        buf.clear();
        buf.putInt(revision.major());
        commitId.copyRawTo(buf);
        buf.flip();

        // Append a record to the file.
        long pos = (long) (revision.major() - 1) * RECORD_LEN;
        try {
            do {
                pos += channel.write(buf, pos);
            } while (buf.hasRemaining());

            if (safeMode && fsync) {
                channel.force(true);
            }
        } catch (IOException e) {
            throw new StorageException("failed to update the commit ID database: " + path, e);
        }

        if (safeMode ||
            headRevision == null ||
            headRevision.major() < revision.major()) {
            headRevision = revision;
        }
    }

    void rebuild(Repository gitRepo) {
        logger.warn("Rebuilding the commit ID database ..");

        // Drop everything.
        try {
            channel.truncate(0);
        } catch (IOException e) {
            throw new StorageException("failed to drop the commit ID database: " + path, e);
        }
        headRevision = null;

        // Get the commit IDs of all revisions.
        try (RevWalk revWalk = new RevWalk(gitRepo)) {
            final Revision headRevision;
            final ObjectId headCommitId = gitRepo.resolve(R_HEADS_MASTER);
            if (headCommitId == null) {
                throw new StorageException("failed to determine the HEAD: " + gitRepo.getDirectory());
            }

            RevCommit revCommit = revWalk.parseCommit(headCommitId);
            headRevision = CommitUtil.extractRevision(revCommit.getFullMessage());

            // NB: We did not store the last commit ID until all commit IDs are stored,
            //     so that the partially built database always has mismatching head revision.

            ObjectId currentId;
            Revision previousRevision = headRevision;
            loop: for (;;) {
                switch (revCommit.getParentCount()) {
                    case 0:
                        // End of the history
                        break loop;
                    case 1:
                        currentId = revCommit.getParent(0);
                        break;
                    default:
                        throw new StorageException("found more than one parent: " +
                                                   gitRepo.getDirectory());
                }

                revCommit = revWalk.parseCommit(currentId);

                final Revision currentRevision = CommitUtil.extractRevision(revCommit.getFullMessage());
                final Revision expectedRevision = previousRevision.backward(1);
                if (!currentRevision.equals(expectedRevision)) {
                    throw new StorageException("mismatching revision: " + gitRepo.getDirectory() +
                                               " (actual: " + currentRevision.major() +
                                               ", expected: " + expectedRevision.major() + ')');
                }

                put(currentRevision, currentId, false);
                previousRevision = currentRevision;
            }

            // All commit IDs except the head have been stored. Store the head finally.
            put(headRevision, headCommitId);
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to rebuild the commit ID database", e);
        }

        logger.info("Rebuilt the commit ID database.");
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
