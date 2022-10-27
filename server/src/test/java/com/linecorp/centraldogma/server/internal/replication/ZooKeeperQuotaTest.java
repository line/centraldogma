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

package com.linecorp.centraldogma.server.internal.replication;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.spotify.futures.CompletableFutures;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.TooManyRequestsException;
import com.linecorp.centraldogma.server.QuotaConfig;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.testing.internal.FlakyTest;

@FlakyTest
class ZooKeeperQuotaTest {

    private static final int MAX_QUOTA = 3;

    @Test
    void testLimitation() throws Exception {
        try (Cluster cluster = Cluster.builder()
                                      .writeQuota(new QuotaConfig(MAX_QUOTA, 3))
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {
            final int iteration = MAX_QUOTA * 5;
            final ImmutableList.Builder<CompletableFuture<?>> resultsBuilder =
                    ImmutableList.builderWithExpectedSize(iteration);
            final Replica replica = cluster.get(0);
            for (int i = 0; i < iteration; i++) {
                final Command<CommitResult> command =
                        Command.push(Author.SYSTEM, "project", "repo1", Revision.HEAD, "", "", Markup.PLAINTEXT,
                                     Change.ofTextUpsert("/foo", "foo " + i));
                resultsBuilder.add(replica.commandExecutor().doExecute(command));
            }
            final ImmutableList<CompletableFuture<?>> results = resultsBuilder.build();
            int limited = 0;
            int succeeded = 0;
            final String expectedMessage = String.format("'/project/repo1' (quota limit: %d.0/sec)",
                                                         MAX_QUOTA / 3);
            for (int i = 0; i < iteration; i++) {
                try {
                    results.get(i).join();
                    succeeded++;
                } catch (CompletionException e) {
                    final Throwable cause = e.getCause();
                    if (cause instanceof TooManyRequestsException &&
                        cause.getMessage().contains(expectedMessage)) {
                        limited++;
                    } else {
                        throw e;
                    }
                }
            }
            assertThat(succeeded).isGreaterThanOrEqualTo(MAX_QUOTA);
            assertThat(limited).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void testLease() throws Exception {
        try (Cluster cluster = Cluster.builder()
                                      .writeQuota(new QuotaConfig(MAX_QUOTA, 1))
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {
            ImmutableList.Builder<CompletableFuture<?>> resultsBuilder =
                    ImmutableList.builderWithExpectedSize(MAX_QUOTA);
            final Replica replica = cluster.get(0);
            for (int i = 0; i < MAX_QUOTA; i++) {
                final Command<CommitResult> command =
                        Command.push(Author.SYSTEM, "project", "repo1", Revision.HEAD, "", "", Markup.PLAINTEXT,
                                     Change.ofTextUpsert("/foo", "foo " + i));
                resultsBuilder.add(replica.commandExecutor().doExecute(command));
            }
            CompletableFutures.allAsList(resultsBuilder.build()).join();

            // Wait 1 sec to obtain a new quota
            Thread.sleep(1000);

            resultsBuilder = ImmutableList.builderWithExpectedSize(MAX_QUOTA);
            for (int i = 0; i < MAX_QUOTA; i++) {
                final Command<CommitResult> command =
                        Command.push(Author.SYSTEM, "project", "repo1", Revision.HEAD, "", "", Markup.PLAINTEXT,
                                     Change.ofTextUpsert("/foo", "foo " + i));
                resultsBuilder.add(replica.commandExecutor().doExecute(command));
            }
            CompletableFutures.allAsList(resultsBuilder.build()).join();
        }
    }
}
