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

package com.linecorp.centraldogma.server.internal.mirror;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.GarbageCollectCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.lib.EmptyProgressMonitor;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.server.internal.IsolatedSystemReader;
import com.linecorp.centraldogma.server.internal.JGitUtil;

final class GitWithAuth extends Git {

    private static final Logger logger = LoggerFactory.getLogger(GitWithAuth.class);

    /**
     * One of the Locks in this array is locked while a Git repository is accessed so that other GitMirrors
     * that access the same repository cannot access it at the same time. The lock is chosen based on the
     * hash code of the Git repository path. See {@link #getLock(File)} for more information.
     *
     * <p>The number of available locks is hard-coded, but it should be large enough for most use cases.
     */
    private static final Lock[] locks = new Lock[1024];

    static {
        IsolatedSystemReader.install();

        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    private static Lock getLock(File repoDir) {
        final int h = repoDir.getPath().hashCode();
        return locks[Math.abs((h ^ h >>> 16) % locks.length)];
    }

    private final AbstractGitMirror mirror;
    private final Lock lock;
    private final URIish remoteUri;
    private final Map<String, ProgressMonitor> progressMonitors = new HashMap<>();

    GitWithAuth(AbstractGitMirror mirror, File repoDir, URIish remoteUri) throws IOException {
        super(repo(repoDir));
        this.mirror = mirror;
        lock = getLock(repoDir);
        this.remoteUri = remoteUri;
    }

    URIish remoteUri() {
        return remoteUri;
    }

    private static Repository repo(File repoDir) throws IOException {
        final Lock lock = getLock(repoDir);
        boolean success = false;
        lock.lock();
        try {
            repoDir.getParentFile().mkdirs();
            final Repository repo = new RepositoryBuilder().setGitDir(repoDir).setBare().build();
            if (!repo.getObjectDatabase().exists()) {
                repo.create(true);
            }

            JGitUtil.applyDefaultsAndSave(repo.getConfig());
            success = true;
            return repo;
        } finally {
            if (!success) {
                lock.unlock();
            }
        }
    }

    @Override
    public void close() {
        try {
            super.close();
        } finally {
            try {
                getRepository().close();
            } finally {
                lock.unlock();
            }
        }
    }

    private ProgressMonitor progressMonitor(String name) {
        return progressMonitors.computeIfAbsent(name, MirrorProgressMonitor::new);
    }

    @Override
    public FetchCommand fetch() {
        return super.fetch().setProgressMonitor(progressMonitor("fetch"));
    }

    @Override
    public PushCommand push() {
        return super.push().setProgressMonitor(progressMonitor("push"));
    }

    @Override
    public GarbageCollectCommand gc() {
        return super.gc().setProgressMonitor(progressMonitor("gc"));
    }

    private final class MirrorProgressMonitor extends EmptyProgressMonitor {

        private final String operationName;

        MirrorProgressMonitor(String operationName) {
            this.operationName = requireNonNull(operationName, "operationName");
        }

        @Override
        public void beginTask(String title, int totalWork) {
            if (totalWork > 0 && logger.isInfoEnabled()) {
                logger.info("[{}] {} ({}, total: {})", operationName, mirror.remoteRepoUri(), title, totalWork);
            }
        }
    }
}
