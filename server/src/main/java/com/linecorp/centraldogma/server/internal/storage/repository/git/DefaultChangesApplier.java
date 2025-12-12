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
 * under the License
 */
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.centraldogma.internal.Yaml.isYaml;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.sanitizeText;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

import javax.annotation.Nullable;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEditor.DeletePath;
import org.eclipse.jgit.dircache.DirCacheEditor.DeleteTree;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.TextPatchConflictException;
import com.linecorp.centraldogma.common.jsonpatch.JsonPatchConflictException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.Yaml;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;

import difflib.DiffUtils;
import difflib.Patch;

final class DefaultChangesApplier extends AbstractChangesApplier {

    private final Iterable<Change<?>> changes;

    DefaultChangesApplier(Iterable<Change<?>> changes) {
        this.changes = changes;
    }

    @Override
    int doApply(Revision unused, DirCache dirCache,
                ObjectReader reader, ObjectInserter inserter) throws IOException {
        int numEdits = 0;
        // loop over the specified changes.
        for (Change<?> change : changes) {
            final String changePath = change.path().substring(1); // Strip the leading '/'.
            final DirCacheEntry oldEntry = dirCache.getEntry(changePath);
            final byte[] oldContent = oldEntry != null ? reader.open(oldEntry.getObjectId()).getBytes()
                                                       : null;

            switch (change.type()) {
                case UPSERT_JSON: {
                    final String rawContent = change.rawContent();
                    final JsonNode newJsonNode = firstNonNull((JsonNode) change.content(),
                                                              JsonNodeFactory.instance.nullNode());
                    final boolean hasChanges;
                    if (rawContent != null) {
                        // If rawContent is provided, compare the raw JSON text.
                        final String oldRawContent = oldContent != null ? new String(oldContent, UTF_8) : null;
                        hasChanges = !rawContent.equals(oldRawContent);
                    } else {
                        // Otherwise, compare the parsed JSON nodes.
                        final JsonNode oldJsonNode = toJsonNode(changePath, oldContent);
                        hasChanges = !Objects.equals(newJsonNode, oldJsonNode);
                    }

                    if (hasChanges) {
                        String newJson = rawContent;
                        if (newJson == null) {
                            newJson = Jackson.writeValueAsString(newJsonNode);
                        }
                        applyPathEdit(dirCache, new InsertText(changePath, inserter, newJson));
                        numEdits++;
                    }
                    break;
                }
                case UPSERT_YAML:
                    final String newYaml = change.rawContent();
                    // rawContent must not be null for YAML upsert.
                    assert newYaml != null;

                    final String oldYaml;
                    if (oldContent != null) {
                        oldYaml = new String(oldContent, UTF_8);
                    } else {
                        oldYaml = null;
                    }

                    if (!newYaml.equals(oldYaml)) {
                        applyPathEdit(dirCache, new InsertText(changePath, inserter, newYaml));
                        numEdits++;
                    }
                    break;
                case UPSERT_TEXT: {
                    final String sanitizedOldText;
                    if (oldContent != null) {
                        sanitizedOldText = sanitizeText(new String(oldContent, UTF_8));
                    } else {
                        sanitizedOldText = null;
                    }

                    final String sanitizedNewText = sanitizeText(change.contentAsText());

                    // Upsert only when the contents are really different.
                    if (!sanitizedNewText.equals(sanitizedOldText)) {
                        applyPathEdit(dirCache, new InsertText(changePath, inserter, sanitizedNewText));
                        numEdits++;
                    }
                    break;
                }
                case REMOVE:
                    if (oldEntry != null) {
                        applyPathEdit(dirCache, new DeletePath(changePath));
                        numEdits++;
                        break;
                    }

                    // The path might be a directory.
                    if (applyDirectoryEdits(dirCache, changePath, null, change)) {
                        numEdits++;
                    } else {
                        // Was not a directory either; conflict.
                        reportNonExistentEntry(change);
                        break;
                    }
                    break;
                case RENAME: {
                    final String newPath =
                            ((String) change.content()).substring(1); // Strip the leading '/'.

                    if (dirCache.getEntry(newPath) != null) {
                        throw new ChangeConflictException("a file exists at the target path: " + change);
                    }

                    if (oldEntry != null) {
                        if (changePath.equals(newPath)) {
                            // Redundant rename request - old path and new path are same.
                            break;
                        }

                        final DirCacheEditor editor = dirCache.editor();
                        editor.add(new DeletePath(changePath));
                        editor.add(new CopyOldEntry(newPath, oldEntry));
                        editor.finish();
                        numEdits++;
                        break;
                    }

                    // The path might be a directory.
                    if (applyDirectoryEdits(dirCache, changePath, newPath, change)) {
                        numEdits++;
                    } else {
                        // Was not a directory either; conflict.
                        reportNonExistentEntry(change);
                    }
                    break;
                }
                case APPLY_JSON_PATCH: {
                    final JsonNode oldJsonNode = toJsonNode(changePath, oldContent);
                    final JsonNode newJsonNode;
                    try {
                        newJsonNode = JsonPatch.fromJson((JsonNode) change.content()).apply(oldJsonNode);
                    } catch (JsonPatchConflictException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new JsonPatchConflictException("failed to apply JSON patch: " + change, e);
                    }

                    // Apply only when the contents are really different.
                    if (!newJsonNode.equals(oldJsonNode)) {
                        final String newContent;
                        // NB: Some JSON5 or YAML features, such as comments, will be lost
                        //     when using JSON Patch.
                        if (isYaml(changePath)) {
                            newContent = Yaml.writeValueAsString(newJsonNode);
                        } else {
                            newContent = Jackson.writeValueAsString(newJsonNode);
                        }
                        applyPathEdit(dirCache, new InsertText(changePath, inserter, newContent));
                        numEdits++;
                    }
                    break;
                }
                case APPLY_TEXT_PATCH:
                    final Patch<String> patch = DiffUtils.parseUnifiedDiff(
                            Util.stringToLines(sanitizeText((String) change.content())));

                    final String sanitizedOldText;
                    final List<String> sanitizedOldTextLines;
                    if (oldContent != null) {
                        sanitizedOldText = sanitizeText(new String(oldContent, UTF_8));
                        sanitizedOldTextLines = Util.stringToLines(sanitizedOldText);
                    } else {
                        sanitizedOldText = null;
                        sanitizedOldTextLines = Collections.emptyList();
                    }

                    final String newText;
                    try {
                        final List<String> newTextLines = DiffUtils.patch(sanitizedOldTextLines, patch);
                        if (newTextLines.isEmpty()) {
                            newText = "";
                        } else {
                            final StringJoiner joiner = new StringJoiner("\n", "", "\n");
                            for (String line : newTextLines) {
                                joiner.add(line);
                            }
                            newText = joiner.toString();
                        }
                    } catch (Exception e) {
                        String message = "failed to apply text patch: " + change;
                        if (e.getMessage() != null) {
                            message += " (reason: " + e.getMessage() + ')';
                        }
                        throw new TextPatchConflictException(message, e);
                    }

                    // Apply only when the contents are really different.
                    if (!newText.equals(sanitizedOldText)) {
                        applyPathEdit(dirCache, new InsertText(changePath, inserter, newText));
                        numEdits++;
                    }
                    break;
            }
        }
        return numEdits;
    }

