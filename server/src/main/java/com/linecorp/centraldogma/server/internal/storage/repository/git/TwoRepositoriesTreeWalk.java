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
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.OBJECT_ID_LENGTH;
import static org.eclipse.jgit.lib.Constants.OBJ_TREE;
import static org.eclipse.jgit.lib.Constants.TYPE_TREE;

import java.io.IOException;
import java.util.Arrays;

import javax.annotation.Nullable;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.RawParseUtils;

final class TwoRepositoriesTreeWalk implements AutoCloseable {

    // Forked minimum features from jGit v5.11.0.202103091610-r
    // https://github.com/eclipse/jgit/blob/v5.11.0.202103091610-r/org.eclipse.jgit/src/org/eclipse/jgit/treewalk/TreeWalk.java

    private final MutableObjectId idBuffer = new MutableObjectId();
    private final CanonicalTreeParser[] trees;

    private final TreeFilter filter;

    private int depth;

    private boolean advance;

    private CanonicalTreeParser currentHead;

    private final ObjectReader oldReader;
    private final ObjectReader newReader;

    TwoRepositoriesTreeWalk(Repository oldRepo, ObjectId oldObjectId, Repository newRepo, ObjectId newObjectId,
                            TreeFilter filter) throws IOException {
        this.filter = filter;
        oldReader = oldRepo.newObjectReader();
        newReader = newRepo.newObjectReader();

        final RevTree oldRevTree = newRevWalk(oldReader).parseTree(oldObjectId);
        final RevTree newRevTree = newRevWalk(newReader).parseTree(newObjectId);

        final CanonicalTreeParser fromTreeParser = canonicalTreeParser(oldReader, oldRevTree);
        final CanonicalTreeParser toTreeParser = canonicalTreeParser(newReader, newRevTree);
        trees = new CanonicalTreeParser[] { fromTreeParser, toTreeParser };
    }

    private static RevWalk newRevWalk(ObjectReader reader) {
        final RevWalk revWalk = new RevWalk(reader);
        revWalk.setRewriteParents(false);
        return revWalk;
    }

    private CanonicalTreeParser canonicalTreeParser(ObjectReader reader, RevTree revTree) throws IOException {
        final CanonicalTreeParser parser = new CanonicalTreeParser(reader, revTree);
        parser.reset();
        return parser;
    }

    public boolean next() throws IOException {
        try {
            if (advance) {
                advance = false;
                popEntriesEqual();
            }

            for (;;) {
                final CanonicalTreeParser t = min();
                if (t.eof()) {
                    if (depth > 0) {
                        exitSubtree();
                        popEntriesEqual();
                        continue;
                    }
                    return false;
                }

                currentHead = t;
                if (!matchFilter()) {
                    popEntriesEqual();
                    continue;
                }

                if (FileMode.TREE.equals(t.getEntryRawMode())) {
                    enterSubtree();
                    continue;
                }

                advance = true;
                return true;
            }
        } catch (StopWalkException stop) {
            return false;
        }
    }

    private boolean matchFilter() {
        if (filter == TreeFilter.ALL) {
            return true;
        }
        assert filter instanceof PathPatternFilter;
        if (FileMode.TREE.equals(currentHead.getEntryRawMode())) {
            // subtree.
            return true;
        }

        final PathPatternFilter pathPatternFilter = (PathPatternFilter) filter;
        return pathPatternFilter.matches(pathString());
    }

    String pathString() {
        return pathOf(currentHead);
    }

    private static String pathOf(CanonicalTreeParser t) {
        return RawParseUtils.decode(UTF_8, t.path(), 0, t.pathLen());
    }

    private CanonicalTreeParser min() {
        int i = 0;
        CanonicalTreeParser minRef = trees[i];
        while (minRef.eof() && ++i < trees.length) {
            minRef = trees[i];
        }
        if (minRef.eof()) {
            return minRef;
        }

        minRef.matches = minRef;
        while (++i < trees.length) {
            final CanonicalTreeParser t = trees[i];
            if (t.eof()) {
                continue;
            }
            final int cmp = t.pathCompare(minRef);
            if (cmp < 0) {
                t.matches = t;
                minRef = t;
            } else if (cmp == 0) {
                t.matches = minRef;
            }
        }

        return minRef;
    }

    private void popEntriesEqual() {
        final CanonicalTreeParser ch = currentHead;
        for (CanonicalTreeParser t : trees) {
            if (t.matches == ch) {
                t.next(1);
                t.matches = null;
            }
        }
    }

