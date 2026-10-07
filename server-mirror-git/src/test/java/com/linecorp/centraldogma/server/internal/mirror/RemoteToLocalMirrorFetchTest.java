/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server.internal.mirror;

import static com.linecorp.centraldogma.server.internal.mirror.MirroringTestUtils.EVERY_MINUTE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_COMMIT_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_GPGSIGN;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.AbstractPushCommand;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommandExecutorStatusManager;
import com.linecorp.centraldogma.server.command.ExecutionContext;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.mirror.MirrorContext;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorStatus;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;
import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;
import com.linecorp.centraldogma.testing.internal.TestUtil;

class RemoteToLocalMirrorFetchTest {

    private static final int MAX_NUM_FILES = 32;
    private static final long MAX_NUM_BYTES = 1048576; // 1 MiB

    private static final String REPO_FOO = "foo";

    @RegisterExtension
    static final ProjectManagerExtension pmExtension = new ProjectManagerExtension();

    @RegisterExtension
    final TemporaryFolderExtension tempDir = new TemporaryFolderExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private final List<String> transportCommands = new ArrayList<>();

    private File workDir;
    private File gitWorkTree;
    private Git remoteGit;
    private String gitUri;
    private String projectName;
    private Repository localRepo;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        projectName = TestUtil.normalizedDisplayName(testInfo);
        workDir = new File(tempDir.getRoot().toFile(), "work-dir");
        gitWorkTree = new File(tempDir.getRoot().toFile(), "remote").getAbsoluteFile();
        final org.eclipse.jgit.lib.Repository gitRepo =
                new FileRepositoryBuilder().setWorkTree(gitWorkTree).build();
        gitRepo.create();
        final StoredConfig config = gitRepo.getConfig();
        config.setBoolean(CONFIG_COMMIT_SECTION, null, CONFIG_KEY_GPGSIGN, false);
        config.save();
        remoteGit = Git.wrap(gitRepo);
        remoteGit.commit().setMessage("Initial commit").call();

        final String gitDirPath = gitWorkTree.getPath().replace(File.separatorChar, '/') + "/.git";
        gitUri = "git+file://" + (gitDirPath.startsWith("/") ? "" : "/") + gitDirPath;

