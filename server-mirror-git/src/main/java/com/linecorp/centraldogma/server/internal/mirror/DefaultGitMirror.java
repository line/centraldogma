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
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import com.cronutils.model.Cron;

import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.mirror.credential.AccessTokenMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PasswordMirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class DefaultGitMirror extends AbstractGitMirror {

    private static final Consumer<TransportCommand<?, ?>> NOOP_CONFIGURATOR = command -> {};

    DefaultGitMirror(Cron schedule, MirrorDirection direction, MirrorCredential credential,
                     Repository localRepo, String localPath,
                     URI remoteRepoUri, String remotePath, @Nullable String remoteBranch,
                     @Nullable String gitignore) {
        super(schedule, direction, credential, localRepo, localPath, remoteRepoUri, remotePath, remoteBranch,
              gitignore);
    }

    @Override
    protected void mirrorLocalToRemote(File workDir, int maxNumFiles, long maxNumBytes) throws Exception {
        try (GitWithAuth git = openGit(workDir)) {
            mirrorLocalToRemote(git, maxNumFiles, maxNumBytes, transportCommandConfigurator());
        }
    }

    private Consumer<TransportCommand<?, ?>> transportCommandConfigurator() {
        final MirrorCredential c = credential();
        switch (remoteRepoUri().getScheme()) {
            case SCHEME_GIT_HTTP:
            case SCHEME_GIT_HTTPS:
                if (c instanceof PasswordMirrorCredential) {
                    final PasswordMirrorCredential cred = (PasswordMirrorCredential) c;
                    return command -> command.setCredentialsProvider(
                            new UsernamePasswordCredentialsProvider(cred.username(), cred.password()));
                }
                if (c instanceof AccessTokenMirrorCredential) {
                    final AccessTokenMirrorCredential cred = (AccessTokenMirrorCredential) c;
                    return command -> command.setCredentialsProvider(
                            new UsernamePasswordCredentialsProvider("token", cred.accessToken()));
                }
                break;
        }
        return NOOP_CONFIGURATOR;
    }

    @Override
    protected void mirrorRemoteToLocal(File workDir, CommandExecutor executor,
                                       int maxNumFiles, long maxNumBytes) throws Exception {
        try (GitWithAuth git = openGit(workDir)) {
            mirrorRemoteToLocal(git, executor, maxNumFiles, maxNumBytes, transportCommandConfigurator());
        }
    }

    private GitWithAuth openGit(File workDir) throws Exception {
        final String scheme = remoteRepoUri().getScheme();
        final String jGitUri;
        if (scheme.startsWith("git+")) {
            // Convert the remoteRepoUri into the URI accepted by jGit by removing the 'git+' prefix.
            jGitUri = remoteRepoUri().toASCIIString().substring(4);
        } else {
            jGitUri = remoteRepoUri().toASCIIString();
        }
        return openGit(workDir, jGitUri, new URIish(jGitUri));
    }
}
