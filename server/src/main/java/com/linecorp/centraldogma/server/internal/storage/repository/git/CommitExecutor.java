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

import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.R_HEADS_MASTER;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.doRefUpdate;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.newRevWalk;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.toTree;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.storage.StorageException;

final class CommitExecutor {

    final GitRepository gitRepository;
    private final long commitTimeMillis;
    private final Author author;
    private final String summary;
    private final String detail;
    private final Markup markup;
    private final EmptyCommitPolicy emptyCommitPolicy;

    CommitExecutor(GitRepository gitRepository, long commitTimeMillis, Author author,
                   String summary, String detail, Markup markup, EmptyCommitPolicy emptyCommitPolicy) {
        this.gitRepository = gitRepository;
        this.commitTimeMillis = commitTimeMillis;
        this.author = author;
        this.summary = summary;
        this.detail = detail;
        this.markup = markup;
        this.emptyCommitPolicy = emptyCommitPolicy;
    }

    Author author() {
        return author;
    }

    String summary() {
        return summary;
    }

    void executeInitialCommit() {
        commit(null, Revision.INIT, ImmutableList.of());
    }

    CommitResult execute(Revision baseRevision,
                         Function<Revision, Iterable<Change<?>>> applyingChangesProvider) {
        final RevisionAndEntries res;
        final Iterable<Change<?>> applyingChanges;
        gitRepository.writeLock();
        try {
            final Revision normBaseRevision = gitRepository.normalizeNow(baseRevision);
            final Revision headRevision = gitRepository.cachedHeadRevision();
            if (headRevision.major() != normBaseRevision.major()) {
                throw new ChangeConflictException(
                        "invalid baseRevision: " + baseRevision + " (expected: " + headRevision +
                        " or equivalent)");
            }

            applyingChanges = applyingChangesProvider.apply(normBaseRevision);
            res = commit(headRevision, headRevision.forward(1), applyingChanges);

            gitRepository.setHeadRevision(res.revision);
        } finally {
            gitRepository.writeUnLock();
        }

        // Note that the notification is made while no lock is held to avoid the risk of a dead lock.
        gitRepository.notifyWatchers(res.revision, res.diffEntries);
        return CommitResult.of(res.revision, applyingChanges);
    }

    RevisionAndEntries commit(@Nullable Revision headRevision, Revision nextRevision,
                              Iterable<Change<?>> changes) {
        requireNonNull(nextRevision, "nextRevision");
        requireNonNull(changes, "changes");

        assert (headRevision == null && Iterables.isEmpty(changes)) ||
               (headRevision != null && headRevision.major() > 0);
        assert nextRevision.major() > 0;

        final Repository jGitRepository = gitRepository.jGitRepository();
        try (ObjectInserter inserter = jGitRepository.newObjectInserter();
             ObjectReader reader = jGitRepository.newObjectReader();
             RevWalk revWalk = newRevWalk(reader)) {
            final CommitIdDatabase commitIdDatabase = gitRepository.commitIdDatabase();

            // The staging area that keeps the entries of the new tree.
            // It starts with the entries of the tree at the headRevision (or with no entries if the
            // headRevision is the initial commit), and then this method will apply the requested changes
            // to build the new tree.
            final DirCache dirCache = DirCache.newInCore();
            final List<DiffEntry> diffEntries;

            if (headRevision != null) {
                final ObjectId prevTreeId = toTree(commitIdDatabase, revWalk, headRevision);
                // Apply the changes and retrieve the list of the affected files.
                final int numEdits = new DefaultChangesApplier(changes)
                        .apply(jGitRepository, headRevision, prevTreeId, dirCache);
                // Reject empty commit if necessary.
                boolean isEmpty = numEdits == 0;
                if (!isEmpty) {
                    // Even if there are edits, the resulting tree might be identical with the previous tree.
                    final CanonicalTreeParser p = new CanonicalTreeParser();
                    p.reset(reader, prevTreeId);
                    final DiffFormatter diffFormatter = new DiffFormatter(null);
                    diffFormatter.setRepository(jGitRepository);
                    diffEntries = diffFormatter.scan(p, new DirCacheIterator(dirCache));
                    isEmpty = diffEntries.isEmpty();
                } else {
                    diffEntries = ImmutableList.of();
                }
                if (isEmpty) {
                    switch (emptyCommitPolicy) {
                        case ALLOW:
                            break;
                        case DISALLOW:
                            throw new RedundantChangeException(
                                    headRevision,
                                    "changes did not change anything in " + gitRepository.parent().name() +
                                    '/' + gitRepository.name() + " at revision " + headRevision.major() +
                                    ": " + changes);
                        case IGNORE:
                            return new RevisionAndEntries(headRevision, diffEntries);
                    }
                }
            } else {
                // initial commit.
                diffEntries = ImmutableList.of();
            }

            // flush the current index to repository and get the result tree object id.
            final ObjectId nextTreeId = dirCache.writeTree(inserter);

            // build a commit object
            final PersonIdent personIdent = new PersonIdent(author.name(), author.email(),
                                                            commitTimeMillis / 1000L * 1000L, 0);

            final CommitBuilder commitBuilder = new CommitBuilder();

            commitBuilder.setAuthor(personIdent);
            commitBuilder.setCommitter(personIdent);
            commitBuilder.setTreeId(nextTreeId);
            commitBuilder.setEncoding(UTF_8);

            // Write summary, detail and revision to commit's message as JSON format.
            commitBuilder.setMessage(CommitUtil.toJsonString(summary, detail, markup, nextRevision));

            // if the head commit exists, use it as the parent commit.
            if (headRevision != null) {
                commitBuilder.setParentId(commitIdDatabase.get(headRevision));
            }

            final ObjectId nextCommitId = inserter.insert(commitBuilder);
            inserter.flush();

            // tagging the revision object, for history lookup purpose.
            commitIdDatabase.put(nextRevision, nextCommitId);
            doRefUpdate(jGitRepository, revWalk, R_HEADS_MASTER, nextCommitId);

            return new RevisionAndEntries(nextRevision, diffEntries);
        } catch (CentralDogmaException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to push at '" + gitRepository.parent().name() + '/' +
                                       gitRepository.name() + '\'', e);
        }
    }

    static final class RevisionAndEntries {
        final Revision revision;
        final List<DiffEntry> diffEntries;

        RevisionAndEntries(Revision revision, List<DiffEntry> diffEntries) {
            this.revision = revision;
            this.diffEntries = diffEntries;
        }
    }

    enum EmptyCommitPolicy {
        ALLOW,
        DISALLOW,
        IGNORE
    }
}
