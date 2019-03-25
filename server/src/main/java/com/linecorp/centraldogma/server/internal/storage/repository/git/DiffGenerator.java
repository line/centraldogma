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
/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2008-2009, Johannes E. Schindelin <johannes.schindelin@gmx.de>
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import java.io.IOException;
import java.util.function.Predicate;

import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.IndexDiffFilter;
import org.eclipse.jgit.treewalk.filter.NotIgnoredFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import com.linecorp.centraldogma.server.internal.storage.StorageException;
import com.linecorp.centraldogma.server.internal.storage.repository.git.DiffEntry.ChangeType;

final class DiffGenerator {

    /**
     * Magical SHA1 used for file adds or deletes.
     */
    private static final AbbreviatedObjectId A_ZERO = AbbreviatedObjectId.fromObjectId(ObjectId.zeroId());

    /**
     * Magical file name used for file adds or deletes.
     */
    private static final String DEV_NULL = "/dev/null"; //$NON-NLS-1$

    static boolean scan(Repository repository, AnyObjectId a, AnyObjectId b,
                        TreeFilter filter, Predicate<DiffEntry> matcher) {
        try (ObjectReader reader = repository.newObjectReader();
             RevWalk rw = new RevWalk(reader)) {
            final RevTree aTree = a != null ? rw.parseTree(a) : null;
            final RevTree bTree = b != null ? rw.parseTree(b) : null;
            return scan(reader, aTree, bTree, filter, matcher);
        } catch (IOException e) {
            throw new StorageException("failed to compare two objects: " + a + " vs. " + b, e);
        }
    }

    static boolean scan(Repository repository, AbstractTreeIterator a, AbstractTreeIterator b,
                        TreeFilter filter, Predicate<DiffEntry> matcher) throws IOException {
        try (ObjectReader reader = repository.newObjectReader()) {
            return scan(reader, a, b, filter, matcher);
        }
    }

    private static boolean scan(ObjectReader reader, RevTree a, RevTree b,
                                TreeFilter filter, Predicate<DiffEntry> matcher) throws IOException {
        final AbstractTreeIterator aIterator = makeIteratorFromTreeOrNull(reader, a);
        final AbstractTreeIterator bIterator = makeIteratorFromTreeOrNull(reader, b);
        return scan(reader, aIterator, bIterator, filter, matcher);
    }

    private static boolean scan(ObjectReader reader, AbstractTreeIterator a, AbstractTreeIterator b,
                                TreeFilter filter, Predicate<DiffEntry> matcher) throws IOException {
        final TreeWalk walk = new TreeWalk(reader);
        walk.addTree(a);
        walk.addTree(b);
        walk.setRecursive(true);
        walk.setFilter(AndTreeFilter.create(filter, getDiffTreeFilterFor(a, b)));

        final MutableObjectId idBuf = new MutableObjectId();
        while (walk.next()) {
            final DiffEntry entry = new DiffEntry();

            walk.getObjectId(idBuf, 0);
            entry.oldId = AbbreviatedObjectId.fromObjectId(idBuf);

            walk.getObjectId(idBuf, 1);
            entry.newId = AbbreviatedObjectId.fromObjectId(idBuf);

            entry.oldMode = walk.getFileMode(0);
            entry.newMode = walk.getFileMode(1);
            entry.newPath = entry.oldPath = walk.getPathString();

            if (walk.getAttributesNodeProvider() != null) {
                entry.diffAttribute = walk.getAttributes()
                                          .get(Constants.ATTR_DIFF);
            }

            if (entry.oldMode == FileMode.MISSING) {
                entry.oldPath = DEV_NULL;
                entry.changeType = ChangeType.ADD;
                if (matcher.test(entry)) {
                    return true;
                }
            } else if (entry.newMode == FileMode.MISSING) {
                entry.newPath = DEV_NULL;
                entry.changeType = ChangeType.DELETE;
                if (matcher.test(entry)) {
                    return true;
                }
            } else if (!entry.oldId.equals(entry.newId)) {
                entry.changeType = ChangeType.MODIFY;
                if (sameType(entry.oldMode, entry.newMode)) {
                    if (matcher.test(entry)) {
                        return true;
                    }
                } else {
                    final DiffEntry[] brokenEntries = breakModify(entry);
                    if (matcher.test(brokenEntries[0])) {
                        return true;
                    }
                    if (matcher.test(brokenEntries[1])) {
                        return true;
                    }
                }
            } else if (entry.oldMode != entry.newMode) {
                entry.changeType = ChangeType.MODIFY;
                if (matcher.test(entry)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static AbstractTreeIterator makeIteratorFromTreeOrNull(ObjectReader reader, RevTree tree)
            throws IOException {
        if (tree == null) {
            return new EmptyTreeIterator();
        }

        final CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, tree);
        return parser;
    }

    private static TreeFilter getDiffTreeFilterFor(AbstractTreeIterator a, AbstractTreeIterator b) {
        if (a instanceof DirCacheIterator && b instanceof WorkingTreeIterator) {
            return new IndexDiffFilter(0, 1);
        }

        if (a instanceof WorkingTreeIterator && b instanceof DirCacheIterator) {
            return new IndexDiffFilter(1, 0);
        }

        TreeFilter filter = TreeFilter.ANY_DIFF;
        if (a instanceof WorkingTreeIterator) {
            filter = AndTreeFilter.create(new NotIgnoredFilter(0), filter);
        }
        if (b instanceof WorkingTreeIterator) {
            filter = AndTreeFilter.create(new NotIgnoredFilter(1), filter);
        }
        return filter;
    }

    static boolean sameType(FileMode a, FileMode b) {
        // Files have to be of the same type in order to rename them.
        // We would never want to rename a file to a gitlink, or a
        // symlink to a file.
        //
        final int aType = a.getBits() & FileMode.TYPE_MASK;
        final int bType = b.getBits() & FileMode.TYPE_MASK;
        return aType == bType;
    }

    /**
     * Breaks apart a DiffEntry into two entries, one DELETE and one ADD.
     *
     * @param entry
     *            the DiffEntry to break apart.
     * @return a list containing two entries. Calling {@link DiffEntry#getChangeType()}
     *         on the first entry will return ChangeType.DELETE. Calling it on
     *         the second entry will return ChangeType.ADD.
     */
    static DiffEntry[] breakModify(DiffEntry entry) {
        final DiffEntry del = new DiffEntry();
        del.oldId = entry.getOldId();
        del.oldMode = entry.getOldMode();
        del.oldPath = entry.getOldPath();

        del.newId = A_ZERO;
        del.newMode = FileMode.MISSING;
        del.newPath = DEV_NULL;
        del.changeType = ChangeType.DELETE;
        del.diffAttribute = entry.diffAttribute;

        final DiffEntry add = new DiffEntry();
        add.oldId = A_ZERO;
        add.oldMode = FileMode.MISSING;
        add.oldPath = DEV_NULL;

        add.newId = entry.getNewId();
        add.newMode = entry.getNewMode();
        add.newPath = entry.getNewPath();
        add.changeType = ChangeType.ADD;
        add.diffAttribute = entry.diffAttribute;
        return new DiffEntry[] { del, add };
    }

    private DiffGenerator() {}
}
