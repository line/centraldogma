/*
 * Copyright 2025 LINE Corporation
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

import java.net.URI;
import java.util.List;
import java.util.ServiceLoader;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.internal.api.v1.MirrorRequest;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorContext;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirrorProvider;
import com.linecorp.centraldogma.server.mirror.MirrorUtil;
import com.linecorp.centraldogma.server.storage.project.Project;

public final class MirrorConverter {

    private static final Logger logger = LoggerFactory.getLogger(MirrorConverter.class);

    public static final List<MirrorProvider> MIRROR_PROVIDERS;

    static {
        MIRROR_PROVIDERS = ImmutableList.copyOf(ServiceLoader.load(MirrorProvider.class));
        logger.debug("Available {}s: {}", MirrorProvider.class.getSimpleName(), MIRROR_PROVIDERS);
    }

    @Nullable
    static Mirror convertToMirror(MirrorConfig mirrorConfig, Project parent, List<Credential> credentials) {
        if (!parent.repos().exists(mirrorConfig.localRepo())) {
            return null;
        }

        final Credential credential = findCredential(mirrorConfig, credentials);
        return convertToMirror(mirrorConfig, parent, credential);
    }

    static Mirror convertToMirror(MirrorConfig mirrorConfig, Project parent, Credential credential) {
        final MirrorContext mirrorContext = new MirrorContext(
                mirrorConfig.id(), mirrorConfig.enabled(), mirrorConfig.cronSchedule(),
                mirrorConfig.direction(),
                credential, parent.repos().get(mirrorConfig.localRepo()), mirrorConfig.localPath(),
                mirrorConfig.rawRemoteUri(), mirrorConfig.gitignore(), mirrorConfig.zone());
        for (MirrorProvider mirrorProvider : MIRROR_PROVIDERS) {
            final Mirror mirror = mirrorProvider.newMirror(mirrorContext);
            if (mirror != null) {
                return mirror;
            }
        }

        throw new IllegalArgumentException("could not find a mirror provider for " + mirrorContext);
    }

    private static Credential findCredential(MirrorConfig mirrorConfig, List<Credential> credentials) {
        for (Credential c : credentials) {
            if (mirrorConfig.credentialName().equals(c.name())) {
                return c;
            }
        }

        return Credential.NONE;
    }

    public static MirrorConfig converterToMirrorConfig(MirrorRequest mirrorRequest) {
        final String remoteUri =
                mirrorRequest.remoteScheme() + "://" + mirrorRequest.remoteUrl() +
                MirrorUtil.normalizePath(mirrorRequest.remotePath()) + '#' + mirrorRequest.remoteBranch();

        return new MirrorConfig(
                mirrorRequest.id(),
                mirrorRequest.enabled(),
                mirrorRequest.schedule(),
                MirrorDirection.valueOf(mirrorRequest.direction()),
                mirrorRequest.localRepo(),
                mirrorRequest.localPath(),
                URI.create(remoteUri),
                mirrorRequest.gitignore(),
                null,
                mirrorRequest.credentialName(),
                mirrorRequest.zone());
    }

    private MirrorConverter() {}
}
