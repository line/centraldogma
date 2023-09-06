/*
 * Copyright 2023 LINE Corporation
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

import static com.linecorp.centraldogma.server.internal.storage.repository.MirrorUtil.split;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_FILE;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_HTTP;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_HTTPS;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_SSH;
import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorContext;
import com.linecorp.centraldogma.server.mirror.MirrorProvider;

public final class GitMirrorProvider implements MirrorProvider {

    @Override
    public Mirror newMirror(MirrorContext context) {
        requireNonNull(context, "context");

        final URI remoteUri = context.remoteUri();
        final String scheme = remoteUri.getScheme();
        if (scheme == null) {
            return null;
        }

        switch (scheme) {
            case SCHEME_GIT_SSH: {
                final String[] components = split(remoteUri, "git");
                return new SshGitMirror(context.schedule(), context.direction(), context.credential(),
                                        context.localRepo(), context.localPath(),
                                        URI.create(components[0]), components[1], components[2],
                                        context.gitignore());
            }
            case SCHEME_GIT_HTTP:
            case SCHEME_GIT_HTTPS:
            case SCHEME_GIT:
            case SCHEME_GIT_FILE: {
                final String[] components = split(remoteUri, "git");
                return new GitMirror(context.schedule(), context.direction(), context.credential(),
                                     context.localRepo(), context.localPath(),
                                     URI.create(components[0]), components[1], components[2],
                                     context.gitignore());
            }
        }

        return null;
    }
}
