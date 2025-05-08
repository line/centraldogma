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

import static org.eclipse.jgit.lib.Ref.Storage.NEW;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefRename;
import org.eclipse.jgit.lib.RefUpdate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

final class RocksDbRefDatabase extends RefDatabase {

    private final RocksDbRepository repo;

    RocksDbRefDatabase(RocksDbRepository repo) {
        this.repo = repo;
    }

    @Override
    public void create() throws IOException {
        // No-op
    }

    @Override
    public void close() {
        // No-op
    }

    @Override
    public boolean isNameConflicting(String name) throws IOException {
        // It's always false because the name is HEAD or refs/heads/master.
        assert name.equals(Constants.HEAD) || name.equals(Constants.R_HEADS + Constants.MASTER) : name;
        return false;
    }

    @Override
    public RefUpdate newUpdate(String name, boolean detach) throws IOException {
        assert !detach;
        Ref ref = repo.encryptionGitStorage().readRef(name);
        if (ref == null) {
            ref = new ObjectIdRef.Unpeeled(NEW, name, null);
        }

        return new RocksDbRefUpdate(ref, this, repo);
    }

    @Override
    public RefRename newRename(String fromName, String toName) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public Ref exactRef(String name) throws IOException {
        return repo.encryptionGitStorage().readRef(name);
    }

    @Override
    public Map<String, Ref> getRefs(String prefix) throws IOException {
        // Currently, we don't support listing refs.
        return ImmutableMap.of();
    }

    @Override
    public List<Ref> getAdditionalRefs() throws IOException {
        // Currently, we don't support listing refs.
        return ImmutableList.of();
    }

    @Override
    public Ref peel(Ref ref) throws IOException {
        // We don't use annotated tag so just return the ref itself.
        return ref;
    }
}
