/*
 * Copyright 2019 LINE Corporation
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

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.StampedLock;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

/**
 * A {@link StampedLock} read lock stamp holder that provides reentrancy.
 */
final class ReadLockStamp {

    private static final AtomicIntegerFieldUpdater<ReadLockStamp> depthUpdater =
            AtomicIntegerFieldUpdater.newUpdater(ReadLockStamp.class, "depth");

    static ReadLockStamp lock(StampedLock lock, @Nullable ReadLockStamp readLockStamp) {
        if (readLockStamp == null) {
            final long readLockStampValue = lock.readLock();
            return new ReadLockStamp(lock, readLockStampValue);
        }

        checkArgument(readLockStamp.lock == lock, "mismatching lock");
        final int oldDepth = depthUpdater.getAndIncrement(readLockStamp);
        checkState(oldDepth > 0, "readLockStamp unlocked already");
        return readLockStamp;
    }

    private final StampedLock lock;
    @VisibleForTesting
    final long readLockStamp;
    @VisibleForTesting
    volatile int depth = 1;

    private ReadLockStamp(StampedLock lock, long readLockStamp) {
        // StampedLock.readLock() Javadoc does not mention that 0 has a special meaning,
        // but it seems like that 0 is used to represent a lock acquisition failure internally
        // accodring to OpenJDK source code.
        //
        // Here, we ensure that StampedLock.readLock() never returns 0 just in case this
        // assumption is broken although unlikely, because we set 0 to a stamp to avoid
        // unlocking the same stamp twice. See watch() for more context.
        assert readLockStamp != 0 : "readLockStamp";

        this.lock = lock;
        this.readLockStamp = readLockStamp;
    }

    void unlock() {
        final int newDepth = depthUpdater.decrementAndGet(this);
        checkState(newDepth >= 0, "unlocked too many times: %s", newDepth);

        if (newDepth == 0) {
            lock.unlockRead(readLockStamp);
        }
    }
}
