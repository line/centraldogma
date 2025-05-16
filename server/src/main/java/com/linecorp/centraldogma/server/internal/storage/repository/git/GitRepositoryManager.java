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
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.CentralDogmaException;
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

    private static final String ENCRYPTION_REPO_PLACEHOLDER_FILE = ".encryption-repo-placeholder";

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
        return Files.exists(dir.toPath().resolve(ENCRYPTION_REPO_PLACEHOLDER_FILE));
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
                throw new RepositoryNotFoundException(repoDir.toString());
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
        try {
            if (!repoDir.mkdirs()) {
                throw new StorageException(
                        "failed to create a repository at: " + repoDir + " (exists already)");
            }
            Files.createFile(Paths.get(repoDir.getPath(), ENCRYPTION_REPO_PLACEHOLDER_FILE));
            final EncryptionGitStorage encryptionGitStorage =
                    new EncryptionGitStorage(parent.name(), repoDir.getName(), encryptionStorageManager);
            final RocksDbRepository rocksDbRepository = new RocksDbRepository(encryptionGitStorage);
            final RocksDbCommitIdDatabase commitIdDatabase =
                    new RocksDbCommitIdDatabase(encryptionGitStorage, null);
            return new GitRepository(parent, repoDir, repositoryWorker,
                                     creationTimeMillis, author, cache,
                                     rocksDbRepository, commitIdDatabase);
        } catch (Throwable t) {
            deleteCruft(repoDir);
            throw new StorageException("failed to create a repository at: " + repoDir, t);
        }
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
        return new RepositoryExistsException(parent().name() + '/' + name);
    }

    @Override
    protected CentralDogmaException newStorageNotFoundException(String name) {
        return new RepositoryNotFoundException(parent().name() + '/' + name);
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