    private void exitSubtree() {
        depth--;
        for (int i = 0; i < trees.length; i++) {
            trees[i] = trees[i].parent();
        }

        CanonicalTreeParser minRef = null;
        for (CanonicalTreeParser t : trees) {
            if (t.matches != t) {
                continue;
            }
            if (minRef == null || t.pathCompare(minRef) < 0) {
                minRef = t;
            }
        }
        currentHead = minRef;
    }

    private void enterSubtree() throws IOException {
        final CanonicalTreeParser ch = currentHead;
        final CanonicalTreeParser[] tmp = new CanonicalTreeParser[trees.length];
        for (int i = 0; i < trees.length; i++) {
            final CanonicalTreeParser t = trees[i];
            final CanonicalTreeParser n;
            // If we find a GITLINK when attempting to enter a subtree, then the
            // GITLINK must exist as a TREE in the index, meaning the working tree
            // entry should be treated as a TREE
            if (t.matches == ch && !t.eof() &&
                (FileMode.TREE.equals(t.getEntryRawMode()) ||
                 (FileMode.GITLINK.equals(t.getEntryRawMode()) && t.isWorkTree()))) {
                n = t.createSubtreeIterator(idBuffer);
            } else {
                n = t.createEmptyCanonicalTreeParser();
            }
            tmp[i] = n;
        }
        depth++;
        advance = false;
        System.arraycopy(tmp, 0, trees, 0, trees.length);
    }

    void objectId(MutableObjectId out, int nth) {
        final CanonicalTreeParser t = trees[nth];
        if (t.matches == currentHead) {
            t.getEntryObjectId(out);
        } else {
            out.clear();
        }
    }

    FileMode getFileMode(int nth) {
        return FileMode.fromBits(getRawMode(nth));
    }

    private int getRawMode(int nth) {
        final CanonicalTreeParser t = trees[nth];
        return t.matches == currentHead ? t.getEntryRawMode() : 0;
    }

    @Override
    public void close() {
        try {
            oldReader.close();
        } finally {
            newReader.close();
        }
    }

    private static class CanonicalTreeParser extends AbstractTreeIterator {

        // Forked minimum features from jGit v5.11.0.202103091610-r
        // https://github.com/eclipse/jgit/blob/v5.11.0.202103091610-r/org.eclipse.jgit/src/org/eclipse/jgit/treewalk/CanonicalTreeParser.java

        private static final byte[] EMPTY = {};

        private byte[] raw;

        /**
         * First offset within {@link #raw} of the prior entry.
         */
        private int prevPtr;

        /**
         * First offset within {@link #raw} of the current entry's data.
         */
        private int currPtr;

        /**
         * Offset one past the current entry (first byte of next entry).
         */
        private int nextPtr;

        private final ObjectReader reader;
        @Nullable
        CanonicalTreeParser matches;

        CanonicalTreeParser(CanonicalTreeParser parent, ObjectReader reader) {
            super(parent);
            this.reader = reader;
            reset(EMPTY);
        }

        CanonicalTreeParser(ObjectReader reader, RevTree revTree) throws IOException {
            this.reader = reader;
            reset(reader, revTree);
        }

        private void reset(ObjectReader reader, AnyObjectId id) throws IOException {
            reset(reader.open(id, OBJ_TREE).getCachedBytes());
        }

        private void reset(byte[] treeData) {
            attributesNode = null;
            raw = treeData;
            prevPtr = -1;
            currPtr = 0;
            if (eof()) {
                nextPtr = 0;
            } else {
                parseEntry();
            }
        }

        @Override
        public void reset() {
            if (!first()) {
                reset(raw);
            }
        }

        CanonicalTreeParser createSubtreeIterator(MutableObjectId idBuffer) throws IOException {
            return createSubtreeIterator(reader, idBuffer);
        }

        @Override
        public CanonicalTreeParser createSubtreeIterator(
                ObjectReader reader, MutableObjectId idBuffer) throws IOException {
            idBuffer.fromRaw(idBuffer(), idOffset());
            if (!FileMode.TREE.equals(mode)) {
                final ObjectId me = idBuffer.toObjectId();
                throw new IncorrectObjectTypeException(me, TYPE_TREE);
            }
            final CanonicalTreeParser p = new CanonicalTreeParser(this, reader);
            p.reset(reader, idBuffer);
            return p;
        }

        @Override
        public CanonicalTreeParser createSubtreeIterator(ObjectReader reader) throws IOException {
            return createSubtreeIterator(reader, new MutableObjectId());
        }

