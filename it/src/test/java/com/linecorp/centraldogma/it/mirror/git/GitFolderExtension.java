/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.it.mirror.git;

import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_COMMIT_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_GPGSIGN;

import java.io.File;
import java.io.IOException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.centraldogma.testing.internal.TemporaryFolder;
import com.linecorp.centraldogma.testing.internal.TestUtil;
import com.linecorp.centraldogma.testing.junit.AbstractAllOrEachExtension;

class GitFolderExtension extends AbstractAllOrEachExtension {

    private final TemporaryFolder tempDir;
    @Nullable
    private Git git;
    @Nullable
    private File gitWorkTree;
    @Nullable
    private String fileUri;

    public GitFolderExtension() {
        tempDir = new TemporaryFolder();
    }

    @Override
    protected void before(ExtensionContext context) throws Exception {
        tempDir.create();

        final String displayName = TestUtil.normalizedDisplayName(context);
        gitWorkTree = new File(tempDir.getRoot().toFile(), displayName).getAbsoluteFile();
        final Repository gitRepo = new FileRepositoryBuilder().setWorkTree(gitWorkTree).build();
        createGitRepo(gitRepo);

        git = Git.wrap(gitRepo);
        fileUri = "git+file://" +
                  (gitWorkTree.getPath().startsWith(File.separator) ? "" : '/') +
                  gitWorkTree.getPath().replace(File.separatorChar, '/') +
                  "/.git";
        // Start the master branch with an empty commit.
        git.commit().setMessage("Initial commit").call();
    }

    private static void createGitRepo(Repository gitRepo) throws IOException {
        gitRepo.create();

        // Disable GPG signing.
        final StoredConfig config = gitRepo.getConfig();
        config.setBoolean(CONFIG_COMMIT_SECTION, null, CONFIG_KEY_GPGSIGN, false);
        config.save();
    }

    @Override
    protected void after(ExtensionContext context) throws Exception {
        git().close();
        tempDir.delete();

        git = null;
        fileUri = null;
        gitWorkTree = null;
    }

    public Git git() {
        assert git != null;
        return git;
    }

    public String fileUri() {
        assert fileUri != null;
        return fileUri;
    }

    public File gitWorkTree() {
        assert gitWorkTree != null;
        return gitWorkTree;
    }
}
