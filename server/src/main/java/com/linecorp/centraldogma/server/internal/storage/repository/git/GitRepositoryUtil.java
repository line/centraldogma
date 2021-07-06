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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.centraldogma.server.storage.repository.Repository.ALL_PATH;
import static com.linecorp.centraldogma.server.storage.repository.Repository.MAX_MAX_COMMITS;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEditor.DeletePath;
import org.eclipse.jgit.dircache.DirCacheEditor.DeleteTree;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdOwnerMap;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.TreeRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.server.storage.StorageException;

import difflib.DiffUtils;
import difflib.Patch;

final class GitRepositoryUtil {

    private static final Logger logger = LoggerFactory.getLogger(GitRepositoryUtil.class);

    private static final byte[] EMPTY_BYTE = new byte[0];

    private static final Pattern CR = Pattern.compile("\r", Pattern.LITERAL);

    private static final Field revWalkObjectsField;

    static {
        Field field = null;
        try {
            field = RevWalk.class.getDeclaredField("objects");
            if (field.getType() != ObjectIdOwnerMap.class) {
                throw new IllegalStateException(
                        RevWalk.class.getSimpleName() + ".objects is not an " +
                        ObjectIdOwnerMap.class.getSimpleName() + '.');
            }
            field.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(
                    RevWalk.class.getSimpleName() + ".objects does not exist.");
        }

        revWalkObjectsField = field;
    }

    static RevWalk newRevWalk(Repository jGitRepository) {
        final RevWalk revWalk = new RevWalk(jGitRepository);
        // Disable rewriteParents because otherwise `RevWalk` will load every commit into memory.
        revWalk.setRewriteParents(false);
        return revWalk;
    }

    static RevWalk newRevWalk(ObjectReader reader) {
        final RevWalk revWalk = new RevWalk(reader);
        // Disable rewriteParents because otherwise `RevWalk` will load every commit into memory.
        revWalk.setRewriteParents(false);
        return revWalk;
    }

    static RevTree toTree(CommitIdDatabase commitIdDatabase, RevWalk revWalk, Revision revision) {
        final ObjectId commitId = commitIdDatabase.get(revision);
        try {
            return revWalk.parseCommit(commitId).getTree();
        } catch (IOException e) {
            throw new StorageException("failed to parse a commit: " + commitId, e);
        }
    }

    static int applyChanges(Repository jGitRepo, @Nullable Revision baseRevision,
                            @Nullable ObjectId baseTreeId, DirCache dirCache, Iterable<Change<?>> changes) {
        int numEdits = 0;

        try (ObjectInserter inserter = jGitRepo.newObjectInserter();
             ObjectReader reader = jGitRepo.newObjectReader()) {

            if (baseTreeId != null) {
                // the DirCacheBuilder is to used for doing update operations on the given DirCache object
                final DirCacheBuilder builder = dirCache.builder();

                // Add the tree object indicated by the prevRevision to the temporary DirCache object.
                builder.addTree(EMPTY_BYTE, 0, reader, baseTreeId);
                builder.finish();
            }

            // loop over the specified changes.
            for (Change<?> change : changes) {
                final String changePath = change.path().substring(1); // Strip the leading '/'.
                final DirCacheEntry oldEntry = dirCache.getEntry(changePath);
                final byte[] oldContent = oldEntry != null ? reader.open(oldEntry.getObjectId()).getBytes()
                                                           : null;

                switch (change.type()) {
                    case UPSERT_JSON: {
                        final JsonNode oldJsonNode = oldContent != null ? Jackson.readTree(oldContent) : null;
                        final JsonNode newJsonNode = firstNonNull((JsonNode) change.content(),
                                                                  JsonNodeFactory.instance.nullNode());

                        // Upsert only when the contents are really different.
                        if (!Objects.equals(newJsonNode, oldJsonNode)) {
                            applyPathEdit(dirCache, new InsertJson(changePath, inserter, newJsonNode));
                            numEdits++;
                        }
                        break;
                    }
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
                        final JsonNode oldJsonNode;
                        if (oldContent != null) {
                            oldJsonNode = Jackson.readTree(oldContent);
                        } else {
                            oldJsonNode = Jackson.nullNode;
                        }

                        final JsonNode newJsonNode;
                        try {
                            newJsonNode = JsonPatch.fromJson((JsonNode) change.content()).apply(oldJsonNode);
                        } catch (Exception e) {
                            throw new ChangeConflictException("failed to apply JSON patch: " + change, e);
                        }

                        // Apply only when the contents are really different.
                        if (!newJsonNode.equals(oldJsonNode)) {
                            applyPathEdit(dirCache, new InsertJson(changePath, inserter, newJsonNode));
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
                            throw new ChangeConflictException("failed to apply text patch: " + change, e);
                        }

                        // Apply only when the contents are really different.
                        if (!newText.equals(sanitizedOldText)) {
                            applyPathEdit(dirCache, new InsertText(changePath, inserter, newText));
                            numEdits++;
                        }
                        break;
                }
            }
        } catch (CentralDogmaException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to apply changes on revision " + baseRevision, e);
        }
        return numEdits;
    }

    private static void applyPathEdit(DirCache dirCache, PathEdit edit) {
        final DirCacheEditor e = dirCache.editor();
        e.add(edit);
        e.finish();
    }

