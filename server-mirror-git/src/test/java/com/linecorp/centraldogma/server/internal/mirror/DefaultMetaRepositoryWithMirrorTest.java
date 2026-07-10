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
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialFile;
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static com.linecorp.centraldogma.server.internal.storage.repository.MirrorConfig.DEFAULT_SCHEDULE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.MirrorRequest;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.internal.credential.SshKeyCredential;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryMetadataException;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;
import com.linecorp.centraldogma.testing.internal.TestUtil;

class DefaultMetaRepositoryWithMirrorTest {

    private static final CronParser cronParser = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    private static ProjectManager pm;

    @RegisterExtension
    static final ProjectManagerExtension pmExtension = new ProjectManagerExtension();

    private Project project;
    private MetaRepository metaRepo;

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
                        Change.ofJsonUpsert("/repos/repo/mirrors/foo.json", "[]")).join();
        assertThatThrownBy(() -> metaRepo.mirror("repo", "foo").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RepositoryMetadataException.class);

        // not an object but a value
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert("/repos/repo/mirrors/bar.json", "\"oops\"")).join();
        assertThatThrownBy(() -> metaRepo.mirror("repo", "bar").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RepositoryMetadataException.class);

        // an empty object
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "",
                        Change.ofJsonUpsert("/repos/repo/mirrors/qux.json", "{}")).join();
        assertThatThrownBy(() -> metaRepo.mirror("repo", "qux").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RepositoryMetadataException.class);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void testMirror(boolean useRawApi) {
        if (useRawApi) {
            final List<Change<?>> mirrors = ImmutableList.of(
                    Change.ofJsonUpsert(
                            "/repos/repo/mirrors/foo.json",
                            '{' +
                            "  \"id\": \"foo\"," +
                            "  \"enabled\": true," +
                            "  \"schedule\": \"" + DEFAULT_SCHEDULE + "\"," +
                            "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                            "  \"localRepo\": \"foo\"," +
                            "  \"localPath\": \"/mirrors/foo\"," +
                            "  \"remoteUri\": \"git+ssh://foo.com/foo.git\"," +
                            "  \"credentialName\": \"" + credentialName(project.name(), "alice") +
                            '"' +
                            '}'),
                    Change.ofJsonUpsert(
                            "/repos/repo/mirrors/bar.json",
                            '{' +
                            "  \"id\": \"bar\"," +
                            "  \"enabled\": true," +
                            "  \"schedule\": \"0 */10 * * * ?\"," +
                            "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                            "  \"localRepo\": \"bar\"," +
                            "  \"remoteUri\": \"git+ssh://bar.com/bar.git/some-path#develop\"," +
                            "  \"credentialName\": \"" + credentialName(project.name(), "bob") +
                            '"' +
                            '}'));
            metaRepo.commit(Revision.HEAD, 0L, Author.SYSTEM, "", mirrors).join();
            metaRepo.commit(Revision.HEAD, 0L, Author.SYSTEM, "", upsertRawCredentials(project.name())).join();
        } else {
            final List<MirrorRequest> mirrors = ImmutableList.of(
                    new MirrorRequest("foo", true, project.name(), DEFAULT_SCHEDULE, "LOCAL_TO_REMOTE", "foo",
                                      "/mirrors/foo", "git+ssh", "foo.com/foo.git", "", "", null,
                                      credentialName(project.name(), "alice"), null),
                    new MirrorRequest("bar", true, project.name(), "0 */10 * * * ?", "REMOTE_TO_LOCAL", "bar",
                                      "", "git+ssh", "bar.com/bar.git", "/some-path", "develop", null,
                                      credentialName(project.name(), "bob"), null));
            for (Credential credential : credentials(project.name())) {
                final Command<Revision> command =
                        metaRepo.createCredentialPushCommand(credential, Author.SYSTEM, false).join();
                pmExtension.executor().execute(command).join();
            }
            for (MirrorRequest mirror : mirrors) {
                final Command<Revision> command =
                        metaRepo.createMirrorPushCommand(mirror.localRepo(), mirror, Author.SYSTEM,
                                                         null, false).join();
                pmExtension.executor().execute(command).join();
            }
        }

        // When the mentioned repositories (foo and bar) do not exist,
        assertThat(metaRepo.mirrors().join()).isEmpty();

        project.repos().create("foo", Author.SYSTEM);
        project.repos().create("bar", Author.SYSTEM);

        final List<Mirror> mirrors = findMirrors();
        assertThat(mirrors.stream()
                          .map(m -> m.localRepo().name())
                          .collect(toImmutableList())).containsExactly("bar", "foo");

        final Mirror foo = mirrors.get(1);
        final Mirror bar = mirrors.get(0);

        assertThat(foo.direction()).isEqualTo(MirrorDirection.LOCAL_TO_REMOTE);
        assertThat(bar.direction()).isEqualTo(MirrorDirection.REMOTE_TO_LOCAL);

        assertThat(foo.schedule().equivalent(cronParser.parse("0 * * * * ?"))).isTrue();
        assertThat(bar.schedule().equivalent(cronParser.parse("0 */10 * * * ?"))).isTrue();

        assertThat(foo.localPath()).isEqualTo("/mirrors/foo/");
        assertThat(bar.localPath()).isEqualTo("/");

        assertThat(foo.remoteRepoUri().toString()).isEqualTo("git+ssh://foo.com/foo.git");
        assertThat(bar.remoteRepoUri().toString()).isEqualTo("git+ssh://bar.com/bar.git");

        assertThat(foo.remotePath()).isEqualTo("/");
        assertThat(bar.remotePath()).isEqualTo("/some-path/");

        assertThat(foo.remoteBranch()).isEmpty();
        assertThat(bar.remoteBranch()).isEqualTo("develop");

        // Ensure the credentials are loaded correctly.

        //// Should be matched by 'alice' credential.
        assertThat(foo.credential()).isInstanceOf(SshKeyCredential.class);
        //// Should be matched by 'bob' credential.
        assertThat(bar.credential()).isInstanceOf(SshKeyCredential.class);

        final SshKeyCredential fooCredential = (SshKeyCredential) foo.credential();
        final SshKeyCredential barCredential = (SshKeyCredential) bar.credential();

        assertThat(fooCredential.username()).isEqualTo("alice");
        assertThat(barCredential.username()).isEqualTo("bob");
    }

    @Test
    void testMirrorWithCredentialId() {
        final List<Change<?>> changes =
                ImmutableList.<Change<?>>builder()
                             .add(Change.ofJsonUpsert(
                                     "/repos/repo/mirrors/foo.json",
                                     '{' +
                                     "  \"id\": \"foo\"," +
                                     // type isn't used from https://github.com/line/centraldogma/pull/836 but
                                     // left for backward compatibility check.
                                     "  \"type\": \"single\"," +
                                     "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                                     "  \"localRepo\": \"qux\"," +
                                     "  \"remoteUri\": \"git+ssh://qux.net/qux.git\"," +
                                     "  \"credentialName\": \"" +
                                     credentialName(project.name(), "alice") + '"' +
                                     '}'))
                             .addAll(upsertRawCredentials(project.name()))
                             .build();
        metaRepo.commit(Revision.HEAD, 0, Author.SYSTEM, "", changes).join();

        project.repos().create("qux", Author.SYSTEM);

        final List<Mirror> mirrors = findMirrors();
        assertThat(mirrors).hasSize(1);

        final Mirror m = mirrors.get(0);
        assertThat(m.localRepo().name()).isEqualTo("qux");
        assertThat(m.credential()).isInstanceOf(SshKeyCredential.class);
        assertThat(((SshKeyCredential) m.credential()).username()).isEqualTo("alice");
    }

    @Test
    void xdsMirrorWithNonRootLocalPath_isRejected() {
        final MirrorRequest badMirror = new MirrorRequest(
                "xds-mirror", true, "@xds", DEFAULT_SCHEDULE, "REMOTE_TO_LOCAL", "some-group",
                "/clusters/", "git+ssh", "git.example.com/org/repo.git", "/", "main", null, "", null);
        assertThatThrownBy(() ->
                metaRepo.createMirrorPushCommand("some-group", badMirror, Author.SYSTEM, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localPath");
    }

    @Test
    void xdsMirrorWithRootLocalPath_isAccepted() {
        final MirrorRequest validMirror = new MirrorRequest(
                "xds-mirror", true, "@xds", DEFAULT_SCHEDULE, "REMOTE_TO_LOCAL", "some-group",
                "/", "git+ssh", "git.example.com/org/repo.git", "/", "main", null, "", null);
        assertThatCode(() ->
                metaRepo.createMirrorPushCommand("some-group", validMirror, Author.SYSTEM, null, false))
                .doesNotThrowAnyException();
    }

    private List<Mirror> findMirrors() {
        // Get the mirror list and sort it by localRepo name alphabetically for easier testing.
        return metaRepo.mirrors().join().stream()
                       .sorted(Comparator.comparing(m -> m.localRepo().name()))
                       .collect(toImmutableList());
    }

    private static List<Change<?>> upsertRawCredentials(String projectName) {
        final String aliceCredential = credentialName(projectName, "alice");
        final String bobCredential = credentialName(projectName, "bob");
        final String dummyKey = "-----BEGIN RSA PRIVATE KEY-----\\ntest\\n-----END RSA PRIVATE KEY-----";
        return ImmutableList.of(
                Change.ofJsonUpsert(
                        credentialFile(aliceCredential),
                        '{' +
                        "  \"name\": \"" + aliceCredential + "\"," +
                        "  \"type\": \"SSH_KEY\"," +
                        "  \"username\": \"alice\"," +
                        "  \"publicKey\": \"ssh-rsa AAAA\"," +
                        "  \"privateKey\": \"" + dummyKey + '"' +
                        '}'),
                Change.ofJsonUpsert(
                        credentialFile(bobCredential),
                        '{' +
                        "  \"name\": \"" + bobCredential + "\"," +
                        "  \"type\": \"SSH_KEY\"," +
                        "  \"username\": \"bob\"," +
                        "  \"publicKey\": \"ssh-rsa BBBB\"," +
                        "  \"privateKey\": \"" + dummyKey + '"' +
                        '}'));
    }

    private static List<Credential> credentials(String projectName) {
        final String dummyKey = "-----BEGIN RSA PRIVATE KEY-----\ntest\n-----END RSA PRIVATE KEY-----";
        return ImmutableList.of(
                new SshKeyCredential(credentialName(projectName, "alice"), "alice",
                                     "ssh-rsa AAAA", dummyKey, null),
                new SshKeyCredential(credentialName(projectName, "bob"), "bob",
                                     "ssh-rsa BBBB", dummyKey, null));
    }
}
