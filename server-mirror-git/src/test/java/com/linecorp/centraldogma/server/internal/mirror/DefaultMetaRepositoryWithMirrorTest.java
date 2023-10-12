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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.server.internal.storage.repository.MirrorConfig.DEFAULT_SCHEDULE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.MirrorDto;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.internal.mirror.credential.NoneMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PasswordMirrorCredential;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryMetadataException;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;
import com.linecorp.centraldogma.testing.internal.TestUtil;

class DefaultMetaRepositoryWithMirrorTest {

    private static final List<Change<?>> UPSERT_RAW_CREDENTIALS = ImmutableList.of(
            Change.ofJsonUpsert(
                    "/credentials/alice.json",
                    '{' +
                    "  \"id\": \"alice\"," +
                    "  \"type\": \"password\"," +
                    "  \"hostnamePatterns\": [ \"^foo\\\\.com$\" ]," +
                    "  \"username\": \"alice\"," +
                    "  \"password\": \"secret_a\"" +
                    '}'),
            Change.ofJsonUpsert(
                    "/credentials/bob.json",
                    '{' +
                    "  \"id\": \"bob\"," +
                    "  \"type\": \"password\"," +
                    "  \"hostnamePatterns\": [ \"^.*\\\\.com$\" ]," +
                    "  \"username\": \"bob\"," +
                    "  \"password\": \"secret_b\"" +
                    '}'));

    private static final List<MirrorCredential> CREDENTIALS = ImmutableList.of(
            new PasswordMirrorCredential(
                    "alice", true, ImmutableList.of(Pattern.compile("^foo\\.com$")),
                    "alice", "secret_a"),
            new PasswordMirrorCredential(
                    "bob", true, ImmutableList.of(Pattern.compile("^.*\\.com$")),
                    "bob", "secret_b"));

    private static final CronParser cronParser = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    @TempDir
    static File rootDir;

    private static ProjectManager pm;

    private Project project;
    private MetaRepository metaRepo;
    static final ProjectManagerExtension pmExtension = new ProjectManagerExtension();

    @BeforeAll
    static void init() {
        pm = pmExtension.projectManager();
    }

    @BeforeEach
    void setUp(TestInfo testInfo) {
        final String name = TestUtil.normalizedDisplayName(testInfo);
        project = pm.create(name, Author.SYSTEM);
        metaRepo = project.metaRepo();
    }

    @Test
    void testEmptyMirrors() {
        // should return an empty result when both /credentials/ and /mirrors/ are non-existent.
        assertThat(metaRepo.mirrors().join()).isEmpty();
    }

    /**
     * Ensures a {@link RepositoryMetadataException} is raised when the mirror configuration is not valid.
     */
    @Test
    void testInvalidMirrors() {
        // not an object but an array
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert("/mirrors/foo.json", "[]")).join();
        assertThatThrownBy(() -> metaRepo.mirror("foo").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RepositoryMetadataException.class);

