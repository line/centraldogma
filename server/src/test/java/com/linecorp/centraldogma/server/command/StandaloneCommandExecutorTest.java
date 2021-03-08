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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.RateLimiter;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.QuotaConfig;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class StandaloneCommandExecutorTest {

    private static final String TEST_PRJ = "test_prj";
    private static final String TEST_REPO = "test_repo";
    private static final String TEST_REPO2 = "test_repo2";

    @RegisterExtension
    static ProjectManagerExtension extension = new ProjectManagerExtension();

    @BeforeAll
    static void setUp() {
        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) extension.executor();
        // Initialize repository
        executor.execute(Command.createProject(Author.SYSTEM, TEST_PRJ)).join();
        executor.execute(Command.createRepository(Author.SYSTEM, TEST_PRJ, TEST_REPO)).join();
        executor.execute(Command.createRepository(Author.SYSTEM, TEST_PRJ, TEST_REPO2)).join();
    }

    @Test
    void setWriteQuota() {
        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) extension.executor();
        final MetadataService mds = new MetadataService(extension.projectManager(), executor);
        mds.addRepo(Author.SYSTEM, TEST_PRJ, TEST_REPO).join();

        final RateLimiter rateLimiter1 = executor.writeRateLimiters.get("test_prj/test_repo");
        assertThat(rateLimiter1).isNull();
        mds.updateWriteQuota(Author.SYSTEM, TEST_PRJ, TEST_REPO, new QuotaConfig(10, 1)).join();
        final RateLimiter rateLimiter2 = executor.writeRateLimiters.get("test_prj/test_repo");
        assertThat(rateLimiter2.getRate()).isEqualTo(10);

        mds.updateWriteQuota(Author.SYSTEM, TEST_PRJ, TEST_REPO, new QuotaConfig(20, 1)).join();
        final RateLimiter rateLimiter3 = executor.writeRateLimiters.get("test_prj/test_repo");
        // Should update the existing rate limiter.
        assertThat(rateLimiter3).isSameAs(rateLimiter2);
        assertThat(rateLimiter2.getRate()).isEqualTo(20);
    }

    @Test
    void jsonUpsertPushCommandConvertedIntoJsonPatchWhenApplicable() {
        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) extension.executor();

        // Initial commit.
        Change<JsonNode> change = Change.ofJsonUpsert("/foo.json", "{\"a\": \"b\"}");
        CommitResult commitResult =
                executor.execute(Command.push(
                        Author.SYSTEM, TEST_PRJ, TEST_REPO2, Revision.HEAD, "", "", Markup.PLAINTEXT, change))
                        .join();
        // The same json upsert.
        assertThat(commitResult).isEqualTo(CommitResult.of(new Revision(2), ImmutableList.of(change)));

        // Json upsert is converted into json patch.
        change = Change.ofJsonUpsert("/foo.json", "{\"a\": \"c\"}");
        commitResult =
                executor.execute(Command.push(
                        Author.SYSTEM, TEST_PRJ, TEST_REPO2, Revision.HEAD, "", "", Markup.PLAINTEXT, change))
                        .join();

        assertThat(commitResult.revision()).isEqualTo(new Revision(3));
        final List<Change<?>> changes = commitResult.changes();
        assertThat(changes).hasSize(1);
        assertThatJson(changes.get(0).content()).isEqualTo(
                "[{\"op\":\"safeReplace\"," +
                "\"path\":\"/a\"," +
                "\"oldValue\":\"b\"," +
                "\"value\":\"c\"}" +
                "]");

        change = Change.ofJsonUpsert("/foo.json", "{\"a\": \"d\"}");
        // PushAsIs just uses the json upsert.
        final Revision revision = executor.execute(
                Command.pushAsIs(0L, Author.SYSTEM, TEST_PRJ, TEST_REPO2, Revision.HEAD,
                                 "", "", Markup.PLAINTEXT, ImmutableList.of(change))).join();
        assertThat(revision).isEqualTo(new Revision(4));
    }
}
