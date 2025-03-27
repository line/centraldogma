/*
 * Copyright 2025 LINE Corporation
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

import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_REPOSITORY_CACHE_SPEC;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryManager.createFileRepository;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ForkJoinPool;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.storage.project.Project;

@State(Scope.Benchmark)
public class GitRepositoryHistoryBenchmark {

    private static final Author AUTHOR = Author.ofEmail("user@example.com");

    @Param({ "100", "1000"})
    private int noCommits;

    @Param({ "1", "3", "5", "10", "30" })
    private int noFiles;

    private File repoDir;
    private GitRepository repo;
    private int currentRevision;
    private RepositoryCache cache;

    @Setup
    public void init() throws Exception {
        repoDir = Files.createTempDirectory("jmh-gitrepository.").toFile();
        cache = new RepositoryCache(DEFAULT_REPOSITORY_CACHE_SPEC, NoopMeterRegistry.get());
        repo = createFileRepository(mock(Project.class), repoDir, AUTHOR,
                                    System.currentTimeMillis(), ForkJoinPool.commonPool(), cache);
        currentRevision = 1;

        // 1000 is the maximum number of allowed commits for a single history query.
        for (int i = 0; i < noCommits; i++) {
            addCommit(i);
        }
    }

    @TearDown
    public void destroy() throws Exception {
        repo.internalClose();
        Util.deleteFileTree(repoDir);
    }

    @Benchmark
    public void history(Blackhole bh) throws Exception {
        cache.clear();
        for (int i = 0; i < noFiles; i++) {
            bh.consume(repo.blockingHistory(new Revision(noCommits), Revision.INIT,
                                            "/dir/file_" + i + ".txt", 1));
        }
    }

    private void addCommit(int index) {
        repo.commit(new Revision(currentRevision), currentRevision * 1000L, AUTHOR,
                    "Summary", "Detail", Markup.PLAINTEXT,
                    Change.ofTextUpsert("/dir/file_" + index + ".txt",
                                        String.valueOf(currentRevision))).join();
        currentRevision++;
    }
}
