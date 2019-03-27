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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.locks.StampedLock;

import org.junit.Test;

public class ReadLockStampTest {

    @Test
    public void withoutReentrance() {
        final StampedLock lock = new StampedLock();
        final ReadLockStamp stamp = ReadLockStamp.lock(lock, null);

        // Ensure that read lock has been acquired.
        assertThat(lock.tryWriteLock()).isZero();

        // Check the internal properties.
        assertThat(stamp.readLockStamp).isNotZero();
        assertThat(stamp.depth).isOne();

        // Unlock must decrease the depth to zero.
        stamp.unlock();
        assertThat(stamp.depth).isZero();

        // Ensure the read lock has been released.
        final long writeStamp = lock.tryWriteLock();
        assertThat(writeStamp).isNotZero();
        lock.unlockWrite(writeStamp);
    }

    @Test
    public void withReentrance() {
        final StampedLock lock = new StampedLock();
        final ReadLockStamp stamp1 = ReadLockStamp.lock(lock, null);
        final ReadLockStamp stamp2 = ReadLockStamp.lock(lock, stamp1);

        // Must return the same stamp instance with increased depth in case of reentrance.
        assertThat(stamp2).isSameAs(stamp1);
        assertThat(stamp1.depth).isEqualTo(2);

        // unlock() must decrease the depth to 1 and the read lock must not be released.
        stamp2.unlock();
        assertThat(stamp2.depth).isOne();
        assertThat(lock.tryWriteLock()).isZero();

        // Unlocking again must decrease the depth to 0.
        stamp2.unlock();
        assertThat(stamp2.depth).isZero();

        // Ensure the read lock has been released.
        final long writeStamp = lock.tryWriteLock();
        assertThat(writeStamp).isNotZero();
        lock.unlockWrite(writeStamp);
    }

    @Test
    public void unlockTooManyTimes() {
        final StampedLock lock = new StampedLock();
        final ReadLockStamp stamp = ReadLockStamp.lock(lock, null);
        stamp.unlock();
        assertThatThrownBy(stamp::unlock).isInstanceOf(IllegalStateException.class)
                                         .hasMessageContaining("unlocked too many times");
    }

    @Test
    public void lockTheUnlocked() {
        final StampedLock lock = new StampedLock();
        final ReadLockStamp stamp = ReadLockStamp.lock(lock, null);
        stamp.unlock();
        assertThatThrownBy(() -> ReadLockStamp.lock(lock, stamp)).isInstanceOf(IllegalStateException.class)
                                                                 .hasMessageContaining("unlocked already");
    }

    @Test
    public void mismatchingLock() {
        final StampedLock lock1 = new StampedLock();
        final StampedLock lock2 = new StampedLock();
        final ReadLockStamp stamp = ReadLockStamp.lock(lock1, null);
        assertThatThrownBy(() -> ReadLockStamp.lock(lock2, stamp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mismatching");
    }
}
