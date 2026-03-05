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

import static com.linecorp.centraldogma.internal.Util.deleteFileTree;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.R_HEADS_MASTER;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.closeRepository;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.deleteCruft;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepository.newRevWalk;
import static java.util.Objects.requireNonNull;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_CORE_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_REPO_FORMAT_VERSION;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.JGitUtil;
import com.linecorp.centraldogma.server.internal.storage.DirectoryBasedStorageManager;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.RocksDbCommitIdDatabase;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.RocksDbRepository;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

public final class GitRepositoryManager extends DirectoryBasedStorageManager<Repository>
        implements RepositoryManager {

    private static final Logger logger = LoggerFactory.getLogger(GitRepositoryManager.class);

    private static final String ENCRYPTED_REPO_PLACEHOLDER_FILE = ".encryption-repo-placeholder";

    private final Project parent;
    private final Executor repositoryWorker;

    @Nullable
    private final RepositoryCache cache;

    public GitRepositoryManager(Project parent, File rootDir, Executor repositoryWorker,
                                Executor purgeWorker, @Nullable RepositoryCache cache,
                                EncryptionStorageManager encryptionStorageManager) {
        super(rootDir, Repository.class, purgeWorker, encryptionStorageManager);
        this.parent = requireNonNull(parent, "parent");
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");
        this.cache = cache;
        init();
    }

    @Override
    public Project parent() {
        return parent;
    }

    private String projectRepositoryName(String name) {
        return parent.name() + '/' + name;
    }

    @Override
    public void migrateToEncryptedRepository(String repositoryName) {
        logger.info("Starting to migrate the repository '{}' to an encrypted repository.",
                    projectRepositoryName(repositoryName));
        final long startTime = System.nanoTime();
        final Repository oldRepository = get(repositoryName);

        final GitRepository encryptedRepository;
        final EncryptionStorageManager encryptionStorageManager = encryptionStorageManager();
        try {
            encryptedRepository = createEncryptedRepository(parent, oldRepository.repoDir(),
                                                            oldRepository.author(),
                                                            oldRepository.creationTimeMillis(),
                                                            repositoryWorker, cache,
                                                            encryptionStorageManager);
        } catch (Throwable t) {
            throw new StorageException("failed to create the repository while migrating. " +
                                       "repositoryName: " + projectRepositoryName(repositoryName), t);
        }

        final Revision headRevision = oldRepository.normalizeNow(Revision.HEAD);
        Revision baseRevision = null;
        try {
            for (int i = 2; i <= headRevision.major(); i++) {
                baseRevision = new Revision(i - 1);
                final Revision revision = new Revision(i);
                final CompletableFuture<List<Commit>> historyFuture =
                        oldRepository.history(revision, revision, "/**");
                final CompletableFuture<Map<String, Change<?>>> changesFuture =
                        oldRepository.diff(baseRevision, revision, "/**");
                CompletableFuture.allOf(historyFuture, changesFuture).join();
                final List<Commit> commits = historyFuture.join();
                assert commits.size() == 1;
                final Commit commit = commits.get(0);
                encryptedRepository.commit(baseRevision, commit.when(), commit.author(), commit.summary(),
                                           changesFuture.join().values()).join();
            }
        } catch (Throwable t) {
            encryptedRepository.internalClose();
            encryptionStorageManager.deleteRepositoryData(parent.name(), repositoryName);
            throw new StorageException("failed to migrate the contents of the repository '" +
                                       projectRepositoryName(repositoryName) + "' to an encrypted repository." +
                                       " baseRevision: " + baseRevision, t);
        }

        // Simply create the placeholder file to indicate that this repository is encrypted.
        // TODO(minwoox): remove the old content of the repository using a plugin.
        try {
            Files.createFile(Paths.get(oldRepository.repoDir().getPath(), ENCRYPTED_REPO_PLACEHOLDER_FILE));
        } catch (IOException e) {
            encryptedRepository.internalClose();
            encryptionStorageManager.deleteRepositoryData(parent.name(), repositoryName);
            throw new StorageException("failed to create the encrypted repository placeholder file at: " +
                                       oldRepository.repoDir(), e);
        }

        if (!replaceChild(repositoryName, oldRepository, encryptedRepository)) {
            encryptedRepository.internalClose();
            encryptionStorageManager.deleteRepositoryData(parent.name(), repositoryName);
            try {
                Files.delete(Paths.get(oldRepository.repoDir().getPath(), ENCRYPTED_REPO_PLACEHOLDER_FILE));
            } catch (IOException e) {
                logger.warn("Failed to delete the encrypted repository placeholder file at: {}",
                            oldRepository.repoDir(), e);
            }
            throw new StorageException("failed to replace the old repository with the encrypted repository. " +
                                       "repositoryName: " + projectRepositoryName(repositoryName));
        }
        logger.info("Migrated the repository '{}' to an encrypted repository in {} seconds.",
                    projectRepositoryName(repositoryName),
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime));
        // We didn't add repository listeners to the repository so don't have to add the listener here.
        ((GitRepository) oldRepository).close(() -> new CentralDogmaException(
                projectRepositoryName(repositoryName) + " is migrated to an encrypted repository. Try again."));
    }

    @Override
    protected Repository openChild(File childDir) throws Exception {
        requireNonNull(childDir, "childDir");
        if (isEncryptedRepository(childDir)) {
            return openEncryptionRepository(
                    parent, childDir, repositoryWorker, cache, encryptionStorageManager());
        } else {
            return openFileRepository(parent, childDir, repositoryWorker, cache);
        }
    }

    public static boolean isEncryptedRepository(File dir) {
        return Files.exists(dir.toPath().resolve(ENCRYPTED_REPO_PLACEHOLDER_FILE));
    }

    @VisibleForTesting
    static Repository openEncryptionRepository(Project parent, File repoDir, Executor repositoryWorker,
                                               @Nullable RepositoryCache cache,
                                               EncryptionStorageManager encryptionStorageManager) {
        final EncryptionGitStorage encryptionGitStorage =
                new EncryptionGitStorage(parent.name(), repoDir.getName(), encryptionStorageManager);
        final RocksDbRepository rocksDbRepository = new RocksDbRepository(encryptionGitStorage);
        final Revision headRevision = uncachedHeadRevision(rocksDbRepository, parent, repoDir);
        final RocksDbCommitIdDatabase commitIdDatabase =
                new RocksDbCommitIdDatabase(encryptionGitStorage, headRevision);
        return new GitRepository(parent, repoDir, repositoryWorker, cache, rocksDbRepository,
                                 commitIdDatabase, headRevision);
    }

    @VisibleForTesting
    static GitRepository openFileRepository(Project parent, File repoDir, Executor repositoryWorker,
                                            @Nullable RepositoryCache cache) {
        final org.eclipse.jgit.lib.Repository jGitRepository;
        try {
            jGitRepository = new RepositoryBuilder().setGitDir(repoDir).setBare().build();
            if (!exist(repoDir)) {
                throw RepositoryNotFoundException.of(parent.name(), repoDir.getName());
            }

            // Retrieve the repository format.
            final int formatVersion = jGitRepository.getConfig().getInt(
                    CONFIG_CORE_SECTION, null, CONFIG_KEY_REPO_FORMAT_VERSION, 0);
            if (formatVersion != JGitUtil.REPO_FORMAT_VERSION) {
                throw new StorageException("unsupported repository format version: " + formatVersion +
                                           " (expected: " + JGitUtil.REPO_FORMAT_VERSION + ')');
            }

            // Update the default settings if necessary.
            JGitUtil.applyDefaultsAndSave(jGitRepository.getConfig());
        } catch (IOException e) {
            throw new StorageException("failed to open a repository at: " + repoDir, e);
        }

        CommitIdDatabase commitIdDatabase = null;
        boolean success = false;
        try {
            final Revision headRevision = uncachedHeadRevision(jGitRepository, parent, repoDir);
            commitIdDatabase = new DefaultCommitIdDatabase(jGitRepository);
            if (!headRevision.equals(commitIdDatabase.headRevision())) {
                commitIdDatabase.rebuild(jGitRepository);
                assert headRevision.equals(commitIdDatabase.headRevision());
            }
            final GitRepository gitRepository = new GitRepository(parent, repoDir, repositoryWorker, cache,
                                                                  jGitRepository,
                                                                  commitIdDatabase, headRevision);
            success = true;
            return gitRepository;
        } finally {
            if (!success) {
                closeRepository(commitIdDatabase, jGitRepository);
            }
        }
    }

    private static Revision uncachedHeadRevision(org.eclipse.jgit.lib.Repository jGitRepository, Project parent,
                                                 File repoDir) {
        try (RevWalk revWalk = newRevWalk(jGitRepository.newObjectReader())) {
            final ObjectId headRevisionId = jGitRepository.resolve(R_HEADS_MASTER);
            if (headRevisionId != null) {
                final RevCommit revCommit = revWalk.parseCommit(headRevisionId);
                return CommitUtil.extractRevision(revCommit.getFullMessage());
            }
        } catch (CentralDogmaException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to get the current revision", e);
        }

        throw new StorageException("failed to determine the HEAD: " + parent.name() + '/' + repoDir.getName());
    }

    @Override
    protected Repository createChild(
            File childDir, Author author, long creationTimeMillis, boolean encrypt) throws Exception {
        requireNonNull(childDir, "childDir");
        requireNonNull(author, "author");
        if (encrypt) {
            return createEncryptionRepository(parent, childDir, author, creationTimeMillis,
                                              repositoryWorker, cache, encryptionStorageManager());
        } else {
            return createFileRepository(parent, childDir, author, creationTimeMillis, repositoryWorker, cache);
        }
    }

    @VisibleForTesting
    static GitRepository createEncryptionRepository(
            Project parent, File repoDir, Author author, long creationTimeMillis,
            Executor repositoryWorker, @Nullable RepositoryCache cache,
            EncryptionStorageManager encryptionStorageManager) throws IOException {
        if (!repoDir.mkdirs()) {
            throw new StorageException(
                    "failed to create a repository at: " + repoDir + " (exists already)");
        }
        try {
            Files.createFile(Paths.get(repoDir.getPath(), ENCRYPTED_REPO_PLACEHOLDER_FILE));
            return createEncryptedRepository(parent, repoDir, author, creationTimeMillis, repositoryWorker,
                                             cache, encryptionStorageManager);
        } catch (Throwable t) {
            deleteCruft(repoDir);
            throw new StorageException("failed to create a repository at: " + repoDir, t);
        }
    }

    private static GitRepository createEncryptedRepository(
            Project parent, File repoDir, Author author,
            long creationTimeMillis, Executor repositoryWorker,
            @Nullable RepositoryCache cache,
            EncryptionStorageManager encryptionStorageManager) throws IOException {
        final EncryptionGitStorage encryptionGitStorage =
                new EncryptionGitStorage(parent.name(), repoDir.getName(), encryptionStorageManager);
        final RocksDbRepository rocksDbRepository = new RocksDbRepository(encryptionGitStorage);
        // Initialize the master branch.
        final RefUpdate head = rocksDbRepository.updateRef(Constants.HEAD);
        head.disableRefLog();
        head.link(R_HEADS_MASTER);
        final RocksDbCommitIdDatabase commitIdDatabase =
                new RocksDbCommitIdDatabase(encryptionGitStorage, null);
        return new GitRepository(parent, repoDir, repositoryWorker,
                                 creationTimeMillis, author, cache,
                                 rocksDbRepository, commitIdDatabase);
    }

    @VisibleForTesting
    static GitRepository createFileRepository(
            Project parent, File repoDir, Author author, long creationTimeMillis, Executor repositoryWorker,
            @Nullable RepositoryCache cache) throws IOException {
        org.eclipse.jgit.lib.Repository jGitRepository = null;
        CommitIdDatabase commitIdDatabase = null;
        try {
            final RepositoryBuilder repositoryBuilder = new RepositoryBuilder().setGitDir(repoDir).setBare();
            // Create an empty repository with format version 0 first.
            try (org.eclipse.jgit.lib.Repository initRepo = repositoryBuilder.build()) {
                if (exist(repoDir)) {
                    throw new StorageException(
                            "failed to create a repository at: " + repoDir + " (exists already)");
                }
                initRepo.create(true);

                // Save the initial default settings.
                JGitUtil.applyDefaultsAndSave(initRepo.getConfig());
            }

            // Re-open the repository with the updated settings.
            jGitRepository = new RepositoryBuilder().setGitDir(repoDir).build();

            // Initialize the master branch.
            final RefUpdate head = jGitRepository.updateRef(Constants.HEAD);
            head.disableRefLog();
            head.link(R_HEADS_MASTER);
            commitIdDatabase = new DefaultCommitIdDatabase(jGitRepository);
            return new GitRepository(parent, repoDir, repositoryWorker,
                                     creationTimeMillis, author, cache,
                                     jGitRepository, commitIdDatabase);
        } catch (Throwable t) {
            closeRepository(commitIdDatabase, jGitRepository);
            // Failed to create a repository. Remove any cruft so that it is not loaded on the next run.
            deleteCruft(repoDir);
            throw new StorageException("failed to create a repository at: " + repoDir, t);
        }
    }

    private static boolean exist(File repoDir) {
        try {
            final RepositoryBuilder repositoryBuilder = new RepositoryBuilder().setGitDir(repoDir);
            final org.eclipse.jgit.lib.Repository repository = repositoryBuilder.build();
            if (repository.getConfig() instanceof FileBasedConfig) {
                return ((FileBasedConfig) repository.getConfig()).getFile().exists();
            }
            return repository.getDirectory().exists();
        } catch (IOException e) {
            throw new StorageException("failed to check if repository exists at " + repoDir, e);
        }
    }

    @Override
    protected void closeChild(File childDir, Repository child,
                              Supplier<CentralDogmaException> failureCauseSupplier) {
        ((GitRepository) child).close(failureCauseSupplier);
    }

    @Override
    protected CentralDogmaException newStorageExistsException(String name) {
        return RepositoryExistsException.of(parent().name(), name);
    }

    @Override
    protected CentralDogmaException newStorageNotFoundException(String name) {
        return RepositoryNotFoundException.of(parent().name(), name);
    }

    @Override
    protected void deletePurged(File file) {
        final String repoName = removeInterfixAndPurgedSuffix(file.getName());
        logger.info("Deleting a purged repository: {} ..", repoName);
        if (isEncryptedRepository(file)) {
            encryptionStorageManager().deleteRepositoryData(parent.name(), repoName);
            // Then remove the directory below.
        }
        try {
            deleteFileTree(file);
            logger.info("Deleted a purged repository: {}.", repoName);
        } catch (IOException e) {
            logger.warn("Failed to delete a purged repository: {}", repoName, e);
        }
    }

    public static String removeInterfixAndPurgedSuffix(String name) {
        assert name.endsWith(SUFFIX_PURGED);
        name = name.substring(0, name.length() - SUFFIX_PURGED.length());
        final int index = name.lastIndexOf('.');
        assert index > 0;
        return name.substring(0, index);
    }
}
