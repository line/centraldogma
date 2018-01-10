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

import static com.linecorp.centraldogma.common.Revision.HEAD;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.internal.storage.StorageException;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.ChangeConflictException;
import com.linecorp.centraldogma.server.internal.storage.repository.RedundantChangeException;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;
import com.linecorp.centraldogma.server.internal.storage.repository.RevisionNotFoundException;

public class GitRepositoryTest {

    private static final String SUMMARY = "summary";

    private static final int NUM_ITERATIONS = 3;

    @ClassRule
    public static final TemporaryFolder repoDir = new TemporaryFolder();

    private static GitRepository repo;

    /**
     * Used by {@link GitRepositoryTest#testWatchWithQueryCancellation()}.
     */
    private static Consumer<CompletableFuture<Revision>> watchConsumer;

    @BeforeClass
    public static void init() throws Exception {
        repo = new GitRepository(mock(Project.class), repoDir.getRoot(), commonPool(), 0L, Author.SYSTEM) {
            /**
             * Used by {@link GitRepositoryTest#testWatchWithQueryCancellation()}.
             */
            @Override
            public CompletableFuture<Revision> watch(Revision lastKnownRevision, String pathPattern) {
                final CompletableFuture<Revision> f = super.watch(lastKnownRevision, pathPattern);
                if (watchConsumer != null) {
                    watchConsumer.accept(f);
                }
                return f;
            }
        };
    }

    @AfterClass
    public static void destroy() {
        if (repo != null) {
            repo.close();
        }
    }

    @Rule
    public final TestName testName = new TestName();

    private String prefix;
    private String allPattern;
    private final String[] jsonPaths = new String[NUM_ITERATIONS];
    private final String[] textPaths = new String[NUM_ITERATIONS];
    private final Change<JsonNode>[] jsonUpserts = Util.unsafeCast(new Change[NUM_ITERATIONS]);
    private final Change<String>[] textUpserts = Util.unsafeCast(new Change[NUM_ITERATIONS]);
    private final Change<JsonNode>[] jsonPatches = Util.unsafeCast(new Change[NUM_ITERATIONS]);
    private final Change<String>[] textPatches = Util.unsafeCast(new Change[NUM_ITERATIONS]);

    @Before
    public void setUp() throws Exception {
        prefix = '/' + testName.getMethodName() + '/';
        allPattern = prefix + "**";

        for (int i = 0; i < NUM_ITERATIONS; i++) {
            final String jsonPath = prefix + i + ".json";
            final String textPath = prefix + i + ".txt";

            jsonPaths[i] = jsonPath;
            textPaths[i] = textPath;
            jsonUpserts[i] = Change.ofJsonUpsert(jsonPath, "{ \"" + i + "\": " + i + " }");
            textUpserts[i] = Change.ofTextUpsert(textPath, "value:\n" + i);
        }

        jsonPatches[0] = Change.ofJsonPatch(jsonPaths[0], null, jsonUpserts[0].content());
        textPatches[0] = Change.ofTextPatch(textPaths[0], null, textUpserts[0].content());

        for (int i = 1; i < NUM_ITERATIONS; i++) {
            jsonPatches[i] = Change.ofJsonPatch(jsonPaths[0], jsonUpserts[i - 1].content(),
                                                jsonUpserts[i].content());
            textPatches[i] = Change.ofTextPatch(textPaths[0], textUpserts[i - 1].content(),
                                                textUpserts[i].content());
        }

        watchConsumer = null;
    }

    @Test
    public void testJsonUpsert() throws Exception {
        testUpsert(jsonUpserts, EntryType.JSON);
    }

    @Test
    public void testTextUpsert() throws Exception {
        testUpsert(textUpserts, EntryType.TEXT);
    }

    private void testUpsert(Change<?>[] upserts, EntryType entryType) {
        final Revision oldHeadRev = repo.normalizeNow(HEAD);
        for (int i = 0; i < upserts.length; i++) {
            final Change<?> change = upserts[i];

            final Revision revision = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, change).join();

            // Ensure the revision is incremented.
            assertThat(revision.major()).isEqualTo(oldHeadRev.major() + i + 1);
            assertThat(repo.normalizeNow(HEAD)).isEqualTo(revision);

            // Ensure that the entries which were created in the previous revisions are retrieved
            // as well as the entry in the latest revision.
            final Map<String, Entry<?>> entries = repo.find(revision, allPattern).join();
            assertThat(entries).hasSize(i + 1);
            for (int j = 0; j <= i; j++) {
                assertThat(entries).containsKey(upserts[j].path());
            }
        }