        @Override
        public boolean hasId() {
            return true;
        }

        @Override
        public byte[] idBuffer() {
            return raw;
        }

        @Override
        public int idOffset() {
            return nextPtr - OBJECT_ID_LENGTH;
        }

        @Override
        public boolean first() {
            return currPtr == 0;
        }

        @Override
        public boolean eof() {
            return currPtr == raw.length;
        }

        @Override
        public void next(int delta) {
            if (delta == 1) {
                // Moving forward one is the most common case.
                prevPtr = currPtr;
                currPtr = nextPtr;
                if (!eof()) {
                    parseEntry();
                }
                return;
            }

            // Fast skip over records, then parse the last one.
            final int end = raw.length;
            int ptr = nextPtr;
            while (--delta > 0 && ptr != end) {
                prevPtr = ptr;
                while (raw[ptr] != 0) {
                    ptr++;
                }
                ptr += OBJECT_ID_LENGTH + 1;
            }
            if (delta != 0) {
                throw new ArrayIndexOutOfBoundsException(delta);
            }
            currPtr = ptr;
            if (!eof()) {
                parseEntry();
            }
        }

        @Override
        public void back(int delta) {
            if (delta == 1 && 0 <= prevPtr) {
                // Moving back one is common in NameTreeWalk, as the average tree
                // won't have D/F type conflicts to study.
                currPtr = prevPtr;
                prevPtr = -1;
                if (!eof()) {
                    parseEntry();
                }
                return;
            } else if (delta <= 0) {
                throw new ArrayIndexOutOfBoundsException(delta);
            }

            // Fast skip through the records, from the beginning of the tree.
            // There is no reliable way to read the tree backwards, so we must
            // parse all over again from the beginning. We hold the last "delta"
            // positions in a buffer, so we can find the correct position later.
            final int[] trace = new int[delta + 1];
            Arrays.fill(trace, -1);
            int ptr = 0;
            while (ptr != currPtr) {
                System.arraycopy(trace, 1, trace, 0, delta);
                trace[delta] = ptr;
                while (raw[ptr] != 0) {
                    ptr++;
                }
                ptr += OBJECT_ID_LENGTH + 1;
            }
            if (trace[1] == -1) {
                throw new ArrayIndexOutOfBoundsException(delta);
            }
            prevPtr = trace[0];
            currPtr = trace[1];
            parseEntry();
        }

        private void parseEntry() {
            int ptr = currPtr;
            byte c = raw[ptr++];
            int tmp = c - '0';
            for (;;) {
                c = raw[ptr++];
                if (' ' == c) {
                    break;
                }
                tmp <<= 3;
                tmp += c - '0';
            }
            mode = tmp;

            tmp = pathOffset;
            for (;; tmp++) {
                c = raw[ptr++];
                if (c == 0) {
                    break;
                }
                if (tmp >= path.length) {
                    growPath(tmp);
                }
                path[tmp] = c;
            }
            pathLen = tmp;
            nextPtr = ptr + OBJECT_ID_LENGTH;
        }

        CanonicalTreeParser parent() {
            return (CanonicalTreeParser) parent;
        }

        byte[] path() {
            return path;
        }

        int pathLen() {
            return pathLen;
        }

        CanonicalTreeParser createEmptyCanonicalTreeParser() {
            return new EmptyCanonicalTreeParser(this, reader);
        }
    }

    private static final class EmptyCanonicalTreeParser extends CanonicalTreeParser {

        EmptyCanonicalTreeParser(CanonicalTreeParser parent, ObjectReader reader) {
            super(parent, reader);
            pathLen = pathOffset;
        }

        @Override
        public EmptyCanonicalTreeParser createSubtreeIterator(ObjectReader reader) throws IOException {
            return new EmptyCanonicalTreeParser(this, reader);
        }

        @Override
        public boolean hasId() {
            return false;
        }

        @Override
        public ObjectId getEntryObjectId() {
            return ObjectId.zeroId();
        }

        @Override
        public byte[] idBuffer() {
            return zeroid;
        }

        @Override
        public int idOffset() {
            return 0;
        }

        @Override
        public void reset() {}

        @Override
        public boolean first() {
            return true;
        }

        @Override
        public boolean eof() {
            return true;
        }

        @Override
        public void next(int delta) {}

        @Override
        public void back(int delta) {}

        @Override
        public void stopWalk() {}

        @Override
        protected boolean needsStopWalk() {
            return false;
        }
    }
}
