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
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import java.util.concurrent.CompletableFuture;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectLoader;

import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.primitives.Ints;

import com.linecorp.centraldogma.server.internal.storage.repository.CacheableCall;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class CacheableObjectLoaderCall extends CacheableCall<ObjectLoader> {

    private final AnyObjectId objectId;
    private final int hashCode;

    CacheableObjectLoaderCall(Repository repo, AnyObjectId objectId) {
        super(repo);
        this.objectId = objectId;
        hashCode = objectId.hashCode() * 31 + System.identityHashCode(repo);
    }

    @Override
    protected int weigh(ObjectLoader value) {
        return Ints.saturatedCast(value.getSize());
    }

    /**
     * Never invoked because {@link GitRepository} produces the value of this call.
     */
    @Override
    public CompletableFuture<ObjectLoader> execute() {
        throw new IllegalStateException();
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }

        final CacheableObjectLoaderCall that = (CacheableObjectLoaderCall) o;
        return objectId.equals(that.objectId);
    }

    @Override
    protected void toString(ToStringHelper helper) {
        helper.add("objectId", objectId);
    }
}
