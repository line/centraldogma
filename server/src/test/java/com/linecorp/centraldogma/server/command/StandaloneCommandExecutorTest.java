/*
 * Copyright 2020 LINE Corporation
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
 * under the License
 */

package com.linecorp.centraldogma.server.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.util.concurrent.RateLimiter;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.QuotaConfig;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class StandaloneCommandExecutorTest {

    @RegisterExtension
    static ProjectManagerExtension extension = new ProjectManagerExtension();

    @Test
    void setWriteQuota() throws InterruptedException {
        final String project = "test_prj";
        final String repo = "test_repo";
        final ProjectManager pm = extension.projectManager();
        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) extension.executor();
        final MetadataService mds = new MetadataService(pm, executor);

        // Initialize repository
        executor.execute(Command.createProject(Author.SYSTEM, project)).join();
        executor.execute(Command.createRepository(Author.SYSTEM, project, repo)).join();
        mds.addRepo(Author.SYSTEM, project, repo).join();

        final RateLimiter rateLimiter1 = executor.writeRateLimiters.get("test_prj/test_repo");
        assertThat(rateLimiter1).isNull();
        mds.updateWriteQuota(Author.SYSTEM, "test_prj", "test_repo", new QuotaConfig(10, 1)).join();
        final RateLimiter rateLimiter2 = executor.writeRateLimiters.get("test_prj/test_repo");
        assertThat(rateLimiter2.getRate()).isEqualTo(10);

        mds.updateWriteQuota(Author.SYSTEM, "test_prj", "test_repo", new QuotaConfig(5, 1)).join();
        final RateLimiter rateLimiter3 = executor.writeRateLimiters.get("test_prj/test_repo");
        // Should update the existing rate limiter.
        assertThat(rateLimiter3).isSameAs(rateLimiter2);
        assertThat(rateLimiter2.getRate()).isEqualTo(5);
    }
}
