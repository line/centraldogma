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

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.mirror.credential.AbstractMirrorCredential;
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

    private static final Map.Entry<List<Mirror>, List<MirrorCredential>> EMPTY_MIRRORS_AND_CREDENTIALS =
            Maps.immutableEntry(ImmutableList.of(), ImmutableList.of());

    private final Lock mirrorLock = new ReentrantLock();

    /**
     * The revision number of the /credentials.json and /mirrors.json who generated {@link #mirrors}.
     */
    private int mirrorRev = -1;

    @Nullable
    private Map.Entry<List<Mirror>, List<MirrorCredential>> mirrorsAndCredentials;

    public DefaultMetaRepository(Repository repo) {
        super(repo);
    }

    @Override
    public CompletableFuture<List<Mirror>> mirrors(boolean includeDisabled) {
        return mirrorsAndCredentials().thenApply(mirrorsAndCredentials -> {
            final List<Mirror> mirrors = mirrorsAndCredentials.getKey();
            if (includeDisabled) {
                return mirrors;
            } else {
                return mirrors.stream().filter(Mirror::enabled).collect(toImmutableList());
            }
        });
    }

    @Override
    public CompletableFuture<List<MirrorCredential>> credentials() {
        return mirrorsAndCredentials().thenApply(Map.Entry::getValue);
    }

    public CompletableFuture<Map.Entry<List<Mirror>, List<MirrorCredential>>> mirrorsAndCredentials() {
        // The head revision of meta repo will be increased if a mirror configuration is updated or
        // a repository is created or removed.
        final int headRev = normalizeNow(Revision.HEAD).major();
        if (headRev > mirrorRev) {
            return mirrorsAndCredentials(headRev).handle((mirrorsAndCredentials, cause) -> {
                if (cause != null) {
                    return Exceptions.throwUnsafely(cause);
                }

                mirrorLock.lock();
                final int mirrorRev = this.mirrorRev;
                if (headRev > mirrorRev) {
                    this.mirrorsAndCredentials = mirrorsAndCredentials;
                    this.mirrorRev = headRev;
                } else {
                    // `mirrors` was updated by other threads with the latest revision.
                }

                mirrorLock.unlock();
                return mirrorsAndCredentials;
            });
        } else {
            return UnmodifiableFuture.completedFuture(mirrorsAndCredentials);
        }
    }

    private CompletableFuture<Map.Entry<List<Mirror>, List<MirrorCredential>>> mirrorsAndCredentials(int rev) {
        return find(new Revision(rev), PATH_CREDENTIALS_AND_MIRRORS, Collections.emptyMap()).thenApply(
                entries -> {
                    if (entries.isEmpty()) {
                        return EMPTY_MIRRORS_AND_CREDENTIALS;
                    }

                    try {
                        final List<MirrorCredential> credentials = parseCredentials(entries);
                        final List<Mirror> mirrors = parseMirrors(entries, credentials);
                        return Maps.immutableEntry(mirrors, credentials);
                    } catch (RepositoryMetadataException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RepositoryMetadataException("failed to load the mirror configuration", e);
                    }
                });
    }

    private List<Mirror> parseMirrors(Map<String, Entry<?>> entries, List<MirrorCredential> credentials)
            throws JsonProcessingException {
        final Entry<?> e = entries.get(PATH_MIRRORS);
        if (e == null) {
            return ImmutableList.of();
        }

        final JsonNode mirrorsJson = (JsonNode) e.content();
        if (!mirrorsJson.isArray()) {
            throw new RepositoryMetadataException(
                    PATH_MIRRORS + " must be an array: " + mirrorsJson.getNodeType());
        }
        if (mirrorsJson.isEmpty()) {
            return ImmutableList.of();
        }

        final ImmutableList.Builder<Mirror> mirrors = ImmutableList.builder();

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
    }

    private static List<MirrorCredential> parseCredentials(Map<String, Entry<?>> entries)
            throws JsonProcessingException {
        final Entry<?> e = entries.get(PATH_CREDENTIALS);
        if (e == null) {
            return ImmutableList.of();
        }

        final JsonNode credentialsJson = (JsonNode) e.content();
        if (!credentialsJson.isArray()) {
            throw new RepositoryMetadataException(
                    PATH_CREDENTIALS + " must be an array: " + credentialsJson.getNodeType());
        }
        if (credentialsJson.isEmpty()) {
            return ImmutableList.of();
        }

        final ImmutableList.Builder<MirrorCredential> builder = ImmutableList.builder();
        int index = 0;
        for (JsonNode c : credentialsJson) {
            final MirrorCredential credential = Jackson.treeToValue(c, MirrorCredential.class);
            if (credential == null) {
                throw new RepositoryMetadataException(PATH_CREDENTIALS + " contains null.");
            }
            assert credential instanceof AbstractMirrorCredential;
            ((AbstractMirrorCredential) credential).setIndex(index);
            builder.add(credential);
            index++;
        }

        return builder.build();
    }
}
