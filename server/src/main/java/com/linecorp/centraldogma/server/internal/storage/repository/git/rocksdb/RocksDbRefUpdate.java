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

import java.io.IOException;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;

public class RocksDbRefUpdate extends RefUpdate {

    private final RefDatabase refDatabase;
    private final RocksDbRepository repo;

    RocksDbRefUpdate(Ref ref, RefDatabase refDatabase, RocksDbRepository repo) {
        super(ref);
        this.refDatabase = refDatabase;
        this.repo = repo;
    }

    @Override
    protected RefDatabase getRefDatabase() {
        return refDatabase;
    }

    @Override
    protected Repository getRepository() {
        return repo;
    }

    @Override
    protected boolean tryLock(boolean deref) throws IOException {
        // Always return true because we don't need to lock.
        return true;
    }

    @Override
    protected void unlock() {
        // No-op
    }

    @Override
    protected Result doUpdate(Result desiredResult) throws IOException {
        return repo.encryptionGitStorage().updateRef(getRef().getName(), getNewObjectId(), desiredResult);
    }

    @Override
    protected Result doDelete(Result desiredResult) throws IOException {
        if (getRef().getStorage() != Ref.Storage.NEW) {
            repo.encryptionGitStorage().deleteRef(getRef().getName());
        }
        return desiredResult;
    }

    @Override
    protected Result doLink(String target) throws IOException {
        assert target.startsWith(Constants.R_REFS) : target;
        repo.encryptionGitStorage().linkRef(getRef().getName(), target);
        if (getRef().getStorage() == Ref.Storage.NEW) {
            return Result.NEW;
        }
        return Result.FORCED;
    }
}
