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

package com.linecorp.centraldogma.server.internal.storage.repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public final class DefaultMetaRepository extends RepositoryWrapper implements MetaRepository {

    public static final String PATH_CREDENTIALS = "/credentials.json";

    public static final String PATH_MIRRORS = "/mirrors.json";

    public static final Set<String> metaRepoFiles = ImmutableSet.of(PATH_CREDENTIALS, PATH_MIRRORS);

    private static final String PATH_CREDENTIALS_AND_MIRRORS = PATH_CREDENTIALS + ',' + PATH_MIRRORS;

    private final Lock mirrorLock = new ReentrantLock();

    /**
     * The revision number of the /credentials.json and /mirrors.json who generated {@link #mirrors}.
     */
    private int mirrorRev = -1;

    /**
     * The repositories of the parent {@link Project} at the moment when {@link #mirrors} is generated.
     */
    private Set<String> mirrorRepos = Collections.emptySet();

    @Nullable
    private Set<Mirror> mirrors;

    public DefaultMetaRepository(Repository repo) {
        super(repo);
    }

    @Override
    public org.eclipse.jgit.lib.Repository jGitRepository() {
        return unwrap().jGitRepository();
    }

    @Override
    public Set<Mirror> mirrors() {
        mirrorLock.lock();
        try {
            final int headRev = normalizeNow(Revision.HEAD).major();
            final Set<String> repos = parent().repos().list().keySet();
            if (headRev > mirrorRev || !mirrorRepos.equals(repos)) {
                mirrors = loadMirrors(headRev);
                mirrorRev = headRev;
                mirrorRepos = repos;
            }

            return mirrors;
        } finally {
            mirrorLock.unlock();
        }
    }

    private Set<Mirror> loadMirrors(int rev) {
        // TODO(trustin): Asynchronization
        final Map<String, Entry<?>> entries =
                find(new Revision(rev), PATH_CREDENTIALS_AND_MIRRORS, Collections.emptyMap()).join();

        if (!entries.containsKey(PATH_MIRRORS)) {
            return Collections.emptySet();
        }

        final JsonNode mirrorsJson = (JsonNode) entries.get(PATH_MIRRORS).content();
        if (!mirrorsJson.isArray()) {
            throw new RepositoryMetadataException(
                    PATH_MIRRORS + " must be an array: " + mirrorsJson.getNodeType());
        }

        if (mirrorsJson.size() == 0) {
            return Collections.emptySet();
        }

        try {
            final List<MirrorCredential> credentials = loadCredentials(entries);
            final ImmutableSet.Builder<Mirror> mirrors = ImmutableSet.builder();

            for (JsonNode m : mirrorsJson) {
                final MirrorConfig c = Jackson.treeToValue(m, MirrorConfig.class);
                if (c == null) {
                    throw new RepositoryMetadataException(PATH_MIRRORS + " contains null.");
                }
                final Mirror mirror = c.toMirror(parent(), credentials);
                if (mirror != null) {
                    mirrors.add(mirror);
                }
            }

            return mirrors.build();
        } catch (RepositoryMetadataException e) {
            throw e;
        } catch (Exception e) {
            throw new RepositoryMetadataException("failed to load the mirror configuration", e);
        }
    }

    private static List<MirrorCredential> loadCredentials(Map<String, Entry<?>> entries) throws Exception {
        final Entry<?> e = entries.get(PATH_CREDENTIALS);
        if (e == null) {
            return Collections.emptyList();
        }

        final JsonNode credentialsJson = (JsonNode) e.content();
        if (!credentialsJson.isArray()) {
            throw new RepositoryMetadataException(
                    PATH_CREDENTIALS + " must be an array: " + credentialsJson.getNodeType());
        }

        if (credentialsJson.size() == 0) {
            return Collections.emptyList();
        }

        final ImmutableList.Builder<MirrorCredential> builder = ImmutableList.builder();
        for (JsonNode c : credentialsJson) {
            final MirrorCredential credential = Jackson.treeToValue(c, MirrorCredential.class);
            if (credential == null) {
                throw new RepositoryMetadataException(PATH_CREDENTIALS + " contains null.");
            }
            builder.add(credential);
        }

        return builder.build();
    }
}