        // not an object but a value
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert("/mirrors/bar.json", "\"oops\"")).join();
        assertThatThrownBy(() -> metaRepo.mirror("bar").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RepositoryMetadataException.class);

        // an empty object
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert("/mirrors/qux.json", "{}")).join();
        assertThatThrownBy(() -> metaRepo.mirror("qux").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RepositoryMetadataException.class);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void testMirror(boolean useRawApi) {
        if (useRawApi) {
            final List<Change<?>> mirrors = ImmutableList.of(
                    Change.ofJsonUpsert(
                            "/mirrors/foo.json",
                            '{' +
                            "  \"id\": \"foo\"," +
                            "  \"enabled\": true," +
                            "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                            "  \"localRepo\": \"foo\"," +
                            "  \"localPath\": \"/mirrors/foo\"," +
                            "  \"remoteUri\": \"git+ssh://foo.com/foo.git\"," +
                            "  \"credentialId\": \"alice\"" +
                            '}'),
                    Change.ofJsonUpsert(
                            "/mirrors/bar.json",
                            '{' +
                            "  \"id\": \"bar\"," +
                            "  \"enabled\": true," +
                            "  \"schedule\": \"0 */10 * * * ?\"," +
                            "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                            "  \"localRepo\": \"bar\"," +
                            "  \"remoteUri\": \"git+ssh://bar.com/bar.git/some-path\"," +
                            " \"credentialId\": \"bob\"" +
                            '}'),
                    Change.ofJsonUpsert(
                            "/mirrors/qux.json",
                            '{' +
                            "  \"id\": \"qux\"," +
                            "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                            "  \"localRepo\": \"qux\"," +
                            "  \"remoteUri\": \"git+ssh://qux.net/qux.git#develop\"" +
                            // No credential will be chosen.
                            '}'),
                    Change.ofJsonUpsert(
                            "/mirrors/foo-bar.json",
                            '{' +
                            "  \"id\": \"foo-bar\"," +
                            "  \"enabled\": false," + // Disabled
                            "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                            "  \"localRepo\": \"foo\"," +
                            "  \"localPath\": \"/mirrors/bar\"," +
                            "  \"remoteUri\": \"git+ssh://bar.com/bar.git\"" +
                            // credentialId 'bob' will be chosen.
                            '}'));
            metaRepo.commit(Revision.HEAD, 0L, Author.SYSTEM, "", mirrors).join();
            metaRepo.commit(Revision.HEAD, 0L, Author.SYSTEM, "", UPSERT_RAW_CREDENTIALS).join();
        } else {
            final List<MirrorDto> mirrors = ImmutableList.of(
                    new MirrorDto("foo", true, project.name(), DEFAULT_SCHEDULE, "LOCAL_TO_REMOTE", "foo",
                                  "/mirrors/foo", "git+ssh", "foo.com/foo.git", "", "", null, "alice"),
                    new MirrorDto("bar", true, project.name(), "0 */10 * * * ?", "REMOTE_TO_LOCAL", "bar",
                                  "", "git+ssh", "bar.com/bar.git", "/some-path", "", null, "bob"),
                    new MirrorDto("qux", true, project.name(), DEFAULT_SCHEDULE, "LOCAL_TO_REMOTE", "qux",
                                  "", "git+ssh", "qux.net/qux.git", "", "develop", null, ""),
                    new MirrorDto("foo-bar", false, project.name(), DEFAULT_SCHEDULE, "LOCAL_TO_REMOTE", "foo",
                                  "/mirrors/bar", "git+ssh", "bar.com/bar.git", "", "", null, "bob"));
            for (MirrorCredential credential : CREDENTIALS) {
                metaRepo.saveCredential(credential, Author.SYSTEM);
            }
            for (MirrorDto mirror : mirrors) {
                final Command<CommitResult> command = metaRepo.createCommand(mirror, Author.SYSTEM, false)
                                                              .join();
                pmExtension.executor().execute(command).join();
            }
        }

        // When the mentioned repositories (foo and bar) do not exist,
        assertThat(metaRepo.mirrors().join()).isEmpty();

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
        assertThat(bar.schedule().equivalent(cronParser.parse("0 */10 * * * ?"))).isTrue();
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

        assertThat(foo.remoteBranch()).isEmpty();
        assertThat(bar.remoteBranch()).isEmpty();
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
        final List<Change<?>> changes =
                ImmutableList.<Change<?>>builder()
                             .add(Change.ofJsonUpsert(
                                     "/mirrors/foo.json",
                                     '{' +
                                     "  \"id\": \"foo\"," +
                                     // type isn't used from https://github.com/line/centraldogma/pull/836 but
                                     // left for backward compatibility check.
                                     "  \"type\": \"single\"," +
                                     "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                                     "  \"localRepo\": \"qux\"," +
                                     "  \"remoteUri\": \"git+ssh://qux.net/qux.git\"," +
                                     "  \"credentialId\": \"alice\"" +
                                     '}'))
                             .addAll(UPSERT_RAW_CREDENTIALS)
                             .build();
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "", changes).join();

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
        return metaRepo.mirrors().join().stream()
                       .sorted(Comparator.comparing(m -> m.localRepo().name()))
                       .collect(toImmutableList());
    }
}