    private static JsonNode toJsonNode(String path, @Nullable byte[] content) throws JsonParseException {
        if (content == null) {
            return Jackson.nullNode;
        }

        return Jackson.readTree(path, content);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("changes", changes)
                          .toString();
    }

    /**
     * Applies recursive directory edits.
     *
     * @param oldDir the path to the directory to make a recursive change
     * @param newDir the path to the renamed directory, or {@code null} to remove the directory.
     *
     * @return {@code true} if any edits were made to {@code dirCache}, {@code false} otherwise
     */
    private static boolean applyDirectoryEdits(DirCache dirCache,
                                               String oldDir, @Nullable String newDir, Change<?> change) {

        if (!oldDir.endsWith("/")) {
            oldDir += '/';
        }
        if (newDir != null && !newDir.endsWith("/")) {
            newDir += '/';
        }

        final byte[] rawOldDir = Constants.encode(oldDir);
        final byte[] rawNewDir = newDir != null ? Constants.encode(newDir) : null;
        final int numEntries = dirCache.getEntryCount();
        DirCacheEditor editor = null;

        loop:
        for (int i = 0; i < numEntries; i++) {
            final DirCacheEntry e = dirCache.getEntry(i);
            final byte[] rawPath = e.getRawPath();

            // Ensure that there are no entries under the newDir; we have a conflict otherwise.
            if (rawNewDir != null) {
                boolean conflict = true;
                if (rawPath.length > rawNewDir.length) {
                    // Check if there is a file whose path starts with 'newDir'.
                    for (int j = 0; j < rawNewDir.length; j++) {
                        if (rawNewDir[j] != rawPath[j]) {
                            conflict = false;
                            break;
                        }
                    }
                } else if (rawPath.length == rawNewDir.length - 1) {
                    // Check if there is a file whose path is exactly same with newDir without trailing '/'.
                    for (int j = 0; j < rawNewDir.length - 1; j++) {
                        if (rawNewDir[j] != rawPath[j]) {
                            conflict = false;
                            break;
                        }
                    }
                } else {
                    conflict = false;
                }

                if (conflict) {
                    throw new ChangeConflictException("target directory exists already: " + change);
                }
            }

            // Skip the entries that do not belong to the oldDir.
            if (rawPath.length <= rawOldDir.length) {
                continue;
            }
            for (int j = 0; j < rawOldDir.length; j++) {
                if (rawOldDir[j] != rawPath[j]) {
                    continue loop;
                }
            }

            // Do not create an editor until we find an entry to rename/remove.
            // We can tell if there was any matching entries or not from the nullness of editor later.
            if (editor == null) {
                editor = dirCache.editor();
                editor.add(new DeleteTree(oldDir));
                if (newDir == null) {
                    // Recursive removal
                    break;
                }
            }

            assert newDir != null; // We should get here only when it's a recursive rename.

            final String oldPath = e.getPathString();
            final String newPath = newDir + oldPath.substring(oldDir.length());
            editor.add(new CopyOldEntry(newPath, e));
        }

        if (editor != null) {
            editor.finish();
            return true;
        } else {
            return false;
        }
    }

    private static void reportNonExistentEntry(Change<?> change) {
        throw new ChangeConflictException("non-existent file/directory: " + change);
    }
}