    /**
     * Removes {@code \r} and appends {@code \n} on the last line if it does not end with {@code \n}.
     */
    private static String sanitizeText(String text) {
        if (text.indexOf('\r') >= 0) {
            text = CR.matcher(text).replaceAll("");
        }
        if (!text.isEmpty() && !text.endsWith("\n")) {
            text += "\n";
        }
        return text;
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

    @VisibleForTesting
    static void doRefUpdate(Repository jGitRepo, RevWalk revWalk,
                            String ref, ObjectId commitId) throws IOException {
        if (ref.startsWith(Constants.R_TAGS)) {
            final Ref oldRef = jGitRepo.exactRef(ref);
            if (oldRef != null) {
                throw new StorageException("tag ref exists already: " + ref);
            }
        }

        final RefUpdate refUpdate = jGitRepo.updateRef(ref);
        refUpdate.setNewObjectId(commitId);

        final Result res = refUpdate.update(revWalk);
        switch (res) {
            case NEW:
            case FAST_FORWARD:
                // Expected
                break;
            default:
                throw new StorageException("unexpected refUpdate state: " + res);
        }
    }

    static List<Commit> getCommits(InternalRepository repo, String pathPattern, int maxCommits,
                                   RevisionRange range, RevisionRange descendingRange) {
        try (RevWalk revWalk = newRevWalk(repo.jGitRepo())) {
            final ObjectIdOwnerMap<?> revWalkInternalMap =
                    (ObjectIdOwnerMap<?>) revWalkObjectsField.get(revWalk);

            final CommitIdDatabase commitIdDatabase = repo.commitIdDatabase();
            final ObjectId fromCommitId = commitIdDatabase.get(descendingRange.from());
            final ObjectId toCommitId = commitIdDatabase.get(descendingRange.to());

            revWalk.markStart(revWalk.parseCommit(fromCommitId));
            revWalk.setRetainBody(false);

            // Instead of relying on RevWalk to filter the commits,
            // we let RevWalk yield all commits so we can:
            // - Have more control on when iteration should be stopped.
            //   (A single Iterator.next() doesn't take long.)
            // - Clean up the internal map as early as possible.
            final RevFilter filter = new TreeRevFilter(revWalk, AndTreeFilter.create(
                    TreeFilter.ANY_DIFF, PathPatternFilter.of(pathPattern)));

            // Search up to 1000 commits when maxCommits <= 100.
            // Search up to (maxCommits * 10) commits when 100 < maxCommits <= 1000.
            final int maxNumProcessedCommits = Math.max(maxCommits * 10, MAX_MAX_COMMITS);

            final List<Commit> commitList = new ArrayList<>();
            int numProcessedCommits = 0;
            for (RevCommit revCommit : revWalk) {
                numProcessedCommits++;

                if (filter.include(revWalk, revCommit)) {
                    revWalk.parseBody(revCommit);
                    commitList.add(toCommit(revCommit));
                    revCommit.disposeBody();
                }

                if (revCommit.getId().equals(toCommitId) ||
                    commitList.size() >= maxCommits ||
                    // Prevent from iterating for too long.
                    numProcessedCommits >= maxNumProcessedCommits) {
                    break;
                }

                // Clear the internal lookup table of RevWalk to reduce the memory usage.
                // This is safe because we have linear history and traverse in one direction.
                if (numProcessedCommits % 16 == 0) {
                    revWalkInternalMap.clear();
                }
            }

            // Include the initial empty commit only when the caller specified
            // the initial revision (1) in the range and the pathPattern contains '/**'.
            if (commitList.size() < maxCommits &&
                descendingRange.to().major() == 1 &&
                pathPattern.contains(ALL_PATH)) {
                try (RevWalk tmpRevWalk = newRevWalk(repo.jGitRepo())) {
                    final RevCommit lastRevCommit = tmpRevWalk.parseCommit(toCommitId);
                    commitList.add(toCommit(lastRevCommit));
                }
            }

            if (!descendingRange.equals(range)) { // from and to is swapped so reverse the list.
                Collections.reverse(commitList);
            }

            return commitList;
        } catch (CentralDogmaException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(
                    "failed to retrieve the history: " + repo.repoDir() +
                    " (" + pathPattern + ", " + range.from() + ".." + range.to() + ')', e);
        }
    }

    private static Commit toCommit(RevCommit revCommit) {
        final Author author;
        final PersonIdent committerIdent = revCommit.getCommitterIdent();
        final long when;
        if (committerIdent == null) {
            author = Author.UNKNOWN;
            when = 0;
        } else {
            author = new Author(committerIdent.getName(), committerIdent.getEmailAddress());
            when = committerIdent.getWhen().getTime();
        }

        try {
            return CommitUtil.newCommit(author, when, revCommit.getFullMessage());
        } catch (Exception e) {
            throw new StorageException("failed to create a Commit", e);
        }
    }

    static boolean deleteDirectory(File dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            return walk.sorted(Comparator.reverseOrder())
                       .map(Path::toFile)
                       .map(File::delete)
                       // Return false if it fails to delete a file.
                       .reduce(true, (a, b) -> a && b);
        }
    }

    static boolean isEmpty(File dir) throws IOException {
        if (!dir.isDirectory()) {
            return false;
        }
        if (!dir.exists()) {
            return true;
        }
        try (Stream<Path> entries = Files.list(dir.toPath())) {
            return entries.findFirst().isPresent();
        }
    }

    static void closeJGitRepo(Repository repository) {
        try {
            repository.close();
        } catch (Throwable t) {
            logger.warn("Failed to close a Git repository: {}", repository.getDirectory(), t);
        }
    }

    // PathEdit implementations which is used when applying changes.

    private static final class InsertText extends PathEdit {
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

    private static final class InsertJson extends PathEdit {
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
                ent.setObjectId(inserter.insert(Constants.OBJ_BLOB, Jackson.writeValueAsBytes(jsonNode)));
                ent.setFileMode(FileMode.REGULAR_FILE);
            } catch (IOException e) {
                throw new StorageException("failed to create a new JSON blob", e);
            }
        }
    }

    private static final class CopyOldEntry extends PathEdit {
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

    private GitRepositoryUtil() {}
}
