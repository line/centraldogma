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
 *
 */

package com.linecorp.centraldogma.server.internal.storage.repository;

import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_DOGMA;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.linecorp.centraldogma.server.internal.mirror.CentralDogmaMirror;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorContext;
import com.linecorp.centraldogma.server.mirror.MirrorProvider;
import com.linecorp.centraldogma.server.mirror.RepositoryUri;

public final class CentralDogmaMirrorProvider implements MirrorProvider {

    private static final Pattern DOGMA_PATH_PATTERN = Pattern.compile("^/([^/]+)/([^/]+)\\.dogma$");

    @Override
    public Mirror newMirror(MirrorContext context) {
        requireNonNull(context, "context");

        final URI remoteUri = context.remoteUri();
        final String scheme = remoteUri.getScheme();
        if (scheme == null) {
            return null;
        }

        if (!SCHEME_DOGMA.equals(scheme)) {
            return null;
        }
        final RepositoryUri repositoryUri = RepositoryUri.parse(remoteUri, "dogma");
        final Matcher pathMatcher = DOGMA_PATH_PATTERN.matcher(repositoryUri.uri().getPath());
        if (!pathMatcher.find()) {
            // TODO(ikhoon): Should we use the same resource URI format with Git?
            //               e.g. dogma://<host>[:<port>].dogma/<project>/<repository>[/<remotePath>]
            throw new IllegalArgumentException(
                    "cannot determine project name and repository name: " + remoteUri +
                    " (expected: dogma://<host>[:<port>]/<project>/<repository>.dogma[<remotePath>])");
        }

        final String remoteProject = pathMatcher.group(1);
        final String remoteRepo = pathMatcher.group(2);
        return new CentralDogmaMirror(context.id(), context.enabled(), context.schedule(), context.direction(),
                                      context.credential(), context.localRepo(), context.localPath(),
                                      repositoryUri, remoteProject, remoteRepo,
                                      context.gitignore(), context.zone());
    }
}
