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
import java.time.ZonedDateTime;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

class MirrorTest {

    private static final Cron EVERY_MINUTE = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)).parse("0 * * * * ?");

    @Test
    void testGitMirror() {
        // Simplest possible form
        assertMirror("git://a.com/b.git", GitMirror.class,
                     "git://a.com/b.git", "/", "master");

        // Non-default port number
        assertMirror("git+ssh://a.com:8022/b.git", GitMirror.class,
                     "git+ssh://a.com:8022/b.git", "/", "master");

        // Non-default remotePath
        assertMirror("git+http://a.com/b.git/c", GitMirror.class,
                     "git+http://a.com/b.git", "/c/", "master");

        // Non-default remoteBranch
        assertMirror("git+https://a.com/b.git#develop", GitMirror.class,
                     "git+https://a.com/b.git", "/", "develop");

        // Non-default remotePath and remoteBranch
        assertMirror("git+ssh://a.com/b.git/c#develop", GitMirror.class,
                     "git+ssh://a.com/b.git", "/c/", "develop");

        // remoteUri must contain the '.git' suffix.
        assertThatThrownBy(() -> newMirror("git://a.com/b", GitMirror.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newMirror("git://a.com/b.dogma", GitMirror.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

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
        assertThatThrownBy(() -> newMirror("magma://a.com/b.magma", Mirror.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newMirror("git+foo://a.com/b.git", Mirror.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jitter() {
        final AbstractMirror mirror = newMirror("git://a.com/b.git", AbstractMirror.class);

        assertThat(mirror.schedule()).isSameAs(EVERY_MINUTE);

        // When jitter is less then the configured interval.
        assertThat(mirror.nextExecutionTime(ZonedDateTime.parse("2018-05-15T19:20:00+00:00"), 1000))
                .isEqualTo(ZonedDateTime.parse("2018-05-15T19:20:01+00:00"));
        assertThat(mirror.nextExecutionTime(ZonedDateTime.parse("2018-05-15T19:20:01+00:00"), 1000))
                .isEqualTo(ZonedDateTime.parse("2018-05-15T19:21:01+00:00"));

        // When jitter is equal to the configured interval.
        assertThat(mirror.nextExecutionTime(ZonedDateTime.parse("2018-05-15T19:20:00+00:00"), 60000))
                .isEqualTo(ZonedDateTime.parse("2018-05-15T19:21:00+00:00"));
        assertThat(mirror.nextExecutionTime(ZonedDateTime.parse("2018-05-15T19:21:30+00:00"), 60000))
                .isEqualTo(ZonedDateTime.parse("2018-05-15T19:22:00+00:00"));

        // When jitter is more than the configured interval.
        assertThat(mirror.nextExecutionTime(ZonedDateTime.parse("2018-05-15T19:20:00+00:00"), 90000))
                .isEqualTo(ZonedDateTime.parse("2018-05-15T19:20:30+00:00"));
        assertThat(mirror.nextExecutionTime(ZonedDateTime.parse("2018-05-15T19:20:30+00:00"), 90000))
                .isEqualTo(ZonedDateTime.parse("2018-05-15T19:21:30+00:00"));
    }

    private static <T extends Mirror> T assertMirror(String remoteUri, Class<T> mirrorType,
                                                     String expectedRemoteRepoUri,
                                                     String expectedRemotePath,
                                                     @Nullable String expectedRemoteBranch) {
        final T m = newMirror(remoteUri, mirrorType);
        assertThat(m.remoteRepoUri().toString()).isEqualTo(expectedRemoteRepoUri);
        assertThat(m.remotePath()).isEqualTo(expectedRemotePath);
        assertThat(m.remoteBranch()).isEqualTo(expectedRemoteBranch);
        return m;
    }

    private static <T extends Mirror> T newMirror(String remoteUri, Class<T> mirrorType) {
        return newMirror(remoteUri, EVERY_MINUTE, mirrorType);
    }

    private static <T extends Mirror> T newMirror(String remoteUri, Cron schedule, Class<T> mirrorType) {
        final MirrorCredential credential = mock(MirrorCredential.class);
        final Repository localRepo = mock(Repository.class);
        final Project localProj = mock(Project.class);
        when(localRepo.parent()).thenReturn(localProj);
        when(localProj.name()).thenReturn("foo");
        when(localRepo.name()).thenReturn("bar");
        final Mirror mirror = Mirror.of(schedule, MirrorDirection.LOCAL_TO_REMOTE,
                                        credential, localRepo, "/", URI.create(remoteUri));

        assertThat(mirror).isInstanceOf(mirrorType);
        assertThat(mirror.direction()).isEqualTo(MirrorDirection.LOCAL_TO_REMOTE);
        assertThat(mirror.credential()).isSameAs(credential);
        assertThat(mirror.localRepo()).isSameAs(localRepo);
        assertThat(mirror.localPath()).isEqualTo("/");

        @SuppressWarnings("unchecked")
        final T castMirror = (T) mirror;
        return castMirror;
    }
}
