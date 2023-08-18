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
 * under the License.
 */

package com.linecorp.centraldogma.server.internal.mirror;

import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.PATH_CREDENTIALS;
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.PATH_MIRRORS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.server.internal.mirror.credential.NoneMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PasswordMirrorCredential;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryMetadataException;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.testing.internal.TestUtil;

class DefaultMetaRepositoryWithMirrorTest {

    private static final Change<JsonNode> UPSERT_CREDENTIALS = Change.ofJsonUpsert(
            PATH_CREDENTIALS,
            "[{" +
            "  \"type\": \"password\"," +
            "  \"id\": \"alice\"," +
            "  \"hostnamePatterns\": [ \"^foo\\\\.com$\" ]," +
            "  \"username\": \"alice\"," +
            "  \"password\": \"secret_a\"" +
            "},{" +
            "  \"type\": \"password\"," +
            "  \"hostnamePatterns\": [ \"^.*\\\\.com$\" ]," +
            "  \"username\": \"bob\"," +
            "  \"password\": \"secret_b\"" +
            "}]");

    private static final CronParser cronParser = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    @TempDir
    static File rootDir;

    private static ProjectManager pm;

    private Project project;
    private MetaRepository metaRepo;

    @BeforeAll
    static void init() {
        pm = new DefaultProjectManager(rootDir, ForkJoinPool.commonPool(),
                                       MoreExecutors.directExecutor(), NoopMeterRegistry.get(), null);
    }

    @AfterAll
    static void destroy() {
        pm.close(ShuttingDownException::new);
    }

    @BeforeEach
    void setUp(TestInfo testInfo) {
        final String name = TestUtil.normalizedDisplayName(testInfo);
        project = pm.create(name, Author.SYSTEM);
        metaRepo = project.metaRepo();
    }

    @Test
    void testEmptyMirrors() {
        // should return an empty result when both /credentials.json and /mirrors.json are non-existent.
        assertThat(metaRepo.mirrors()).isEmpty();

        // should return an empty result when /credentials.json exists and /mirrors.json does not.
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "", Change.ofJsonUpsert("/credentials.json", "[]"));
        assertThat(metaRepo.mirrors()).isEmpty();

