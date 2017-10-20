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

package com.linecorp.centraldogma.it;

import static com.linecorp.centraldogma.internal.thrift.ErrorCode.BAD_REQUEST;
import static com.linecorp.centraldogma.internal.thrift.ErrorCode.REPOSITORY_EXISTS;
import static com.linecorp.centraldogma.internal.thrift.ErrorCode.REPOSITORY_NOT_FOUND;
import static com.linecorp.centraldogma.testing.internal.ExpectedExceptionAppender.assertThatThrownByWithExpectedException;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CompletionException;

import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.client.RepositoryInfo;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryExistsException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryNotFoundException;

public class RepositoryManagementTest {

    @ClassRule
    public static final CentralDogmaRuleWithScaffolding rule = new CentralDogmaRuleWithScaffolding();

    private static final Logger logger = LoggerFactory.getLogger(RepositoryManagementTest.class);

    @Test
    public void testUnremoveRepository() throws Exception {
        try {
            rule.client().unremoveRepository(rule.project(), rule.removedRepo()).join();
            final Map<String, RepositoryInfo> repos = rule.client().listRepositories(rule.project()).join();
            assertThat(repos).containsKey(rule.removedRepo());
        } finally {
            try {
                rule.client().removeRepository(rule.project(), rule.removedRepo()).join();
            } catch (Exception e) {
                logger.warn("Failed to re-remove a project: {}", rule.removedProject(), e);
            }
        }
    }

    @Test
    public void testCreateRepositoryFailures() throws Exception {
        assertThatThrownByWithExpectedException(RepositoryExistsException.class, "repository: r", () ->
                rule.client().createRepository(rule.project(), rule.repo1()).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == REPOSITORY_EXISTS);

        assertThatThrownByWithExpectedException(RepositoryExistsException.class, "repository: rr", () ->
                // It is not allowed to create a new repository whose name is same with the removed repository.
                rule.client().createRepository(rule.project(), rule.removedRepo()).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == REPOSITORY_EXISTS);

        assertThatThrownByWithExpectedException(
                IllegalArgumentException.class, "invalid repository name: ..", () ->
                        rule.client().createRepository(rule.project(), "..").join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == BAD_REQUEST);
    }

    @Test
    public void testRemoveRepositoryFailures() throws Exception {
        assertThatThrownByWithExpectedException(
                RepositoryNotFoundException.class, "repository: mr", () ->
                        rule.client().removeRepository(rule.project(), rule.missingRepo()).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CentralDogmaException.class)
                .matches(e -> ((CentralDogmaException) e.getCause()).getErrorCode() == REPOSITORY_NOT_FOUND);
    }

    @Test
    public void testListRepositories() throws Exception {
        final Map<String, RepositoryInfo> repos = rule.client().listRepositories(rule.project()).join();

        // Should contain 2 "rNNN"s
        assertThat(repos.keySet()).containsExactlyInAnyOrder("meta", "main", rule.repo1(), rule.repo2());

        for (RepositoryInfo r : repos.values()) {
            final Commit headCommit = r.lastCommit();
            assertThat(headCommit).isNotNull();
        }
    }
}
