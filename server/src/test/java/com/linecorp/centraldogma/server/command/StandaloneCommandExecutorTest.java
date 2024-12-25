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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.BiFunction;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.RateLimiter;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.QuotaConfig;
import com.linecorp.centraldogma.server.management.ServerStatus;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class StandaloneCommandExecutorTest {

    private static final String TEST_PRJ = "test_prj";
    private static final String TEST_REPO = "test_repo";
    private static final String TEST_REPO2 = "test_repo2";
    private static final String TEST_REPO3 = "test_repo3";

    @RegisterExtension
    static ProjectManagerExtension extension = new ProjectManagerExtension();

    @BeforeAll
    static void setUp() {
        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) extension.executor();
        // Initialize repository
        executor.execute(Command.createProject(Author.SYSTEM, TEST_PRJ)).join();
        executor.execute(Command.createRepository(Author.SYSTEM, TEST_PRJ, TEST_REPO)).join();
        executor.execute(Command.createRepository(Author.SYSTEM, TEST_PRJ, TEST_REPO2)).join();
        executor.execute(Command.createRepository(Author.SYSTEM, TEST_PRJ, TEST_REPO3)).join();

        final MetadataService mds = new MetadataService(extension.projectManager(), executor);
        // Metadata should be created before entering read-only mode.
        mds.addRepo(Author.SYSTEM, TEST_PRJ, TEST_REPO).join();
        mds.addRepo(Author.SYSTEM, TEST_PRJ, TEST_REPO2).join();
        mds.addRepo(Author.SYSTEM, TEST_PRJ, TEST_REPO3).join();
    }

    @Test
    void setWriteQuota() {
        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) extension.executor();
        final MetadataService mds = new MetadataService(extension.projectManager(), executor);

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
                                Author.SYSTEM, TEST_PRJ, TEST_REPO2, Revision.HEAD, "", "",
                                Markup.PLAINTEXT, change))
                        .join();
        // The same json upsert.
        final Revision previousRevision = commitResult.revision();
        assertThat(commitResult).isEqualTo(CommitResult.of(previousRevision, ImmutableList.of(change)));

        // Json upsert is converted into json patch.
        change = Change.ofJsonUpsert("/foo.json", "{\"a\": \"c\"}");
        commitResult =
                executor.execute(Command.push(
                                Author.SYSTEM, TEST_PRJ, TEST_REPO2, Revision.HEAD, "", "",
                                Markup.PLAINTEXT, change))
                        .join();

        assertThat(commitResult.revision()).isEqualTo(previousRevision.forward(1));
        final List<Change<?>> changes = commitResult.changes();
        assertThat(changes).hasSize(1);
        assertThatJson(changes.get(0).content()).isEqualTo(
                "[{\"op\":\"safeReplace\"," +
                "\"path\":\"/a\"," +
                "\"oldValue\":\"b\"," +
                "\"value\":\"c\"}" +
                ']');

        change = Change.ofJsonUpsert("/foo.json", "{\"a\": \"d\"}");
        // PushAsIs just uses the json upsert.
        final Revision revision = executor.execute(
                new PushAsIsCommand(0L, Author.SYSTEM, TEST_PRJ, TEST_REPO2, Revision.HEAD,
                                    "", "", Markup.PLAINTEXT, ImmutableList.of(change))).join();
        assertThat(revision).isEqualTo(previousRevision.forward(2));
    }

    @Test
    void shouldPerformAdministrativeCommandWithReadOnly() throws JsonParseException {
        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) extension.executor();
        executor.execute(Command.updateServerStatus(ServerStatus.REPLICATION_ONLY)).join();
        assertThat(executor.isWritable()).isFalse();

        final Change<JsonNode> change = Change.ofJsonUpsert("/foo.json", "{\"a\": \"b\"}");
        final Command<CommitResult> push = Command.push(
                Author.SYSTEM, TEST_PRJ, TEST_REPO3, Revision.HEAD, "", "", Markup.PLAINTEXT, change);
        assertThatThrownBy(() -> executor.execute(push))
                .isInstanceOf(ReadOnlyException.class)
                .hasMessageContaining("running in read-only mode.");
        // The same json upsert.
        final CommitResult commitResult = executor.execute(Command.forcePush(push)).join();
        assertThat(commitResult).isEqualTo(CommitResult.of(new Revision(2), ImmutableList.of(change)));
        final ObjectNode json = (ObjectNode) extension.projectManager()
                                                      .get(TEST_PRJ)
                                                      .repos().get(TEST_REPO3)
                                                      .get(Revision.HEAD, "/foo.json")
                                                      .join()
                                                      .contentAsJson();
        assertThat(json.get("a").asText()).isEqualTo("b");
        executor.execute(Command.updateServerStatus(ServerStatus.WRITABLE)).join();
        assertThat(executor.isWritable()).isTrue();
    }

    @Test
    void createInternalProject() {
        final CommandExecutor executor = extension.executor();
        final String internalProjectName = "@project";
        executor.execute(Command.createProject(Author.SYSTEM, internalProjectName)).join();
        final MetadataService mds = new MetadataService(extension.projectManager(), executor);
        mds.addRepo(Author.SYSTEM, internalProjectName, TEST_REPO).join();
        // Can create an internal project that starts with an underscore.
        executor.execute(Command.createRepository(Author.SYSTEM, internalProjectName, TEST_REPO))
                .join();
        final Change<JsonNode> change = Change.ofJsonUpsert("/foo.json", "{\"a\": \"b\"}");
        // Can push to an internal project.
        final CommitResult commitResult =
                executor.execute(Command.push(
                                Author.SYSTEM, internalProjectName, TEST_REPO, Revision.HEAD, "", "",
                                Markup.PLAINTEXT, change))
                        .join();
        assertThat(commitResult).isEqualTo(CommitResult.of(new Revision(2), ImmutableList.of(change)));
    }

    @Test
    void transformCommandConvertedIntoJsonPatch() {
        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) extension.executor();

        // Initial commit.
        final Change<JsonNode> change = Change.ofJsonUpsert("/bar.json", "{\"a\": \"b\"}");
        CommitResult commitResult =
                executor.execute(Command.push(
                                Author.SYSTEM, TEST_PRJ, TEST_REPO2, Revision.HEAD, "", "",
                                Markup.PLAINTEXT, change))
                        .join();
        // The same json upsert.
        final Revision previousRevision = commitResult.revision();
        assertThat(commitResult).isEqualTo(CommitResult.of(previousRevision, ImmutableList.of(change)));

        final BiFunction<Revision, JsonNode, JsonNode> transformer = (revision, jsonNode) -> {
            if (jsonNode.has("a")) {
                ((ObjectNode) jsonNode).put("a", "c");
            }
            return jsonNode;
        };
        final ContentTransformer<JsonNode> contentTransformer =
                new ContentTransformer<>("/bar.json", EntryType.JSON, transformer);

        commitResult =
                executor.execute(Command.transform(
                        null, Author.SYSTEM, TEST_PRJ, TEST_REPO2, Revision.HEAD, "", "",
                        Markup.PLAINTEXT, contentTransformer)).join();

        // Json upsert is converted into json patch.

        assertThat(commitResult.revision()).isEqualTo(previousRevision.forward(1));
        final List<Change<?>> changes = commitResult.changes();
        assertThat(changes).hasSize(1);
        assertThatJson(changes.get(0).content()).isEqualTo(
                "[{\"op\":\"safeReplace\"," +
                "\"path\":\"/a\"," +
                "\"oldValue\":\"b\"," +
                "\"value\":\"c\"}" +
                ']');
    }
}
