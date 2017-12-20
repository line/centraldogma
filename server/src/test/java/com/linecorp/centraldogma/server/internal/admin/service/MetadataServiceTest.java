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

package com.linecorp.centraldogma.server.internal.admin.service;

import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.initializeInternalProject;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.admin.model.ProjectInfo;
import com.linecorp.centraldogma.server.internal.admin.model.ProjectRole;
import com.linecorp.centraldogma.server.internal.admin.model.TokenInfo;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

public class MetadataServiceTest {

    @ClassRule
    public static final TemporaryFolder rootDir = new TemporaryFolder();

    @Test
    public void test() throws Exception {
        final ProjectManager pm = new DefaultProjectManager(
                rootDir.newFolder(), ForkJoinPool.commonPool(), null);
        final CommandExecutor executor = new StandaloneCommandExecutor(pm, null, ForkJoinPool.commonPool());
        executor.start(null, null);
        initializeInternalProject(executor);

        try {
            final MetadataService mds = new MetadataService(pm, executor);
            mds.initialize();

            List<ProjectInfo> list;
            ProjectInfo projectInfo;

            // Projects
            list = mds.getAllProjects().toCompletableFuture().join();
            assertThat(list).isEmpty();

            createOneProjectAndValidate(mds, "coconut");
            createOneProjectAndValidate(mds, "apple");
            createOneProjectAndValidate(mds, "banana");

            list = mds.getAllProjects().toCompletableFuture().join();
            assertThat(list.size()).isEqualTo(3);
            assertThat(list.get(0).name()).isEqualTo("apple");
            assertThat(list.get(1).name()).isEqualTo("banana");
            assertThat(list.get(2).name()).isEqualTo("coconut");

            projectInfo = mds.removeProject("banana", Author.DEFAULT)
                             .toCompletableFuture().join();
            assertThat(projectInfo.name()).isEqualTo("banana");

            list = mds.getValidProjects().toCompletableFuture().join();
            assertThat(list.size()).isEqualTo(2);
            assertThat(list.get(0).name()).isEqualTo("apple");
            assertThat(list.get(1).name()).isEqualTo("coconut");

            final User user1 = new User("User1@localhost.localdomain");
            final User user2 = new User("User2@localhost.localdomain");

            // Members
            projectInfo = mds.addMember("apple", Author.DEFAULT, user2, ProjectRole.MEMBER)
                             .toCompletableFuture().join();
            projectInfo = mds.addMember("apple", Author.DEFAULT, user1, ProjectRole.MEMBER)
                             .toCompletableFuture().join();

            assertThat(projectInfo.name()).isEqualTo("apple");
            assertThat(projectInfo.members().size()).isEqualTo(2);
            assertThat(projectInfo.members().get(0).login()).isEqualTo(user1.name());
            assertThat(projectInfo.members().get(1).login()).isEqualTo(user2.name());

            projectInfo = mds.removeMember("apple", Author.DEFAULT, user1)
                             .toCompletableFuture().join();

            assertThat(projectInfo.name()).isEqualTo("apple");
            assertThat(projectInfo.members().size()).isOne();
            assertThat(projectInfo.members().get(0).login()).isEqualTo(user2.name());

            list = mds.findProjects(user2).toCompletableFuture().join();
            assertThat(list.size()).isOne();
            assertThat(list.get(0).name()).isEqualTo("apple");

            projectInfo = mds.addMember("coconut", Author.DEFAULT, user2, ProjectRole.OWNER)
                             .toCompletableFuture().join();

            list = mds.findProjects(user2).toCompletableFuture().join();
            assertThat(list.size()).isEqualTo(2);
            assertThat(list.get(0).name()).isEqualTo("apple");
            assertThat(list.get(1).name()).isEqualTo("coconut");

            assertThat(mds.findRole("apple", user2).toCompletableFuture().join())
                    .isEqualTo(ProjectRole.MEMBER);
            assertThat(mds.findRole("coconut", user2).toCompletableFuture().join())
                    .isEqualTo(ProjectRole.OWNER);
            // Not a member
            assertThat(mds.findRole("coconut", user1).toCompletableFuture().join())
                    .isEqualTo(ProjectRole.NONE);
            // Removed project
            assertThat(mds.findRole("banana", user2).toCompletableFuture().join())
                    .isEqualTo(ProjectRole.NONE);

            final Map<String, ProjectRole> map = mds.findRoles(user2).toCompletableFuture().join();
            assertThat(map.size()).isEqualTo(2);
            assertThat(map.get("apple")).isEqualTo(ProjectRole.MEMBER);
            assertThat(map.get("coconut")).isEqualTo(ProjectRole.OWNER);

            // Repositories
            projectInfo = mds.addRepo("coconut", Author.DEFAULT, "pie")
                             .toCompletableFuture().join();
            projectInfo = mds.addRepo("coconut", Author.DEFAULT, "juice")
                             .toCompletableFuture().join();

            assertThat(projectInfo.name()).isEqualTo("coconut");
            assertThat(projectInfo.repos().size()).isEqualTo(2);
            assertThat(projectInfo.repos().get(0).name()).isEqualTo("juice");
            assertThat(projectInfo.repos().get(1).name()).isEqualTo("pie");

            // Tokens
            projectInfo = mds.addToken("apple", Author.DEFAULT, "jobs", "x", ProjectRole.OWNER)
                             .toCompletableFuture().join();
            projectInfo = mds.addToken("apple", Author.DEFAULT, "cook", "y", ProjectRole.MEMBER)
                             .toCompletableFuture().join();
            projectInfo = mds.addToken("apple", Author.DEFAULT, "ive", "z", ProjectRole.MEMBER)
                             .toCompletableFuture().join();

            assertThat(projectInfo.name()).isEqualTo("apple");
            assertThat(projectInfo.tokens().size()).isEqualTo(3);
            assertThat(projectInfo.tokens().get(0).appId()).isEqualTo("cook");
            assertThat(projectInfo.tokens().get(0).role()).isEqualTo(ProjectRole.MEMBER);
            assertThat(projectInfo.tokens().get(1).appId()).isEqualTo("ive");
            assertThat(projectInfo.tokens().get(1).role()).isEqualTo(ProjectRole.MEMBER);
            assertThat(projectInfo.tokens().get(2).appId()).isEqualTo("jobs");
            assertThat(projectInfo.tokens().get(2).role()).isEqualTo(ProjectRole.OWNER);

            final TokenInfo tokenInfo = mds.findToken("apple", "ive")
                                           .toCompletableFuture().join();
            assertThat(tokenInfo).isNotNull();
            assertThat(tokenInfo.appId()).isEqualTo("ive");
            assertThat(tokenInfo.secret()).isEqualTo("z");
        } finally {
            executor.stop();
        }
    }

    private static void createOneProjectAndValidate(MetadataService mds,
                                                    String projectName) {
        final ProjectInfo p1 = mds.createProject(projectName, Author.DEFAULT)
                                  .toCompletableFuture().join();
        assertThat(p1).isNotNull();
        assertThat(p1.name()).isEqualTo(projectName);
        assertThat(p1.creation().user()).isEqualTo(Author.DEFAULT.name());

        final ProjectInfo p2 = mds.getProject(projectName)
                                  .toCompletableFuture().join();
        assertThat(p2).isNotNull();
        assertThat(p2.name()).isEqualTo(projectName);
        assertThat(p2.creation().user()).isEqualTo(Author.DEFAULT.name());

        final List<ProjectInfo> list = mds.getAllProjects()
                                          .toCompletableFuture().join();
        assertThat(list.stream().anyMatch(e -> e.name().equals(projectName))).isTrue();
    }
}
