/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server;

import static com.linecorp.centraldogma.internal.HistoryConstants.UPSTREAM_TAG_PREFIX;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.lib.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.testing.internal.CentralDogmaReplicationExtension;
import com.linecorp.centraldogma.testing.internal.CentralDogmaRuleDelegate;

class UpstreamCommitTagReplicationTest {

    private static final String PROJECT = "project";
    private static final String REPOSITORY = "repository";
    private static final String UPSTREAM_COMMIT_ID = "0123456789abcdef0123456789abcdef01234567";

    @RegisterExtension
    final CentralDogmaReplicationExtension cluster = new CentralDogmaReplicationExtension(3) {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void movesTagOnEveryReplica() throws Exception {
        final CentralDogmaRuleDelegate source = cluster.servers().get(0);
        source.client().createProject(PROJECT).join();
        source.client().createRepository(PROJECT, REPOSITORY).join();

        final Revision firstRevision = requireNonNull(source.dogma().executor()).execute(Command.push(
                System.currentTimeMillis(), Author.SYSTEM, PROJECT, REPOSITORY, Revision.INIT,
                "Mirror first upstream commit", "", Markup.PLAINTEXT, UPSTREAM_COMMIT_ID, true,
                List.of(Change.ofTextUpsert("/a.txt", "1")))).join();
        awaitRevision(firstRevision);

        final List<String> firstTagTargets = new ArrayList<>();
        for (CentralDogmaRuleDelegate replica : cluster.servers()) {
            final String tagTarget = refValue(
                    replica, Constants.R_TAGS + UPSTREAM_TAG_PREFIX + UPSTREAM_COMMIT_ID);
            assertThat(tagTarget).isEqualTo(refValue(replica, "refs/heads/master"));
            firstTagTargets.add(tagTarget);
        }

        final Revision secondRevision = requireNonNull(source.dogma().executor()).execute(Command.push(
                System.currentTimeMillis(), Author.SYSTEM, PROJECT, REPOSITORY, firstRevision,
                "Mirror the same upstream commit again", "", Markup.PLAINTEXT,
                UPSTREAM_COMMIT_ID, true, List.of(Change.ofTextUpsert("/a.txt", "2")))).join();
        awaitRevision(secondRevision);

        for (int i = 0; i < cluster.servers().size(); i++) {
            final CentralDogmaRuleDelegate replica = cluster.servers().get(i);
            final String tagTarget = refValue(
                    replica, Constants.R_TAGS + UPSTREAM_TAG_PREFIX + UPSTREAM_COMMIT_ID);
            assertThat(tagTarget).isEqualTo(refValue(replica, "refs/heads/master"))
                                 .isNotEqualTo(firstTagTargets.get(i));
        }
    }

    private void awaitRevision(Revision revision) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            for (CentralDogmaRuleDelegate replica : cluster.servers()) {
                assertThat(replica.client().normalizeRevision(PROJECT, REPOSITORY, Revision.HEAD).join())
                        .isEqualTo(revision);
            }
        });
    }

    private static String refValue(CentralDogmaRuleDelegate replica, String ref) throws Exception {
        final Path repoDir = replica.dogma().config().dataDir().toPath().resolve(PROJECT).resolve(REPOSITORY);
        return Files.readString(repoDir.resolve(ref), UTF_8).trim();
    }
}
