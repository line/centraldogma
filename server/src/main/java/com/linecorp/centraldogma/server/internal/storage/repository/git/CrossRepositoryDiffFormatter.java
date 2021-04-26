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
/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2008-2020, Johannes E. Schindelin <johannes.schindelin@gmx.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.lib.ObjectId;

import com.google.common.collect.ImmutableList;

final class CrossRepositoryDiffFormatter {

    // Forked minimum features from jGit v5.11.0.202103091610-r
    // https://github.com/eclipse/jgit/blob/v5.11.0.202103091610-r/org.eclipse.jgit/src/org/eclipse/jgit/diff/DiffFormatter.java

    private static final AbbreviatedObjectId A_ZERO = AbbreviatedObjectId.fromObjectId(ObjectId.zeroId());

    static List<DiffEntry> scan(CrossRepositoryTreeWalk walk) throws IOException {
        final ImmutableList.Builder<DiffEntry> builder = ImmutableList.builder();
        final MutableObjectId idBuf = new MutableObjectId();
        while (walk.next()) {
            final SettableDiffEntry entry = new SettableDiffEntry();

            walk.objectId(idBuf, 0);
            entry.oldId(AbbreviatedObjectId.fromObjectId(idBuf));

            walk.objectId(idBuf, 1);
            entry.newId(AbbreviatedObjectId.fromObjectId(idBuf));

            entry.oldMode(walk.getFileMode(0));
            entry.newMode(walk.getFileMode(1));
            entry.oldPath(walk.pathString());
            entry.newPath(walk.pathString());

            if (entry.oldMode() == FileMode.MISSING) {
                entry.oldPath(DiffEntry.DEV_NULL);
                entry.changeType(ChangeType.ADD);
                builder.add(entry);
            } else if (entry.newMode() == FileMode.MISSING) {
                entry.newPath(DiffEntry.DEV_NULL);
                entry.changeType(ChangeType.DELETE);
                builder.add(entry);
            } else if (!entry.oldId().equals(entry.newId())) {
                entry.changeType(ChangeType.MODIFY);
                if (sameType(entry.oldMode(), entry.newMode())) {
                    builder.add(entry);
                } else {
                    builder.addAll(breakModify(entry));
                }
            } else if (entry.oldMode() != entry.newMode()) {
                entry.changeType(ChangeType.MODIFY);
                builder.add(entry);
            }
        }
        return builder.build();
    }

    private static boolean sameType(FileMode a, FileMode b) {
        // Files have to be of the same type in order to rename them.
        // We would never want to rename a file to a gitlink, or a
        // symlink to a file.
        final int aType = a.getBits() & FileMode.TYPE_MASK;
        final int bType = b.getBits() & FileMode.TYPE_MASK;
        return aType == bType;
    }

    private static List<DiffEntry> breakModify(DiffEntry entry) {
        final SettableDiffEntry del = new SettableDiffEntry();
        del.oldId(entry.getOldId());
        del.oldMode(entry.getOldMode());
        del.oldPath(entry.getOldPath());

        del.newId(A_ZERO);
        del.newMode(FileMode.MISSING);
        del.newPath(DiffEntry.DEV_NULL);
        del.changeType(ChangeType.DELETE);

        final SettableDiffEntry add = new SettableDiffEntry();
        add.oldId(A_ZERO);
        add.oldMode(FileMode.MISSING);
        add.oldPath(DiffEntry.DEV_NULL);

        add.newId(entry.getNewId());
        add.newMode(entry.getNewMode());
        add.newPath(entry.getNewPath());
        add.changeType(ChangeType.ADD);
        return Arrays.asList(del, add);
    }

    private static class SettableDiffEntry extends DiffEntry {

        void oldMode(FileMode oldMode) {
            this.oldMode = oldMode;
        }

        FileMode oldMode() {
            return oldMode;
        }

        void newMode(FileMode newMode) {
            this.newMode = newMode;
        }

        FileMode newMode() {
            return newMode;
        }

        void oldPath(String oldPath) {
            this.oldPath = oldPath;
        }

        void newPath(String newPath) {
            this.newPath = newPath;
        }

        void changeType(ChangeType changeType) {
            this.changeType = changeType;
        }

        AbbreviatedObjectId oldId() {
            return oldId;
        }

        void oldId(AbbreviatedObjectId oldId) {
            this.oldId = oldId;
        }

        AbbreviatedObjectId newId() {
            return newId;
        }

        void newId(AbbreviatedObjectId newId) {
            this.newId = newId;
        }
    }

    private CrossRepositoryDiffFormatter() {}
}