        // should return an empty result when both /credentials.json and /mirrors.json exist.
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "", Change.ofJsonUpsert("/mirrors.json", "[]"));
        assertThat(metaRepo.mirrors()).isEmpty();
    }

    /**
     * Ensures a {@link RepositoryMetadataException} is raised when the mirror configuration is not valid.
     */
    @Test
    void testInvalidMirrors() {
        // not an array but an object
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert(PATH_MIRRORS, "{}")).join();
        assertThatThrownBy(() -> metaRepo.mirrors()).isInstanceOf(RepositoryMetadataException.class);

        // not an array but a value
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert(PATH_MIRRORS, "\"oops\"")).join();
        assertThatThrownBy(() -> metaRepo.mirrors()).isInstanceOf(RepositoryMetadataException.class);

        // an array that contains null.
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert(PATH_MIRRORS, "[ null ]")).join();
        assertThatThrownBy(() -> metaRepo.mirrors()).isInstanceOf(RepositoryMetadataException.class);
    }

    @Test
    void testMirror() {
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert(
                                PATH_MIRRORS,
                                "[{" +
                                "  \"enabled\": true," +
                                "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                                "  \"localRepo\": \"foo\"," +
                                "  \"localPath\": \"/mirrors/foo\"," +
                                "  \"remoteUri\": \"git+ssh://foo.com/foo.git\"" +
                                "},{" +
                                "  \"enabled\": true," +
                                "  \"schedule\": \"*/10 * * * * ?\"," +
                                "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                                "  \"localRepo\": \"bar\"," +
                                "  \"remoteUri\": \"git+ssh://bar.com/bar.git/some-path\"" +
                                "}, {" +
                                "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                                "  \"localRepo\": \"qux\"," +
                                "  \"remoteUri\": \"git+ssh://qux.net/qux.git#develop\"" +
                                "}, {" +
                                "  \"enabled\": false," + // Disabled
                                "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                                "  \"localRepo\": \"foo\"," +
                                "  \"localPath\": \"/mirrors/bar\"," +
                                "  \"remoteUri\": \"git+ssh://bar.com/bar.git\"" +
                                "}]"), UPSERT_CREDENTIALS).join();

        // When the mentioned repositories (foo and bar) do not exist,
        assertThat(metaRepo.mirrors()).isEmpty();

        project.repos().create("foo", Author.SYSTEM);
        project.repos().create("bar", Author.SYSTEM);
        project.repos().create("qux", Author.SYSTEM);

        final List<Mirror> mirrors = findMirrors();
        assertThat(mirrors.stream()
                          .map(m -> m.localRepo().name())
                          .collect(Collectors.toList())).containsExactly("bar", "foo", "qux");

        final Mirror foo = mirrors.get(1);
        final Mirror bar = mirrors.get(0);
        final Mirror qux = mirrors.get(2);

        assertThat(foo.direction()).isEqualTo(MirrorDirection.LOCAL_TO_REMOTE);
        assertThat(bar.direction()).isEqualTo(MirrorDirection.REMOTE_TO_LOCAL);
        assertThat(qux.direction()).isEqualTo(MirrorDirection.LOCAL_TO_REMOTE);

        assertThat(foo.schedule().equivalent(cronParser.parse("0 * * * * ?"))).isTrue();
        assertThat(bar.schedule().equivalent(cronParser.parse("*/10 * * * * ?"))).isTrue();
        assertThat(qux.schedule().equivalent(cronParser.parse("0 * * * * ?"))).isTrue();

        assertThat(foo.localPath()).isEqualTo("/mirrors/foo/");
        assertThat(bar.localPath()).isEqualTo("/");
        assertThat(qux.localPath()).isEqualTo("/");

        assertThat(foo.remoteRepoUri().toString()).isEqualTo("git+ssh://foo.com/foo.git");
        assertThat(bar.remoteRepoUri().toString()).isEqualTo("git+ssh://bar.com/bar.git");
        assertThat(qux.remoteRepoUri().toString()).isEqualTo("git+ssh://qux.net/qux.git");

        assertThat(foo.remotePath()).isEqualTo("/");
        assertThat(bar.remotePath()).isEqualTo("/some-path/");
        assertThat(qux.remotePath()).isEqualTo("/");

        assertThat(foo.remoteBranch()).isNull();
        assertThat(bar.remoteBranch()).isNull();
        assertThat(qux.remoteBranch()).isEqualTo("develop");

        // Ensure the credentials are loaded correctly.

        //// Should be matched by 'alice' credential.
        assertThat(foo.credential()).isInstanceOf(PasswordMirrorCredential.class);
        //// Should be matched by 'bob' credential.
        assertThat(bar.credential()).isInstanceOf(PasswordMirrorCredential.class);
        //// Should be matched by no credential.
        assertThat(qux.credential()).isInstanceOf(NoneMirrorCredential.class);

        final PasswordMirrorCredential fooCredential = (PasswordMirrorCredential) foo.credential();
        final PasswordMirrorCredential barCredential = (PasswordMirrorCredential) bar.credential();

        assertThat(fooCredential.username()).isEqualTo("alice");
        assertThat(fooCredential.password()).isEqualTo("secret_a");
        assertThat(barCredential.username()).isEqualTo("bob");
        assertThat(barCredential.password()).isEqualTo("secret_b");
    }

    @Test
    void testMirrorWithCredentialId() {
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert(
                                PATH_MIRRORS,
                                "[{" +
                                // type isn't used from https://github.com/line/centraldogma/pull/836 but
                                // left for backward compatibility check.
                                "  \"type\": \"single\"," +
                                "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                                "  \"localRepo\": \"qux\"," +
                                "  \"remoteUri\": \"git+ssh://qux.net/qux.git\"," +
                                "  \"credentialId\": \"alice\"" +
                                "}]"), UPSERT_CREDENTIALS).join();

        project.repos().create("qux", Author.SYSTEM);

        final List<Mirror> mirrors = findMirrors();
        assertThat(mirrors).hasSize(1);

        final Mirror m = mirrors.get(0);
        assertThat(m.localRepo().name()).isEqualTo("qux");
        assertThat(m.credential()).isInstanceOf(PasswordMirrorCredential.class);
        assertThat(((PasswordMirrorCredential) m.credential()).username()).isEqualTo("alice");
    }

    private List<Mirror> findMirrors() {
        // Get the mirror list and sort it by localRepo name alphabetically for easier testing.
        return metaRepo.mirrors().stream()
                       .sorted(Comparator.comparing(m -> m.localRepo().name()))
                       .collect(Collectors.toList());
    }
}
