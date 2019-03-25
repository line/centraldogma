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
 * Copyright (C) 2008-2013, Google Inc.
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

import org.eclipse.jgit.attributes.Attribute;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;

/**
 * A value class representing a change to a file.
 */
final class DiffEntry {
    /**
     * General type of change a single file-level patch describes.
     */
    enum ChangeType {
        /**
         * Add a new file to the project.
         */
        ADD,
        /**
         * Modify an existing file in the project (content and/or mode).
         */
        MODIFY,
        /**
         * Delete an existing file from the project.
         */
        DELETE,
        /**
         * Rename an existing file to a new location.
         */
        RENAME,
        /**
         * Copy an existing file to a new location, keeping the original.
         */
        COPY
    }

    /**
     * Specify the old or new side for more generalized access.
     */
    enum Side {
        /**
         * The old side of a DiffEntry.
         */
        OLD,
        /**
         * The new side of a DiffEntry.
         */
        NEW
    }

    /**
     * File name of the old (pre-image).
     */
    private final String oldPath;

    /**
     * File name of the new (post-image).
     */
    private final String newPath;

    /**
     * diff filter attribute.
     */
    private final Attribute diffAttribute;

    /**
     * Old mode of the file, if described by the patch, else null.
     */
    private final FileMode oldMode;

    /**
     * New mode of the file, if described by the patch, else null.
     */
    private final FileMode newMode;

    /**
     * General type of change indicated by the patch.
     */
    private final ChangeType changeType;

    /**
     * ObjectId listed on the index line for the old (pre-image).
     */
    private final ObjectId oldId;

    /**
     * ObjectId listed on the index line for the new (post-image).
     */
    private final ObjectId newId;

    DiffEntry(String oldPath, String newPath, Attribute diffAttribute, FileMode oldMode, FileMode newMode,
              ChangeType changeType, ObjectId oldId, ObjectId newId) {
        this.oldPath = oldPath;
        this.newPath = newPath;
        this.diffAttribute = diffAttribute;
        this.oldMode = oldMode;
        this.newMode = newMode;
        this.changeType = changeType;
        this.oldId = oldId;
        this.newId = newId;
    }

    /**
     * Get the old name associated with this file.
     *
     * <p>The meaning of the old name can differ depending on the semantic meaning
     * of this patch:
     * <ul>
     * <li><i>file add</i>: always {@code /dev/null}</li>
     * <li><i>file modify</i>: always {@link #getNewPath()}</li>
     * <li><i>file delete</i>: always the file being deleted</li>
     * <li><i>file copy</i>: source file the copy originates from</li>
     * <li><i>file rename</i>: source file the rename originates from</li>
     * </ul>
     *
     * @return old name for this file.
     */
    String getOldPath() {
        return oldPath;
    }

    /**
     * Get the new name associated with this file.
     *
     * <p>The meaning of the new name can differ depending on the semantic meaning
     * of this patch:
     * <ul>
     * <li><i>file add</i>: always the file being created</li>
     * <li><i>file modify</i>: always {@link #getOldPath()}</li>
     * <li><i>file delete</i>: always {@code /dev/null}</li>
     * <li><i>file copy</i>: destination file the copy ends up at</li>
     * <li><i>file rename</i>: destination file the rename ends up at</li>
     * </ul>
     *
     * @return new name for this file.
     */
    String getNewPath() {
        return newPath;
    }

    /**
     * Returns the {@link Attribute} determining filters to be applied.
     */
    public Attribute getDiffAttribute() {
        return diffAttribute;
    }

    /**
     * Get the old file mode.
     *
     * @return the old file mode, if described in the patch
     */
    FileMode getOldMode() {
        return oldMode;
    }

    /**
     * Get the new file mode.
     *
     * @return the new file mode, if described in the patch
     */
    FileMode getNewMode() {
        return newMode;
    }

    /**
     * Get the change type.
     *
     * @return the type of change this patch makes on {@link #getNewPath()}
     */
    ChangeType getChangeType() {
        return changeType;
    }

    /**
     * Get the old object id from the {@code index}.
     *
     * @return the object id; null if there is no index line
     */
    ObjectId getOldId() {
        return oldId;
    }

    /**
     * Get the new object id from the {@code index}.
     *
     * @return the object id; null if there is no index line
     */
    ObjectId getNewId() {
        return newId;
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        buf.append("DiffEntry[");
        buf.append(changeType);
        buf.append(' ');
        switch (changeType) {
            case ADD:
                buf.append(newPath);
                break;
            case COPY:
            case RENAME:
                buf.append(oldPath + "->" + newPath);
                break;
            case DELETE:
            case MODIFY:
                buf.append(oldPath);
                break;
        }
        buf.append(']');
        return buf.toString();
    }
}