        // Check the content of all entries.
        Map<String, Entry<?>> entries = Util.unsafeCast(repo.find(HEAD, allPattern).join());
        for (Change<?> c : upserts) {
            final String path = c.path();
            if (entryType == EntryType.TEXT) {
                // Text must be sanitized so that the last line ends with \n.
                assertThat(entries).containsEntry(path, Entry.of(path, c.content() + "\n", EntryType.TEXT));
            } else {
                assertThat(entries).containsEntry(path, Entry.of(path, c.content(), entryType));
            }
        }
    }

    @Test
    public void testJsonPatch_safeReplace() throws JsonProcessingException {
        String jsonFilePath = String.format("/test_%s.json", testName.getMethodName());
        Change<JsonNode> change = Change.ofJsonUpsert(jsonFilePath, "{\"key\":1}");
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, change).join();
        Change<JsonNode> nextChange = Change.ofJsonPatch(jsonFilePath, "{\"key\":2}", "{\"key\":3}");
        assertThatThrownBy(
                () -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, nextChange).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChangeConflictException.class);
    }

    @Test
    public void testJsonPatch() throws Exception {
        testPatch(jsonPatches, jsonUpserts);
    }

    @Test
    public void testTextPatch() throws Exception {
        testPatch(textPatches, textUpserts);
    }

    private static void testPatch(Change<?>[] patches, Change<?>[] upserts) {
        final String path = patches[0].path();
        for (int i = 0; i < NUM_ITERATIONS; i++) {
            assert path.equals(patches[i].path());

            final Revision rev = repo.normalizeNow(HEAD);

            // Ensure that we cannot apply patched in an incorrect order.
            for (int j = i + 1; j < NUM_ITERATIONS; j++) {
                final int finalJ = j;
                assertThatThrownBy(
                        () -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, patches[finalJ]).join())
                        .isInstanceOf(CompletionException.class)
                        .hasCauseInstanceOf(StorageException.class);
            }

            // Ensure that the failed commit does not change the revision.
            assertThat(repo.normalizeNow(HEAD)).isEqualTo(rev);

            // Ensure that the successful commit changes the revision.
            Revision newRev = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, patches[i]).join();
            assertThat(repo.normalizeNow(HEAD)).isEqualTo(newRev);
            assertThat(newRev).isEqualTo(new Revision(rev.major() + 1));

            // Ensure the entry has been patched as expected.
            Entry<?> e = repo.get(HEAD, path).join();
            if (e.type() == EntryType.JSON) {
                assertThatJson(e.content()).isEqualTo(upserts[i].content());
            } else {
                // Text must be sanitized so that the last line ends with \n.
                assertThat(e.content()).isEqualTo(upserts[i].content() + "\n");
            }
        }
    }

    @Test
    public void testRemoval() throws Exception {
        // A removal should fail when there's no such entry.
        assertThatThrownBy(() -> repo
                .commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, Change.ofRemoval(jsonPaths[0])).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChangeConflictException.class);

        Revision revision = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[0]).join();
        assertThat(repo.exists(revision, jsonPaths[0]).join()).isTrue();

        // A removal should succeed when there's an entry.
        revision = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, Change.ofRemoval(jsonPaths[0])).join();
        assertThat(repo.exists(revision, jsonPaths[0]).join()).isFalse();

        // A removal should fail when there's no such entry.
        assertThatThrownBy(() -> repo
                .commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, Change.ofRemoval(jsonPaths[0])).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChangeConflictException.class);
    }

    @Test
    public void testRecursiveRemoval() throws Exception {
        // A recursive removal should fail when there's no such entry.
        final String curDir = prefix.substring(0, prefix.length() - 1); // Remove trailing '/'.
        assertThatThrownBy(
                () -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, Change.ofRemoval(curDir)).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChangeConflictException.class);

        // Add some files
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts).join();
        assertThat(repo.find(HEAD, allPattern).join()).hasSize(jsonUpserts.length);

        // Perform a recursive removal
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, Change.ofRemoval(curDir)).join();
        assertThat(repo.find(HEAD, allPattern).join()).isEmpty();
    }

    @Test
    public void testRename() throws Exception {
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[0]).join();

        // Rename without content modification.
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, Change.ofRename(jsonPaths[0], jsonPaths[1])).join();

        assertThat(repo.exists(HEAD, jsonPaths[0]).join()).isFalse();
        assertThat(repo.exists(HEAD, jsonPaths[1]).join()).isTrue();
        assertThat(repo.exists(HEAD, jsonPaths[2]).join()).isFalse();
        assertThatJson(repo.get(HEAD, jsonPaths[1]).join().content())
                .isEqualTo(jsonUpserts[0].content());

        // Rename with content modification.
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                    Change.ofRename(jsonPaths[1], jsonPaths[2]),
                    Change.ofJsonPatch(jsonPaths[2], jsonPatches[1].content()),
                    Change.ofJsonPatch(jsonPaths[2], jsonPatches[2].content())).join();

        assertThat(repo.exists(HEAD, jsonPaths[0]).join()).isFalse();
        assertThat(repo.exists(HEAD, jsonPaths[1]).join()).isFalse();
        assertThat(repo.exists(HEAD, jsonPaths[2]).join()).isTrue();
        assertThatJson(repo.get(HEAD, jsonPaths[2]).join().content())
                .isEqualTo(jsonUpserts[2].content());
    }

    @Test
    public void testRecursiveRename() throws Exception {
        // Add some files under a directory.
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts).join();
        assertThat(repo.find(HEAD, allPattern).join()).hasSize(jsonUpserts.length);

        // Rename the directory and ensure all files were moved.
        final String oldDir = prefix.substring(0, prefix.length() - 1); // Strip the trailing '/'.
        final String newDir = "/re_" + oldDir.substring(1) + "_named";
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, Change.ofRename(oldDir, newDir)).join();
        assertThat(repo.find(HEAD, allPattern).join()).isEmpty();
        assertThat(repo.find(HEAD, newDir + "/**").join()).hasSize(jsonUpserts.length);

        // Add some files under a directory again.
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts).join();
        assertThat(repo.find(HEAD, allPattern).join()).hasSize(jsonUpserts.length);

        // Attempt to rename the directory again, which should fail because the target directory exists now.
        assertThatThrownBy(() -> repo
                .commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, Change.ofRename(oldDir, newDir)).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChangeConflictException.class);
    }

    @Test
    public void testRenameFailure() throws Exception {
        assertThatThrownBy(() -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                             jsonUpserts[0], jsonUpserts[1],
                                             Change.ofRename(jsonPaths[0], jsonPaths[1])).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChangeConflictException.class);

        // Renaming to its own path.
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[0]).join();
        assertThatThrownBy(() -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                             Change.ofRename(jsonPaths[0], jsonPaths[0])).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChangeConflictException.class);

        // Renaming to its own path, when the file is not committed yet.
        assertThatThrownBy(() -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                             jsonUpserts[1], Change.ofRename(jsonPaths[1], jsonPaths[1]))
                                     .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChangeConflictException.class);
    }

    /**
     * Tests the case where a commit is attempted at an old base revision.
     */
    @Test
    public void testLateCommit() throws Exception {
        // Increase the head revision by one by pushing one commit.
        Revision rev = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[0]).join();

        // Attempt to commit again with an old revision.
        assertThatThrownBy(() -> repo
                .commit(new Revision(rev.major() - 1), 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[1]).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChangeConflictException.class);
    }

    @Test
    public void testEmptyCommit() {
        assertThatThrownBy(
                () -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, Collections.emptyList()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RedundantChangeException.class);
    }

    @Test
    public void testEmptyCommitWithRedundantRenames() throws Exception {
        // Create a file to produce redundant changes.
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[0]).join();

        // Ensure redundant changes do not count as a valid change.
        assertThatThrownBy(() -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                             Change.ofRename(jsonPaths[0], jsonPaths[1]),
                                             Change.ofRename(jsonPaths[1], jsonPaths[0])).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RedundantChangeException.class);
    }

    @Test
    public void testEmptyCommitWithRedundantUpsert() throws Exception {
        assertThatThrownBy(
                () -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, Collections.emptyList()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RedundantChangeException.class);

        // Create a file to produce redundant changes.
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[0]).join();

        // Ensure redundant changes do not count as a valid change.
        assertThatThrownBy(
                () -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[0]).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RedundantChangeException.class);
    }

    @Test
    public void testEmptyCommitWithRedundantUpsert2() throws Exception {
        // Create files to produce redundant changes.
        final Change<JsonNode> change1 = Change.ofJsonUpsert("/redundant_upsert_2.json",
                                                             "{ \"foo\": 0, \"bar\": 1 }");
        final Change<String> change2 = Change.ofTextUpsert("/redundant_upsert_2.txt", "foo");
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, change1).join();
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, change2).join();

        // Ensure redundant changes do not count as a valid change.
        assertThatThrownBy(() -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, change1).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RedundantChangeException.class);
        assertThatThrownBy(() -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, change2).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RedundantChangeException.class);

        // Ensure a change only whose serialized form is different does not count.
        final Change<JsonNode> change1a = Change.ofJsonUpsert("/redundant_upsert_2.json",
                                                              "{ \"bar\": 1, \"foo\": 0 }");
        assertThatThrownBy(() -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, change1a).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RedundantChangeException.class);
    }

    @Test
    public void testTextSanitization() throws Exception {
        // Ensure CRs are stripped.
        final Change<String> dosText = Change.ofTextUpsert("/text_sanitization_dos.txt", "foo\r\nbar\r\n");
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, dosText).join();
        assertThat(repo.get(HEAD, dosText.path()).join().contentAsText()).isEqualTo("foo\nbar\n");

        // Ensure redundant commits are rejected.
        assertThatThrownBy(() -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                             Change.ofTextUpsert(dosText.path(), "foo\nbar\n")).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RedundantChangeException.class);
        assertThatThrownBy(() -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                             Change.ofTextUpsert(dosText.path(), "foo\nbar")).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RedundantChangeException.class);
        //// However, additional empty lines should be treated as different.
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                    Change.ofTextUpsert(dosText.path(), "foo\nbar\r\n\n")).join();
        assertThat(repo.get(HEAD, dosText.path()).join().contentAsText()).isEqualTo("foo\nbar\n\n");

        // Ensure trailing \n is added if missing.
        final Change<String> withoutNewline = Change.ofTextUpsert("/text_sanitization_without_lf.txt", "foo");
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, withoutNewline).join();
        assertThat(repo.get(HEAD, withoutNewline.path()).join().contentAsText()).isEqualTo("foo\n");

        // Ensure trailing \n is not added if exists.
        final Change<String> withNewline = Change.ofTextUpsert("/text_sanitization_with_lf.txt", "foo\n");
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, withNewline).join();
        assertThat(repo.get(HEAD, withNewline.path()).join().contentAsText()).isEqualTo("foo\n");
    }

    @Test
    public void testMultipleChanges() throws Exception {
        List<Change<?>> changes = new ArrayList<>();
        Collections.addAll(changes, jsonUpserts);
        for (int i = 1; i < jsonPatches.length; i++) {
            changes.add(jsonPatches[i]);
        }

        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, changes).join();

        Map<String, Entry<?>> entries = repo.find(HEAD, allPattern).join();

        assertThat(entries).hasSize(jsonUpserts.length);
        for (int i = 0; i < jsonUpserts.length; i++) {
            Change<?> c = jsonUpserts[i];
            assertThat(entries).containsKey(c.path());

            if (i == 0) { // We have patched the first upsert to make it identical to the last upsert.
                assertThatJson(entries.get(c.path()).content())
                        .isEqualTo(jsonUpserts[jsonUpserts.length - 1].content());
            } else {
                assertThatJson(entries.get(c.path()).content()).isEqualTo(c.content());
            }
        }
    }

    @Test
    public void testRenameWithConflict() throws Exception {
        // Create a file to produce redundant changes.
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[0]).join();

        // Attempt to rename to itself.
        assertThatThrownBy(() -> repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                             Change.ofRename(jsonPaths[0], jsonPaths[0])).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChangeConflictException.class);
    }

    @Test
    public void testMultipleChangesWithConflict() throws Exception {
        assertThatThrownBy(() -> repo
                .commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[0], jsonPatches[2]).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChangeConflictException.class);
    }

    /**
     * Test exception handling when invalid arguments are given.
     */
    @Test
    public void testDiff_invalidParameters() throws Exception {
        final String path = jsonPatches[0].path();
        final Revision revision1 = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                               jsonPatches[0]).join();
        final Revision revision2 = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                               jsonPatches[1]).join();

        assertThat(repo.diff(revision1, revision2, "non_existing_path").join()).isEmpty();

        assertThatThrownBy(() -> repo.diff(revision1, revision2, (String) null).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> repo.diff(null, revision2, path).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> repo.diff(revision1, new Revision(revision2.major() + 1), path).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageException.class);

        assertThatThrownBy(() -> repo.diff(new Revision(revision2.major() + 1), revision2, path).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageException.class);
    }

    @Test
    public void testPreviewDiff() {
        Map<String, Change<?>> changeMap = repo.previewDiff(HEAD, jsonUpserts[0]).join();
        assertThat(changeMap).containsEntry(jsonPaths[0], jsonUpserts[0]);

        // Invalid patch
        assertThatThrownBy(() -> repo.previewDiff(HEAD, jsonPatches[1]).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageException.class);

        // Invalid removal
        assertThatThrownBy(() -> repo.previewDiff(HEAD, Change.ofRemoval(jsonPaths[0])).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageException.class);

        // Apply a series of changes
        List<Change<?>> changes = Arrays.asList(jsonUpserts[0], jsonPatches[1], jsonPatches[2],
                                                Change.ofRename(jsonPaths[0], jsonPaths[1]),
                                                Change.ofRemoval(jsonPaths[1]));
        Map<String, Change<?>> returnedChangeMap = repo.previewDiff(HEAD, changes).join();
        assertThat(returnedChangeMap).isEmpty();
        assertThatThrownBy(() -> repo.previewDiff(new Revision(Integer.MAX_VALUE), changes).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RevisionNotFoundException.class);

        assertThat(repo.previewDiff(new Revision(-1), Collections.emptyList()).join()).isEmpty();

        // Test upsert on an existing path
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonPatches[0], jsonPatches[1]).join();
        returnedChangeMap = repo.previewDiff(HEAD, jsonUpserts[0]).join();
        assertThat(returnedChangeMap.get(jsonPaths[0]).type()).isEqualTo(ChangeType.APPLY_JSON_PATCH);
    }

    /**
     * Run a sequence of add operation on the same path, valid the diff after each push.
     */
    @Test
    public void testDiff_add() throws Exception {
        final String jsonPath = jsonUpserts[0].path();
        final String textPath = textUpserts[0].path();

        Revision prevRevison = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                           jsonUpserts[0], textUpserts[0]).join();

        for (int i = 1; i < NUM_ITERATIONS; i++) {
            Revision currRevision = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                                jsonPatches[i], textPatches[i]).join();

            Map<String, Change<?>> diff = repo.diff(prevRevison, currRevision, Repository.ALL_PATH).join();

            assertThat(diff).hasSize(2)
                            .containsEntry(jsonPath, jsonPatches[i])
                            .containsEntry(textPath, textPatches[i]);

            Map<String, Change<?>> diff2 =
                    repo.diff(HEAD.backward(1), HEAD, Repository.ALL_PATH).join();

            assertThat(diff2).isEqualTo(diff);

            prevRevison = currRevision;
        }
    }

    /**
     * Run a sequence of remove operation on the same path, valid the diff after each push.
     */
    @Test
    public void testDiff_remove() throws Exception {
        // add all files into repository
        Revision lastRevision = null;
        for (int i = 0; i < NUM_ITERATIONS; i++) {
            lastRevision = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                       jsonUpserts[i], textUpserts[i]).join();
        }

        Revision prevRevison = lastRevision;
        for (int i = 1; i < NUM_ITERATIONS; i++) {
            final String jsonPath = jsonUpserts[i].path();
            final String textPath = textUpserts[i].path();

            final Change<Void> jsonRemoval = Change.ofRemoval(jsonPath);
            final Change<Void> textRemoval = Change.ofRemoval(textPath);

            final Revision currRevision = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                                      jsonRemoval, textRemoval).join();
            final Map<String, Change<?>> changes = repo.diff(prevRevison, currRevision,
                                                             Repository.ALL_PATH).join();

            assertThat(changes).hasSize(2)
                               .containsEntry(jsonPath, jsonRemoval)
                               .containsEntry(textPath, textRemoval);

            final Map<String, Change<?>> changesRelative =
                    repo.diff(HEAD.backward(1), HEAD, allPattern).join();

            assertThat(changesRelative).isEqualTo(changes);

            prevRevison = currRevision;
        }
    }

    /**
     * Run a sequence of modification on the same path, validate the diff after each push.
     */
    @Test
    public void testDiff_modify() throws Exception {
        final String jsonNodePath = jsonPatches[0].path();
        final String textNodePath = textPatches[0].path();

        // initial commit
        Revision prevRevision = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                            jsonPatches[0], textPatches[0]).join();

        for (int i = 1; i < NUM_ITERATIONS; i++) {
            final Revision currRevision = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                                      jsonPatches[i], textPatches[i]).join();

            final Map<String, Change<?>> changes = repo.diff(prevRevision, currRevision, allPattern).join();

            assertThat(changes).hasSize(2)
                               .containsEntry(jsonNodePath, jsonPatches[i])
                               .containsEntry(textNodePath, textPatches[i]);

            final Map<String, Change<?>> changesRelative =
                    repo.diff(HEAD.backward(1), HEAD, allPattern).join();

            assertThat(changesRelative).isEqualTo(changes);

            prevRevision = currRevision;
        }
    }

    /**
     * Makes sure that diff() works as expected for the two commits:
     * - Rename an entry and
     * - Updates its content.
     */
    @Test
    public void testDiff_twoCommits() throws Exception {
        final String oldPath = prefix + "foo/a.json";
        final String newPath = prefix + "bar/a.json";

        // Start at oldPath with value set to false.
        Revision rev0 = repo.commit(
                HEAD, 0L, Author.UNKNOWN, SUMMARY,
                Change.ofJsonUpsert(oldPath, "{ \"value\": false }")).join();

        // Move to newPath with the same value.
        Revision rev1 = repo.commit(
                HEAD, 0L, Author.UNKNOWN, SUMMARY,
                Change.ofRemoval(oldPath),
                Change.ofJsonUpsert(newPath, "{ \"value\": false }")).join();

        // Set 'value' to true.
        Revision rev2 = repo.commit(
                HEAD, 0L, Author.UNKNOWN, SUMMARY,
                Change.ofJsonUpsert(newPath, "{ \"value\": true }")).join();

        // Get the diff between rev0 and rev2.
        final Map<String, Change<?>> diff = repo.diff(rev0, rev2, prefix + "*/*.json").join();

        assertThat(diff).hasSize(2);
        assertThat(diff.get(oldPath)).isNotNull();
        assertThat(diff.get(newPath)).isNotNull();

        assertThat(diff.get(oldPath).type()).isEqualTo(ChangeType.REMOVE);
        assertThat(diff.get(newPath).type()).isEqualTo(ChangeType.UPSERT_JSON);
        assertThatJson(diff.get(newPath).content()).isEqualTo("{ \"value\": true }");
    }

    // TODO(trustin): Add the test case for generating a revert commit from diff().

    /**
     * Tests if the results are in the correct range for given parameters.
     */
    @Test
    public void testHistory_correctRangeOfResult() throws Exception {

        final String jsonPath = jsonPatches[0].path();
        final String textPath = textPatches[0].path();

        final Revision firstJsonCommit = repo.normalizeNow(HEAD).forward(1);
        Revision lastJsonCommit = null;
        for (Change<JsonNode> c : jsonPatches) {
            lastJsonCommit = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, c).join();
        }

        final Revision firstTextCommit = lastJsonCommit.forward(1);
        Revision lastTextCommit = null;
        for (Change<String> c : textPatches) {
            lastTextCommit = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, c).join();
        }

        final Revision firstJsonCommitRel = new Revision(-(jsonPatches.length + textPatches.length));
        final Revision lastJsonCommitRel = new Revision(-(textPatches.length + 1));
        final Revision firstTextCommitRel = new Revision(-textPatches.length);
        final Revision lastTextCommitRel = HEAD;

        assertThat(repo.normalizeNow(firstJsonCommitRel)).isEqualTo(firstJsonCommit);
        assertThat(repo.normalizeNow(lastJsonCommitRel)).isEqualTo(lastJsonCommit);
        assertThat(repo.normalizeNow(firstTextCommitRel)).isEqualTo(firstTextCommit);
        assertThat(repo.normalizeNow(lastTextCommitRel)).isEqualTo(lastTextCommit);

        List<Commit> commits;
        List<Commit> commitsRel;

        commits = repo.history(firstJsonCommit, lastJsonCommit, jsonPath).join();
        commitsRel = repo.history(firstJsonCommitRel, lastJsonCommitRel, jsonPath).join();
        assertThat(commits).hasSize(jsonPatches.length)
                           .isEqualTo(commitsRel);

        commits = repo.history(firstJsonCommit, lastTextCommit, jsonPath).join();
        commitsRel = repo.history(firstJsonCommitRel, lastTextCommitRel, jsonPath).join();
        assertThat(commits).hasSize(jsonPatches.length)
                           .isEqualTo(commitsRel);

        commits = repo.history(firstTextCommit, lastTextCommit, jsonPath).join();
        commitsRel = repo.history(firstTextCommitRel, lastTextCommitRel, jsonPath).join();
        assertThat(commits).isEmpty();
        assertThat(commitsRel).isEmpty();

        commits = repo.history(new Revision(1), lastTextCommit, jsonPath).join();
        commitsRel = repo.history(new Revision(1), lastTextCommitRel, jsonPath).join();
        assertThat(commits).hasSize(jsonPatches.length) // # of JSON patches
                           .isEqualTo(commitsRel);

        commits = repo.history(firstTextCommit, lastTextCommit, textPath).join();
        commitsRel = repo.history(firstTextCommitRel, lastTextCommitRel, textPath).join();
        assertThat(commits).hasSize(textPatches.length)
                           .isEqualTo(commitsRel);

        commits = repo.history(firstJsonCommit, lastTextCommit, textPath).join();
        commitsRel = repo.history(firstJsonCommitRel, lastTextCommitRel, textPath).join();
        assertThat(commits).hasSize(textPatches.length)
                           .isEqualTo(commitsRel);

        commits = repo.history(firstJsonCommit, lastJsonCommit, textPath).join();
        commitsRel = repo.history(firstJsonCommitRel, lastJsonCommitRel, textPath).join();
        assertThat(commits).isEmpty();
        assertThat(commitsRel).isEmpty();

        commits = repo.history(new Revision(1), lastTextCommit, textPath).join();
        commitsRel = repo.history(new Revision(1), lastTextCommitRel, textPath).join();
        assertThat(commits).hasSize(textPatches.length) // # of text patches
                           .isEqualTo(commitsRel);
    }

    /**
     * Given a path, check if only the affected revisions are returned.
     */
    @Test
    public void testHistory_returnOnlyAffectedRevisions() throws Exception {
        final String jsonPath = jsonPatches[0].path();
        final String textPath = textPatches[0].path();

        Revision lastJsonCommit = null;
        Revision lastTextCommit = null;
        for (int i = 0; i < NUM_ITERATIONS; i++) {
            lastJsonCommit = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonPatches[i]).join();
            lastTextCommit = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, textPatches[i]).join();
        }

        List<Commit> jsonCommits = repo.history(HEAD, new Revision(1), jsonPath).join();
        // # of JSON commits
        assertThat(jsonCommits).hasSize(jsonPatches.length);

        for (Commit c : jsonCommits) {
            if (c.revision().major() > 1) {
                assertThat(c.revision()).isEqualTo(lastJsonCommit);
                lastJsonCommit = lastJsonCommit.backward(2);
            }
        }

        List<Commit> textCommits = repo.history(HEAD, new Revision(1), textPath).join();
        // # of text commits
        assertThat(textCommits).hasSize(textPatches.length);

        for (Commit c : textCommits) {
            if (c.revision().major() > 1) {
                assertThat(c.revision()).isEqualTo(lastTextCommit);
                lastTextCommit = lastTextCommit.backward(2);
            }
        }
    }

    @Test
    public void testHistory_parameterCheck() throws Exception {
        // Make sure that we added at least one non-initial commit.
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[0]).join();

        final Revision head = repo.normalizeNow(HEAD);

        List<Commit> commits;

        // Even though the range contains 1, if the path is specified and does not contain "/**",
        // it should not include the initial commit.
        commits = repo.history(HEAD, new Revision(1), "non_existing_path").join();
        assertThat(commits).hasSize(0);

        // Should include the initial empty commit if the range contain 1 and the path contains "/**".
        commits = repo.history(HEAD, HEAD, "/**").join();
        assertThat(commits).hasSize(1);

        // Should not include the initial empty commit if the range does not contain 1.
        commits = repo.history(HEAD, HEAD, "non_existing_path").join();
        assertThat(commits).isEmpty();

        assertThatThrownBy(() -> repo.history(head.forward(1), head.forward(2), "non_existing_path").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageException.class);

        assertThatThrownBy(() -> repo.history(head.forward(1), head.backward(1), "non_existing_path").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageException.class);

        assertThatThrownBy(() -> repo.history(null, HEAD, "non_existing_path").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> repo.history(HEAD, null, "non_existing_path").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NullPointerException.class);
    }

    @Test
    public void testFind_negativeRevisionQuery() throws Exception {

        int numIterations = 10;

        String jsonNodePath = String.format("/node_%s.json", testName.getMethodName());
        String textNodePath = String.format("/text_%s.txt", testName.getMethodName());

        String jsonStringPattern = "{\"key\":\"%d\"}";
        String textStringPattern = "a\n%d\nc";

        Revision revision = null;
        String oldJsonString = null;
        String oldTextString = null;

        for (int i = 0; i < numIterations; i++) {
            if (i != 0) {
                oldJsonString = String.format(jsonStringPattern, i - 1);
                oldTextString = String.format(textStringPattern, i - 1);
            }
            String newJsonString = String.format(jsonStringPattern, i);
            String newTextString = String.format(textStringPattern, i);

            Change<JsonNode> jsonChange = Change.ofJsonPatch(jsonNodePath, oldJsonString, newJsonString);
            Change<String> textChange = Change.ofTextPatch(textNodePath, oldTextString, newTextString);

            revision = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                                   Arrays.asList(jsonChange, textChange)).join();
        }

        if (revision == null) {
            fail();
        }

        for (int i = 0 - numIterations; i < 0; i++) {
            Map<String, Entry<?>> entryMap = repo.find(new Revision(i), Repository.ALL_PATH).join();
            assertThatJson(entryMap.get(jsonNodePath).content()).isEqualTo(
                    String.format(jsonStringPattern, numIterations + i));
            assertThat(entryMap.get(textNodePath).content()).isEqualTo(
                    String.format(textStringPattern + '\n', numIterations + i));
        }
    }

    @Test
    public void testFindNone() {
        assertThat(repo.find(HEAD, "/non-existent").join()).isEmpty();
        assertThat(repo.find(HEAD, "non-existent").join()).isEmpty();
    }

    /**
     * when the target path or revision is not valid, return an empty map.
     */
    @Test
    public void testFind_invalidParameter() throws Exception {
        String jsonNodePath = "/node.json";
        String jsonString = "{\"key\":\"value\"}";
        Change<JsonNode> jsonChange = Change.ofJsonUpsert(jsonNodePath, jsonString);
        Revision revision = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonChange).join();

        assertThatThrownBy(() -> repo.find(new Revision(revision.major() + 1), jsonNodePath).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RevisionNotFoundException.class);
    }

    @Test
    public void testFind_directory() throws Exception {
        // Will create the following directory structure:
        //
        // prefix -+- a
        //         +- b -+- ba
        //               +- bb
        //

        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                    Change.ofTextUpsert(prefix + "a/file", ""),
                    Change.ofTextUpsert(prefix + "b/ba/file", ""),
                    Change.ofTextUpsert(prefix + "b/bb/file", "")).join();

        final Entry<Void> a = Entry.ofDirectory(prefix + 'a');
        final Entry<Void> b = Entry.ofDirectory(prefix + 'b');
        final Entry<Void> b_ba = Entry.ofDirectory(prefix + "b/ba");
        final Entry<Void> b_bb = Entry.ofDirectory(prefix + "b/bb");

        // Recursive search
        final Collection<Entry<?>> entries = repo.find(HEAD, allPattern).join().entrySet().stream()
                                                 .filter(e -> !e.getKey().endsWith("/file"))
                                                 .map(Map.Entry::getValue).collect(Collectors.toList());
        assertThat(entries).containsExactly(a, b, b_ba, b_bb);

        // Non-recursive search
        assertThat(repo.find(HEAD, prefix + '*').join().values()).containsExactly(a, b);

        // Single get
        assertThat(repo.find(HEAD, prefix + 'b').join().values()).containsExactly(b);
    }

    @Test
    public void testJsonPathQuery() throws Exception {
        repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY,
                    Change.ofJsonUpsert("/instances.json",
                                        '[' +
                                        "  {" +
                                        "    \"name\": \"a\"," +
                                        "    \"groups\": [{" +
                                        "      \"type\": \"phase\"," +
                                        "      \"name\": \"alpha\"" +
                                        "    }, {" +
                                        "      \"type\": \"not_phase\"," +
                                        "      \"name\": \"beta\"" +
                                        "    }]" +
                                        "  }, {" +
                                        "    \"name\": \"b\"," +
                                        "    \"groups\": [{" +
                                        "      \"type\": \"phase\"," +
                                        "      \"name\": \"beta\"" +
                                        "    }, {" +
                                        "      \"type\": \"not_phase\"," +
                                        "      \"name\": \"alpha\"" +
                                        "    }]" +
                                        "  }" +
                                        ']')).join();

        final QueryResult<JsonNode> res1 = repo.get(HEAD, Query.ofJsonPath(
                "/instances.json", "$[?(@.name == 'b')]")).join();

        assertThatJson(res1.content()).isEqualTo("[{" +
                                                 "  \"name\": \"b\"," +
                                                 "  \"groups\": [{" +
                                                 "    \"type\": \"phase\"," +
                                                 "    \"name\": \"beta\"" +
                                                 "  }, {" +
                                                 "    \"type\": \"not_phase\"," +
                                                 "    \"name\": \"alpha\"" +
                                                 "  }]" +
                                                 "}]");

        final QueryResult<JsonNode> res2 = repo.get(HEAD, Query.ofJsonPath(
                "/instances.json", "$..groups[?(@.type == 'not_phase' && @.name == 'alpha')]")).join();

        assertThatJson(res2.content()).isEqualTo("[{" +
                                                 "  \"type\": \"not_phase\"," +
                                                 "  \"name\": \"alpha\"" +
                                                 "}]");

        final QueryResult<JsonNode> res3 = repo.get(HEAD, Query.ofJsonPath(
                "/instances.json", "$[?(@.groups[?(@.type == 'phase' && @.name == 'alpha')] empty false)]"))
                                               .join();

        assertThatJson(res3.content()).isEqualTo("[{" +
                                                 "  \"name\": \"a\"," +
                                                 "  \"groups\": [{" +
                                                 "    \"type\": \"phase\"," +
                                                 "    \"name\": \"alpha\"" +
                                                 "  }, {" +
                                                 "    \"type\": \"not_phase\"," +
                                                 "    \"name\": \"beta\"" +
                                                 "  }]" +
                                                 "}]");
    }

    @Test
    public void testWatch() throws Exception {
        Revision rev1 = repo.normalizeNow(HEAD);
        Revision rev2 = rev1.forward(1);

        final CompletableFuture<Revision> f = repo.watch(rev1, Repository.ALL_PATH);
        assertThat(f).isNotDone();

        repo.commit(rev1, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[0]);
        assertThat(f.get(3, TimeUnit.SECONDS)).isEqualTo(rev2);

        assertThat(repo.normalizeNow(HEAD)).isEqualTo(rev2);
    }

    @Test
    public void testWatchWithPathPattern() throws Exception {
        final Revision rev1 = repo.normalizeNow(HEAD);
        final Revision rev2 = rev1.forward(1);
        final Revision rev3 = rev2.forward(1);

        final CompletableFuture<Revision> f = repo.watch(rev1, jsonPaths[1]);

        // Should not notify when the path pattern does not match.
        repo.commit(rev1, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[0]).join();
        assertThat(repo.normalizeNow(HEAD)).isEqualTo(rev2);
        assertThatThrownBy(() -> f.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        // Should notify when the path pattern matches.
        repo.commit(rev2, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[1]).join();
        assertThat(repo.normalizeNow(HEAD)).isEqualTo(rev3);
        assertThat(f.get(3, TimeUnit.SECONDS)).isEqualTo(rev3);
    }

    @Test
    public void testWatchWithOldRevision() throws Exception {
        final Revision lastKnownRev = repo.normalizeNow(HEAD);
        repo.commit(lastKnownRev, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts).join();
        final Revision latestRev = repo.normalizeNow(HEAD);
        assertThat(latestRev).isNotEqualTo(lastKnownRev);

        // Should notify very soon.
        final CompletableFuture<Revision> f = repo.watch(lastKnownRev, Repository.ALL_PATH);
        assertThat(f.get(3, TimeUnit.SECONDS)).isEqualTo(latestRev);
    }

    @Test
    public void testWatchWithOldRevisionAndPathPattern() throws Exception {
        final Revision lastKnownRev = repo.normalizeNow(HEAD);
        repo.commit(lastKnownRev, 0L, Author.UNKNOWN, SUMMARY, jsonPatches).join();
        final Revision latestRev = repo.normalizeNow(HEAD);
        assertThat(latestRev).isNotEqualTo(lastKnownRev);

        // Should not return a successful future because the changes in the prior commit did not affect
        // the files that patch the path pattern.
        final CompletableFuture<Revision> f = repo.watch(lastKnownRev, jsonPaths[1]);
        assertThatThrownBy(() -> f.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        final Revision newLatestRev = repo.commit(latestRev, 0L, Author.UNKNOWN, SUMMARY,
                                                  jsonUpserts[1]).join();
        assertThat(repo.normalizeNow(HEAD)).isEqualTo(newLatestRev);
        assertThat(f.get(3, TimeUnit.SECONDS)).isEqualTo(newLatestRev);
    }

    @Test
    public void testWatchWithQuery() throws Exception {
        final Revision rev1 = repo.commit(
                HEAD, 0L, Author.UNKNOWN, SUMMARY,
                Change.ofJsonUpsert(jsonPaths[0], "{ \"hello\": \"mars\" }")).join();

        CompletableFuture<QueryResult<JsonNode>> f =
                repo.watch(rev1, Query.ofJsonPath(jsonPaths[0], "$.hello"));

        // Make sure the initial change does not trigger a notification.
        assertThatThrownBy(() -> f.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        // Make sure the change that does not affect the query result does not trigger a notification.
        repo.commit(
                HEAD, 0L, Author.UNKNOWN, SUMMARY,
                Change.ofJsonUpsert(jsonPaths[0], "{ \"hello\": \"mars\", \"goodbye\": \"venus\" }"));

        assertThatThrownBy(() -> f.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        // Here comes the interesting change; make sure notification is triggered.
        final Revision rev3 = repo.commit(
                HEAD, 0L, Author.UNKNOWN, SUMMARY,
                Change.ofJsonUpsert(jsonPaths[0], "{ \"hello\": \"jupiter\", \"goodbye\": \"mars\" }")).join();

        final QueryResult<JsonNode> res = f.get(3, TimeUnit.SECONDS);
        assertThat(res.revision()).isEqualTo(rev3);
        assertThat(res.type()).isEqualTo(EntryType.JSON);
        assertThat(res.content()).isEqualTo(TextNode.valueOf("jupiter"));
    }

    @Test(timeout = 10000)
    public void testWatchWithIdentityQuery() throws Exception {
        final Revision rev1 = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, textUpserts[0]).join();

        CompletableFuture<QueryResult<Object>> f =
                repo.watch(rev1, Query.identity(textPaths[0]));

        final Revision rev2 = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, textPatches[1]).join();
        final QueryResult<Object> res = f.get(3, TimeUnit.SECONDS);
        assertThat(res.revision()).isEqualTo(rev2);
        assertThat(res.type()).isEqualTo(EntryType.TEXT);
        // Text must be sanitized so that the last line ends with \n.
        assertThat(res.content()).isEqualTo(textUpserts[1].content() + '\n');
    }

    @Test
    public void testWatchRemoval() throws Exception {
        final Revision rev1 = repo.commit(HEAD, 0L, Author.UNKNOWN, SUMMARY, jsonUpserts[0]).join();

        CompletableFuture<QueryResult<JsonNode>> f =
                repo.watch(rev1, Query.ofJsonPath(jsonPaths[0], "$"));

        // Remove the file being watched.
        final Revision rev2 = repo.commit(
                HEAD, 0L, Author.UNKNOWN, SUMMARY, Change.ofRemoval(jsonPaths[0])).join();
        final QueryResult<JsonNode> res = f.get(3, TimeUnit.SECONDS);
        assertThat(res.revision()).isEqualTo(rev2);
        assertThat(res.type()).isNull();
        assertThat(res.content()).isNull();
        assertThat(res.contentAsText()).isNull();
    }

    @Test
    public void testWatchWithQueryCancellation() throws Exception {
        final AtomicInteger numSubtasks = new AtomicInteger();
        final CountDownLatch subtaskCancelled = new CountDownLatch(1);

        watchConsumer = f -> {
            numSubtasks.getAndIncrement();
            f.exceptionally(cause -> {
                if (cause instanceof CancellationException) {
                    subtaskCancelled.countDown();
                }
                return null;
            });
        };

        // Start a watch that never finishes.
        final CompletableFuture<QueryResult<JsonNode>> f =
                repo.watch(HEAD, Query.ofJsonPath(jsonPaths[0], "$"));
        assertThatThrownBy(() -> f.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        // A watch with a query should start a subtask.
        assertThat(numSubtasks.get()).isEqualTo(1);
        assertThat(subtaskCancelled.getCount()).isEqualTo(1L);

        // Cancel the watch.
        assertThat(f.cancel(true)).isTrue();

        // The subtask should be cancelled as well.
        assertThatThrownBy(() -> f.get(3, TimeUnit.SECONDS))
                .isInstanceOf(CancellationException.class);
        assertThat(subtaskCancelled.await(3, TimeUnit.SECONDS)).isTrue();

        // No new subtask should be spawned.
        assertThat(numSubtasks.get()).isEqualTo(1);
    }

    @Test
    public void testDoUpdateRef() throws Exception {
        final ObjectId commitId = mock(ObjectId.class);

        // A commit on the mainlane
        testDoUpdateRef(Constants.R_TAGS + '1', commitId, false);
        testDoUpdateRef(Constants.R_HEADS + Constants.MASTER, commitId, false);
    }

    private static void testDoUpdateRef(String ref, ObjectId commitId, boolean tagExists) throws Exception {
        final org.eclipse.jgit.lib.Repository jGitRepo = mock(org.eclipse.jgit.lib.Repository.class);
        final RevWalk revWalk = mock(RevWalk.class);
        final RefUpdate refUpdate = mock(RefUpdate.class);

        when(jGitRepo.exactRef(ref)).thenReturn(tagExists ? mock(Ref.class) : null);
        when(jGitRepo.updateRef(ref)).thenReturn(refUpdate);

        when(refUpdate.update(revWalk)).thenReturn(RefUpdate.Result.NEW);
        GitRepository.doRefUpdate(jGitRepo, revWalk, ref, commitId);

        when(refUpdate.update(revWalk)).thenReturn(RefUpdate.Result.FAST_FORWARD);
        GitRepository.doRefUpdate(jGitRepo, revWalk, ref, commitId);

        when(refUpdate.update(revWalk)).thenReturn(RefUpdate.Result.LOCK_FAILURE);
        assertThatThrownBy(() -> GitRepository.doRefUpdate(jGitRepo, revWalk, ref, commitId))
                .isInstanceOf(StorageException.class);
    }

    @Test
    public void testDoUpdateRefOnExistingTag() throws Exception {
        final ObjectId commitId = mock(ObjectId.class);

        assertThatThrownBy(() -> testDoUpdateRef(Constants.R_TAGS + "01/1.0", commitId, true))
                .isInstanceOf(StorageException.class);
    }
}
