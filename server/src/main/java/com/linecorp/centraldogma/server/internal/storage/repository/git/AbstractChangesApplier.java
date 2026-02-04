/*
 * Copyright 2024 LINE Corporation
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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;

import javax.annotation.Nullable;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.storage.StorageException;

abstract class AbstractChangesApplier {

    private static final byte[] EMPTY_BYTE = new byte[0];

    int apply(Repository jGitRepository, Revision headRevision,
              @Nullable ObjectId baseTreeId, DirCache dirCache) {
        try (ObjectInserter inserter = jGitRepository.newObjectInserter();
             ObjectReader reader = jGitRepository.newObjectReader()) {

            if (baseTreeId != null) {
                // the DirCacheBuilder is to used for doing update operations on the given DirCache object
                final DirCacheBuilder builder = dirCache.builder();

                // Add the tree object indicated by the prevRevision to the temporary DirCache object.
                builder.addTree(EMPTY_BYTE, 0, reader, baseTreeId);
                builder.finish();
            }

            return doApply(headRevision, dirCache, reader, inserter);
        } catch (CentralDogmaException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to apply changes on revision: " + headRevision.major(), e);
        }
    }

    abstract int doApply(Revision headRevision, DirCache dirCache,
                         ObjectReader reader, ObjectInserter inserter) throws IOException;

    static void applyPathEdit(DirCache dirCache, PathEdit edit) {
        final DirCacheEditor e = dirCache.editor();
        e.add(edit);
        e.finish();
    }

    // PathEdit implementations which is used when applying changes.

    static final class InsertJson extends PathEdit {
        private final ObjectInserter inserter;
        private final JsonNode jsonNode;

        InsertJson(String entryPath, ObjectInserter inserter, JsonNode jsonNode) {
            super(entryPath);
            this.inserter = inserter;
            this.jsonNode = jsonNode;
        }

        @Override
        public void apply(DirCacheEntry ent) {
            try {
                final byte[] jsonBytes = Jackson.writeValueAsPrettyString(jsonNode).getBytes(UTF_8);
                ent.setObjectId(inserter.insert(Constants.OBJ_BLOB, jsonBytes));
                ent.setFileMode(FileMode.REGULAR_FILE);
            } catch (IOException e) {
                throw new StorageException("failed to create a new JSON blob", e);
            }
        }
    }

    static final class InsertText extends PathEdit {
        private final ObjectInserter inserter;
        private final String text;

        InsertText(String entryPath, ObjectInserter inserter, String text) {
            super(entryPath);
            this.inserter = inserter;
            this.text = text;
        }

        @Override
        public void apply(DirCacheEntry ent) {
            try {
                ent.setObjectId(inserter.insert(Constants.OBJ_BLOB, text.getBytes(UTF_8)));
                ent.setFileMode(FileMode.REGULAR_FILE);
            } catch (IOException e) {
                throw new StorageException("failed to create a new text blob", e);
            }
        }
    }

    static final class CopyOldEntry extends PathEdit {
        private final DirCacheEntry oldEntry;

        CopyOldEntry(String entryPath, DirCacheEntry oldEntry) {
            super(entryPath);
            this.oldEntry = oldEntry;
        }

        @Override
        public void apply(DirCacheEntry ent) {
            ent.setFileMode(oldEntry.getFileMode());
            ent.setObjectId(oldEntry.getObjectId());
        }
    }
}
