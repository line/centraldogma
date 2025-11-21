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
 */
package com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;

import org.eclipse.jgit.attributes.AttributesNode;
import org.eclipse.jgit.attributes.AttributesNodeProvider;
import org.eclipse.jgit.lib.ObjectDatabase;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.StoredConfig;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.server.internal.EmptyGitConfig;

public final class RocksDbRepository extends Repository {

    private final String identifier;
    private final EncryptionGitStorage encryptionGitStorage;
    private final RocksDbObjectDatabase rocksDbObjectDatabase;
    private final RocksDbRefDatabase refDatabase;

    public RocksDbRepository(EncryptionGitStorage encryptionGitStorage) {
        super(new RepositoryBuilder());
        identifier = encryptionGitStorage.projectName() + '/' + encryptionGitStorage.repoName();
        this.encryptionGitStorage = requireNonNull(encryptionGitStorage, "encryptionGitStorage");
        rocksDbObjectDatabase = new RocksDbObjectDatabase(encryptionGitStorage);
        refDatabase = new RocksDbRefDatabase(this);
    }

    public EncryptionGitStorage encryptionGitStorage() {
        return encryptionGitStorage;
    }

    @Override
    public void create(boolean bare) throws IOException {
        // No-op.
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public ObjectDatabase getObjectDatabase() {
        return rocksDbObjectDatabase;
    }

    @Override
    public RefDatabase getRefDatabase() {
        return refDatabase;
    }

    @Override
    public StoredConfig getConfig() {
        return EmptyGitConfig.INSTANCE;
    }

    @Override
    public AttributesNodeProvider createAttributesNodeProvider() {
        return EmptyAttributesNodeProvider.EMPTY_ATTRIBUTES_NODE_PROVIDER;
    }

    @Override
    public void scanForRepoChanges() throws IOException {
        // No-op.
    }

    @Override
    public void notifyIndexChanged(boolean internal) {
        // No-op.
    }

    @Override
    public ReflogReader getReflogReader(String refName) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
        super.close();
        encryptionGitStorage.close();
    }

    private static class EmptyAttributesNodeProvider implements AttributesNodeProvider {

        static final EmptyAttributesNodeProvider EMPTY_ATTRIBUTES_NODE_PROVIDER =
                new EmptyAttributesNodeProvider();

        private final EmptyAttributesNode emptyAttributesNode = new EmptyAttributesNode();

        @Override
        public AttributesNode getInfoAttributesNode() throws IOException {
            return emptyAttributesNode;
        }

        @Override
        public AttributesNode getGlobalAttributesNode() throws IOException {
            return emptyAttributesNode;
        }

        private static class EmptyAttributesNode extends AttributesNode {

            EmptyAttributesNode() {
                super(ImmutableList.of());
            }

            @Override
            public void parse(InputStream in) throws IOException {
                // No-op
            }
        }
    }
}
