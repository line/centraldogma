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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

import com.linecorp.centraldogma.server.internal.storage.repository.CentralDogmaMirrorProvider;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorContext;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

class CentralDogmaMirrorTest {

    static final Cron EVERY_MINUTE = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)).parse("0 * * * * ?");

    @Test
    void testCentralDogmaMirror() {
        CentralDogmaMirror m;

        // Simplest possible form
        m = assertMirror("dogma://a.com/b/c.dogma", CentralDogmaMirror.class,
                         "dogma://a.com/b/c.dogma", "/", null);
        assertThat(m.remoteProject()).isEqualTo("b");
        assertThat(m.remoteRepo()).isEqualTo("c");

        // Non-default port number
        m = assertMirror("dogma://a.com:1234/b/c.dogma", CentralDogmaMirror.class,
                         "dogma://a.com:1234/b/c.dogma", "/", null);
        assertThat(m.remoteProject()).isEqualTo("b");
        assertThat(m.remoteRepo()).isEqualTo("c");

        // Non-default remotePath
        m = assertMirror("dogma://a.com/b/c.dogma/d", CentralDogmaMirror.class,
                         "dogma://a.com/b/c.dogma", "/d/", null);
        assertThat(m.remoteProject()).isEqualTo("b");
        assertThat(m.remoteRepo()).isEqualTo("c");

        // Non-default remoteBranch (should be ignored because Central Dogma has no notion of branch.)
        m = assertMirror("dogma://a.com/b/c.dogma#develop", CentralDogmaMirror.class,
                         "dogma://a.com/b/c.dogma", "/", null);
        assertThat(m.remoteProject()).isEqualTo("b");
        assertThat(m.remoteRepo()).isEqualTo("c");

        // Non-default remotePath and remoteBranch
        m = assertMirror("dogma://a.com/b/c.dogma/d#develop", CentralDogmaMirror.class,
                         "dogma://a.com/b/c.dogma", "/d/", null);
        assertThat(m.remoteProject()).isEqualTo("b");
        assertThat(m.remoteRepo()).isEqualTo("c");

        // remoteUri must contain the '.dogma' suffix.
        assertThatThrownBy(() -> newMirror("dogma://a.com/b/c", CentralDogmaMirror.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newMirror("dogma://a.com/b/c.git", CentralDogmaMirror.class))
                .isInstanceOf(IllegalArgumentException.class);

        // remoteUri must have two path components (project name and repository name)
        assertThatThrownBy(() -> newMirror("dogma://a.com/b.dogma", CentralDogmaMirror.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newMirror("dogma://a.com/b/c/d.dogma", CentralDogmaMirror.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testUnknownScheme() {
        assertMirrorNull("magma://a.com/b.magma");
        assertMirrorNull("git+foo://a.com/b.git");
    }

    private static <T extends Mirror> T assertMirror(String remoteUri, Class<T> mirrorType,
                                                     String expectedRemoteRepoUri,
                                                     String expectedRemotePath,
                                                     @Nullable String expectedRemoteBranch) {
        final Repository repository = mock(Repository.class);
        final Project project = mock(Project.class);
        when(repository.parent()).thenReturn(project);
        when(repository.name()).thenReturn("bar");
        when(project.name()).thenReturn("foo");

        final T m = newMirror(remoteUri, EVERY_MINUTE, repository, mirrorType);
        assertThat(m.remoteRepoUri().toString()).isEqualTo(expectedRemoteRepoUri);
        assertThat(m.remotePath()).isEqualTo(expectedRemotePath);
        assertThat(m.remoteBranch()).isEqualTo(expectedRemoteBranch);
        return m;
    }

    static <T extends Mirror> T newMirror(String remoteUri, Class<T> mirrorType) {
        return newMirror(remoteUri, EVERY_MINUTE, mock(Repository.class), mirrorType);
    }

    static <T extends Mirror> T newMirror(String remoteUri, Cron schedule,
                                          Repository repository, Class<T> mirrorType) {
        final MirrorCredential credential = mock(MirrorCredential.class);
        final String mirrorId = "mirror-id";
        final Mirror mirror =
                new CentralDogmaMirrorProvider().newMirror(
                        new MirrorContext(mirrorId, true, schedule, MirrorDirection.LOCAL_TO_REMOTE,
                                          credential, repository, "/", URI.create(remoteUri), null));

        assertThat(mirror).isInstanceOf(mirrorType);
        assertThat(mirror.id()).isEqualTo(mirrorId);
        assertThat(mirror.direction()).isEqualTo(MirrorDirection.LOCAL_TO_REMOTE);
        assertThat(mirror.credential()).isSameAs(credential);
        assertThat(mirror.localRepo()).isSameAs(repository);
        assertThat(mirror.localPath()).isEqualTo("/");

        @SuppressWarnings("unchecked")
        final T castMirror = (T) mirror;
        return castMirror;
    }

    static void assertMirrorNull(String remoteUri) {
        final MirrorCredential credential = mock(MirrorCredential.class);
        final Mirror mirror = new CentralDogmaMirrorProvider().newMirror(
                new MirrorContext("mirror-id", true, EVERY_MINUTE, MirrorDirection.LOCAL_TO_REMOTE,
                                  credential, mock(Repository.class), "/", URI.create(remoteUri), null));
        assertThat(mirror).isNull();
    }
}
