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

package com.linecorp.centraldogma.server.internal.mirror;

import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_HTTP;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_HTTPS;

import java.io.File;
import java.net.URI;
import java.time.Instant;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import com.cronutils.model.Cron;

import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.internal.credential.AccessTokenCredential;
import com.linecorp.centraldogma.server.internal.credential.PasswordCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class DefaultGitMirror extends AbstractGitMirror {

    private static final Consumer<TransportCommand<?, ?>> NOOP_CONFIGURATOR = command -> {};

    DefaultGitMirror(String id, boolean enabled, @Nullable Cron schedule, MirrorDirection direction,
                     Credential credential, Repository localRepo, String localPath,
                     URI remoteRepoUri, String remotePath, String remoteBranch,
                     @Nullable String gitignore, @Nullable String zone) {
        super(id, enabled, schedule, direction, credential, localRepo, localPath, remoteRepoUri, remotePath,
              remoteBranch, gitignore, zone);
    }

    @Override
    protected MirrorResult mirrorLocalToRemote(File workDir, int maxNumFiles, long maxNumBytes,
                                               Instant triggeredTime)
            throws Exception {
        try (GitWithAuth git = openGit(workDir, transportCommandConfigurator())) {
            return mirrorLocalToRemote(git, maxNumFiles, maxNumBytes, triggeredTime);
        }
    }

    private Consumer<TransportCommand<?, ?>> transportCommandConfigurator() {
        final Credential c = credential();
        switch (remoteRepoUri().getScheme()) {
            case SCHEME_GIT_HTTP:
            case SCHEME_GIT_HTTPS:
                if (c instanceof PasswordCredential) {
                    final PasswordCredential cred = (PasswordCredential) c;
                    return command -> command.setCredentialsProvider(
                            new UsernamePasswordCredentialsProvider(cred.username(), cred.password()));
                }
                if (c instanceof AccessTokenCredential) {
                    final AccessTokenCredential cred = (AccessTokenCredential) c;
                    return command -> command.setCredentialsProvider(
                            new UsernamePasswordCredentialsProvider("token", cred.accessToken()));
                }
                break;
        }
        return NOOP_CONFIGURATOR;
    }

    @Override
    protected MirrorResult mirrorRemoteToLocal(File workDir, CommandExecutor executor,
                                               int maxNumFiles, long maxNumBytes, Instant triggeredTime)
            throws Exception {
        try (GitWithAuth git = openGit(workDir, transportCommandConfigurator())) {
            return mirrorRemoteToLocal(git, executor, maxNumFiles, maxNumBytes, triggeredTime);
        }
    }

    private GitWithAuth openGit(File workDir, Consumer<TransportCommand<?, ?>> configurator) throws Exception {
        final String scheme = remoteRepoUri().getScheme();
        final String jGitUri;
        if (scheme.startsWith("git+")) {
            // Convert the remoteRepoUri into the URI accepted by jGit by removing the 'git+' prefix.
            jGitUri = remoteRepoUri().toASCIIString().substring(4);
        } else {
            jGitUri = remoteRepoUri().toASCIIString();
        }
        return openGit(workDir, new URIish(jGitUri), configurator);
    }
}
