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

import static org.mockito.Mockito.mock;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadLocalRandom;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.internal.storage.project.Project;

@State(Scope.Benchmark)
public class GitRepositoryBenchmark {

    private static final Author AUTHOR = new Author("user@example.com");

    @Param({ "0", "2000", "4000", "6000", "8000" })
    private int previousCommits;

    @Param
    private GitRepositoryFormat format;

    private File repoDir;
    private GitRepository repo;
    private int currentRevision;

    @Setup
    public void init() throws Exception {
        repoDir = Files.createTempDirectory("jmh-gitrepository.").toFile();
        repo = new GitRepository(mock(Project.class), repoDir, format, ForkJoinPool.commonPool(),
                                 System.currentTimeMillis(), AUTHOR);
        currentRevision = 1;

        for (int i = 0; i < previousCommits; i++) {
            addCommit();
        }
    }

    @TearDown
    public void destroy() throws Exception {
        repo.close();
        Util.deleteFileTree(repoDir);
    }

    @Benchmark
    public void commit(Blackhole bh) throws Exception {
        bh.consume(addCommit());
    }

    private Revision addCommit() {
        final Revision revision =
                repo.commit(new Revision(currentRevision), currentRevision * 1000, AUTHOR,
                            "Summary", "Detail", Markup.PLAINTEXT,
                            Change.ofTextUpsert("/file_" + rnd() + ".txt",
                                                String.valueOf(currentRevision))).join();
        currentRevision++;
        return revision;
    }

    private static int rnd() {
        return ThreadLocalRandom.current().nextInt(10);
    }
}
