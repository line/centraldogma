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

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.common.Revision;
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

        final MetadataService mds = new MetadataService(extension.projectManager(), executor,
                                                        extension.internalProjectInitializer());
        // Metadata should be created before entering read-only mode.
        mds.addRepo(Author.SYSTEM, TEST_PRJ, TEST_REPO).join();
        mds.addRepo(Author.SYSTEM, TEST_PRJ, TEST_REPO2).join();
        mds.addRepo(Author.SYSTEM, TEST_PRJ, TEST_REPO3).join();
    }

    @Test
    void storeJsonUpsertPushCommandAsIs() throws JsonParseException {
        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) extension.executor();

        // Initial commit.
        Change<JsonNode> change = Change.ofJsonUpsert("/foo.json", "{\"a\": \"b\"}");
        final Revision previousRevision =
                executor.execute(Command.push(
                                Author.SYSTEM, TEST_PRJ, TEST_REPO2, Revision.HEAD, "", "",
                                Markup.PLAINTEXT, change))
                        .join();
        // The same json upsert.

        // Json upsert is converted into json patch.
        change = Change.ofJsonUpsert("/foo.json", "{\"a\": \"c\"}");
        final Revision nextRevision =
                executor.execute(Command.push(
                                Author.SYSTEM, TEST_PRJ, TEST_REPO2, Revision.HEAD, "", "",
                                Markup.PLAINTEXT, change))
                        .join();

        assertThat(nextRevision).isEqualTo(previousRevision.forward(1));
        JsonNode jsonNode = extension.projectManager().get(TEST_PRJ)
                                     .repos().get(TEST_REPO2)
                                     .get(Revision.HEAD, "/foo.json")
                                     .join().contentAsJson();
        assertThatJson(jsonNode).isEqualTo("{\"a\": \"c\"}");

        change = Change.ofJsonUpsert("/foo.json", "{\"a\": \"d\"}");
        // PushAsIs just uses the json upsert.
        final Revision revision = executor.execute(
                Command.push(Author.SYSTEM, TEST_PRJ, TEST_REPO2, Revision.HEAD,
                             "", "", Markup.PLAINTEXT, change)).join();
        assertThat(revision).isEqualTo(previousRevision.forward(2));
        jsonNode = extension.projectManager().get(TEST_PRJ)
                            .repos().get(TEST_REPO2)
                            .get(Revision.HEAD, "/foo.json")
                            .join().contentAsJson();
        assertThatJson(jsonNode).isEqualTo("{\"a\": \"d\"}");
    }

    @Test
    void shouldPerformAdministrativeCommandWithReadOnly() throws JsonParseException {
        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) extension.executor();
        executor.execute(Command.updateServerStatus(ServerStatus.REPLICATION_ONLY)).join();
        assertThat(executor.isWritable()).isFalse();

        final Change<JsonNode> change = Change.ofJsonUpsert("/foo.json", "{\"a\": \"b\"}");
        final Command<Revision> push = Command.push(
                Author.SYSTEM, TEST_PRJ, TEST_REPO3, Revision.HEAD, "", "", Markup.PLAINTEXT, change);
        assertThatThrownBy(() -> executor.execute(push))
                .isInstanceOf(ReadOnlyException.class)
                .hasMessageContaining("running in read-only mode.");
        // The same json upsert.
        final Revision revision = executor.execute(Command.forcePush(push)).join();
        assertThat(revision).isEqualTo(new Revision(2));
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
        final MetadataService mds = new MetadataService(extension.projectManager(), executor,
                                                        extension.internalProjectInitializer());
        mds.addRepo(Author.SYSTEM, internalProjectName, TEST_REPO).join();
        // Can create an internal project that starts with an underscore.
        executor.execute(Command.createRepository(Author.SYSTEM, internalProjectName, TEST_REPO))
                .join();
        final Change<JsonNode> change = Change.ofJsonUpsert("/foo.json", "{\"a\": \"b\"}");
        // Can push to an internal project.
        final Revision revision =
                executor.execute(Command.push(
                                Author.SYSTEM, internalProjectName, TEST_REPO, Revision.HEAD, "", "",
                                Markup.PLAINTEXT, change))
                        .join();
        assertThat(revision).isEqualTo(new Revision(2));
    }

    @Test
    void transformCommandConvertedIntoJsonPatch() {
        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) extension.executor();

        // Initial commit.
        final Change<JsonNode> change = Change.ofJsonUpsert("/bar.json", "{\"a\": \"b\"}");
        final Revision previousRevision =
                executor.execute(Command.push(
                                Author.SYSTEM, TEST_PRJ, TEST_REPO2, Revision.HEAD, "", "",
                                Markup.PLAINTEXT, change))
                        .join();
        // The same json upsert.

        final BiFunction<Revision, JsonNode, JsonNode> transformer = (revision, jsonNode) -> {
            if (jsonNode.has("a")) {
                ((ObjectNode) jsonNode).put("a", "c");
            }
            return jsonNode;
        };
        final ContentTransformer<JsonNode> contentTransformer =
                new ContentTransformer<>("/bar.json", EntryType.JSON, transformer);

        final CommitResult commitResult =
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
