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

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GitRepositoryUtil {

    private static final Logger logger = LoggerFactory.getLogger(GitRepositoryUtil.class);

    static boolean exists(Repository repository) {
        if (repository.getConfig() instanceof FileBasedConfig) {
            return ((FileBasedConfig) repository.getConfig()).getFile().exists();
        }
        return repository.getDirectory().exists();
    }

    static void closeJGitRepo(Repository repository) {
        try {
            repository.close();
        } catch (Throwable t) {
            logger.warn("Failed to close a Git repository: {}", repository.getDirectory(), t);
        }
    }

    private GitRepositoryUtil() {}
}