        final Project project = pmExtension.projectManager().create(projectName, Author.SYSTEM);
        localRepo = project.repos().create(REPO_FOO, Author.SYSTEM);
    }

    @Test
    void upToDateMirrorDoesNotFetchObjects() throws Exception {
        final DefaultGitMirror mirror = newMirror("/");
        commitToRemote("first.txt", "1");
        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        assertThat(transportCommands).containsExactly("LsRemoteCommand", "FetchCommand");

        // The remote head has not moved, so the mirror decides to skip with the advertised refs alone.
        transportCommands.clear();
        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.UP_TO_DATE);
        assertThat(transportCommands).containsExactly("LsRemoteCommand");

        // A new remote commit is fetched again.
        commitToRemote("second.txt", "2");
        transportCommands.clear();
        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        assertThat(transportCommands).containsExactly("LsRemoteCommand", "FetchCommand");
    }

    @Test
    void comparingMirrorFetchesObjects() throws Exception {
        final DefaultGitMirror mirror = newMirror("/sub");
        commitToRemote("first.txt", "1");
        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);

        // A local commit outside the mirrored path moves the local head while the remote head stays,
        // which makes the mirror compare the contents instead of skipping.
        pmExtension.executor().execute(
                Command.push(Author.SYSTEM, projectName, REPO_FOO, Revision.HEAD, "Local change", "",
                             Markup.PLAINTEXT, Change.ofTextUpsert("/outside.txt", "x"))).join();

        transportCommands.clear();
        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.UP_TO_DATE);
        assertThat(transportCommands).containsExactly("LsRemoteCommand", "FetchCommand");
    }

    @Test
    void replayedCommitsUseTheRevisionTheyReadAsTheirBase() throws Exception {
        final DefaultGitMirror mirror = newMirror("/", true);
        commitToRemote("initial.txt", "0");
        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        final Revision baseline = localRepo.normalizeNow(Revision.HEAD);

        commitToRemote("first.txt", "1");
        commitToRemote("second.txt", "2");
        final List<Revision> baseRevisions = new ArrayList<>();
        final CommandExecutor executor = new CapturingCommandExecutor(pmExtension.executor(), baseRevisions);

        assertThat(mirrorRemoteToLocal(mirror, executor).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        assertThat(baseRevisions).containsExactly(baseline, baseline.forward(1));
    }

    @Test
    void replayedCommitsUseLatestRevisionAfterInterveningLocalCommit() throws Exception {
        final DefaultGitMirror mirror = newMirror("/", true);
        commitToRemote("initial.txt", "0");
        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        final Revision baseline = localRepo.normalizeNow(Revision.HEAD);

        commitToRemote("first.txt", "1");
        commitToRemote("second.txt", "2");
        final List<Revision> baseRevisions = new ArrayList<>();
        final CommandExecutor executor =
                new InterveningCommitExecutor(
                        pmExtension.executor(), baseRevisions,
                        Change.ofTextUpsert("/outside.txt", "local"));

        assertThat(mirrorRemoteToLocal(mirror, executor).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        assertThat(baseRevisions).containsExactly(baseline, baseline.forward(2));
        assertThat(localRepo.normalizeNow(Revision.HEAD)).isEqualTo(baseline.forward(3));
        assertThat(localRepo.find(Revision.HEAD, "/outside.txt").join()).isEmpty();
    }

    @Test
    void replayRestoresFileDeletedByInterveningLocalCommit() throws Exception {
        final DefaultGitMirror mirror = newMirror("/", true);
        commitToRemote("initial.txt", "0");
        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        final Revision baseline = localRepo.normalizeNow(Revision.HEAD);

        commitToRemote("first.txt", "1");
        commitToRemote("second.txt", "2");
        final List<Revision> baseRevisions = new ArrayList<>();
        final CommandExecutor executor =
                new InterveningCommitExecutor(
                        pmExtension.executor(), baseRevisions, Change.ofRemoval("/initial.txt"));

        assertThat(mirrorRemoteToLocal(mirror, executor).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        assertThat(baseRevisions).containsExactly(baseline, baseline.forward(2));
        assertThat(localContent("/initial.txt")).isEqualTo("0");
    }

    @Test
    void replayPreservesInterveningDeletionOutsideMirroredPath() throws Exception {
        pmExtension.executor().execute(
                Command.push(Author.SYSTEM, projectName, REPO_FOO, Revision.HEAD,
                             "Add outside file", "", Markup.PLAINTEXT,
                             Change.ofTextUpsert("/outside.txt", "local"))).join();
        final DefaultGitMirror mirror = newMirror("/target", true);
        commitToRemote("initial.txt", "0");
        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);

        commitToRemote("first.txt", "1");
        commitToRemote("second.txt", "2");
        final CommandExecutor executor =
                new InterveningCommitExecutor(
                        pmExtension.executor(), new ArrayList<>(), Change.ofRemoval("/outside.txt"));

        assertThat(mirrorRemoteToLocal(mirror, executor).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        assertThat(localRepo.find(Revision.HEAD, "/outside.txt").join()).isEmpty();
        assertThat(localContent("/target/initial.txt")).isEqualTo("0");
    }

    @Test
    void replayPreservesInterveningAdditionOutsideMirroredPath() throws Exception {
        final DefaultGitMirror mirror = newMirror("/target", true);
        commitToRemote("initial.txt", "0");
        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);

        commitToRemote("first.txt", "1");
        commitToRemote("second.txt", "2");
        final CommandExecutor executor =
                new InterveningCommitExecutor(
                        pmExtension.executor(), new ArrayList<>(),
                        Change.ofTextUpsert("/outside.txt", "local"));

        assertThat(mirrorRemoteToLocal(mirror, executor).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        assertThat(localContent("/outside.txt")).isEqualTo("local");
        assertThat(localContent("/target/initial.txt")).isEqualTo("0");
    }

    @Test
    void replaysUpstreamFileDeletion() throws Exception {
        final DefaultGitMirror mirror = newMirror("/", true);
        commitToRemote("deleted.txt", "remove me");
        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        final Revision baseline = localRepo.normalizeNow(Revision.HEAD);

        removeFromRemote("deleted.txt");
        commitToRemote("remaining.txt", "keep me");

        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        assertThat(localRepo.normalizeNow(Revision.HEAD)).isEqualTo(baseline.forward(2));
        assertThat(localRepo.find(Revision.HEAD, "/deleted.txt").join()).isEmpty();
        assertThat(localContent("/remaining.txt")).isEqualTo("keep me");
    }

    @Test
    void upstreamDeletionDuringReplayIsPickedUpByNextRun() throws Exception {
        final DefaultGitMirror mirror = newMirror("/", true);
        commitToRemote("deleted.txt", "remove me");
        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        final Revision baseline = localRepo.normalizeNow(Revision.HEAD);

        commitToRemote("first.txt", "1");
        commitToRemote("second.txt", "2");
        final CommandExecutor executor =
                new InterveningRemoteChangeExecutor(
                        pmExtension.executor(), new ArrayList<>(), () -> {
                            removeFromRemote("deleted.txt");
                            return null;
                        });

        assertThat(mirrorRemoteToLocal(mirror, executor).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        assertThat(localRepo.normalizeNow(Revision.HEAD)).isEqualTo(baseline.forward(2));
        assertThat(localContent("/deleted.txt")).isEqualTo("remove me");

        assertThat(mirrorRemoteToLocal(mirror).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        assertThat(localRepo.normalizeNow(Revision.HEAD)).isEqualTo(baseline.forward(3));
        assertThat(localRepo.find(Revision.HEAD, "/deleted.txt").join()).isEmpty();
    }

    @Test
    void snapshotUsesTheRevisionItReadAsItsBase() throws Exception {
        final DefaultGitMirror mirror = newMirror("/");
        commitToRemote("initial.txt", "0");
        final Revision baseline = localRepo.normalizeNow(Revision.HEAD);
        final List<Revision> baseRevisions = new ArrayList<>();
        final CommandExecutor executor = new CapturingCommandExecutor(pmExtension.executor(), baseRevisions);

        assertThat(mirrorRemoteToLocal(mirror, executor).mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        assertThat(baseRevisions).containsExactly(baseline);
    }

    private DefaultGitMirror newMirror(String localPath) {
        return newMirror(localPath, false);
    }

    private DefaultGitMirror newMirror(String localPath, boolean preserveRemoteCommitHistory) {
        return (DefaultGitMirror) new GitMirrorProvider().newMirror(
                new MirrorContext("mirror-id", true, EVERY_MINUTE, MirrorDirection.REMOTE_TO_LOCAL,
                                  Credential.NONE, localRepo, localPath, URI.create(gitUri), null, null,
                                  preserveRemoteCommitHistory, false, ImmutableMap.of()));
    }

    private MirrorResult mirrorRemoteToLocal(DefaultGitMirror mirror) throws Exception {
        return mirrorRemoteToLocal(mirror, pmExtension.executor());
    }

    private MirrorResult mirrorRemoteToLocal(DefaultGitMirror mirror, CommandExecutor executor)
            throws Exception {
        final Consumer<TransportCommand<?, ?>> recorder =
                command -> transportCommands.add(command.getClass().getSimpleName());
        final URIish remoteUri = new URIish(gitUri.substring("git+".length()));
        try (GitWithAuth git = mirror.openGit(workDir, remoteUri, recorder)) {
            return mirror.mirrorRemoteToLocal(git, executor, MAX_NUM_FILES, MAX_NUM_BYTES,
                                              Instant.now());
        }
    }

    private void commitToRemote(String path, String content) throws Exception {
        final File file = Paths.get(gitWorkTree.getAbsolutePath(), path.split("/")).toFile();
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(UTF_8));
        remoteGit.add().addFilepattern(path).call();
        remoteGit.commit().setMessage("Add " + path).call();
    }

    private void removeFromRemote(String path) throws Exception {
        remoteGit.rm().addFilepattern(path).call();
        remoteGit.commit().setMessage("Remove " + path).call();
    }

    private String localContent(String path) {
        return localRepo.find(Revision.HEAD, path).join().get(path).contentAsText().trim();
    }

    private static class CapturingCommandExecutor implements CommandExecutor {

        private final CommandExecutor delegate;
        private final List<Revision> baseRevisions;

        private CapturingCommandExecutor(CommandExecutor delegate, List<Revision> baseRevisions) {
            this.delegate = delegate;
            this.baseRevisions = baseRevisions;
        }

        @Override
        public <T> CompletableFuture<T> execute(ExecutionContext ctx, Command<T> command) {
            if (command instanceof AbstractPushCommand) {
                baseRevisions.add(((AbstractPushCommand<?>) command).baseRevision());
            }
            return delegate.execute(ctx, command);
        }

        @Override
        public int replicaId() {
            return delegate.replicaId();
        }

        @Override
        public boolean isStarted() {
            return delegate.isStarted();
        }

        @Override
        public CompletableFuture<Void> start() {
            return delegate.start();
        }

        @Override
        public CompletableFuture<Void> stop() {
            return delegate.stop();
        }

        @Override
        public boolean isWritable() {
            return delegate.isWritable();
        }

        @Override
        public void setWritable(boolean writable) {
            delegate.setWritable(writable);
        }

        @Override
        public CommandExecutorStatusManager statusManager() {
            return delegate.statusManager();
        }
    }

    private final class InterveningCommitExecutor extends CapturingCommandExecutor {

        private final Change<?> change;
        private boolean insertedLocalCommit;

        private InterveningCommitExecutor(CommandExecutor delegate, List<Revision> baseRevisions,
                                          Change<?> change) {
            super(delegate, baseRevisions);
            this.change = change;
        }

        @Override
        public <T> CompletableFuture<T> execute(ExecutionContext ctx, Command<T> command) {
            final CompletableFuture<T> result = super.execute(ctx, command);
            if (insertedLocalCommit || !(command instanceof AbstractPushCommand)) {
                return result;
            }
            insertedLocalCommit = true;
            return result.thenCompose(value ->
                    pmExtension.executor().execute(
                            Command.push(Author.SYSTEM, projectName, REPO_FOO, Revision.HEAD,
                                         "Local change", "", Markup.PLAINTEXT,
                                         change))
                               .thenApply(unused -> value));
        }
    }

    private static final class InterveningRemoteChangeExecutor extends CapturingCommandExecutor {

        private final Callable<Void> change;
        private boolean changedRemote;

        private InterveningRemoteChangeExecutor(CommandExecutor delegate, List<Revision> baseRevisions,
                                                Callable<Void> change) {
            super(delegate, baseRevisions);
            this.change = change;
        }

        @Override
        public <T> CompletableFuture<T> execute(ExecutionContext ctx, Command<T> command) {
            final CompletableFuture<T> result = super.execute(ctx, command);
            if (changedRemote || !(command instanceof AbstractPushCommand)) {
                return result;
            }
            changedRemote = true;
            return result.thenApply(value -> {
                try {
                    change.call();
                    return value;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });
        }
    }
}
