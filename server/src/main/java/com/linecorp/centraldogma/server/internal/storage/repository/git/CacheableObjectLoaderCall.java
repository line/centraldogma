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

import static org.eclipse.jgit.lib.Constants.OBJ_TREE;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;

import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.primitives.Ints;

import com.linecorp.centraldogma.server.storage.repository.AbstractCacheableCall;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class CacheableObjectLoaderCall extends AbstractCacheableCall<ObjectLoader> {

    private final ObjectReader delegate;
    private final AnyObjectId objectId;
    private final int hashCode;

    CacheableObjectLoaderCall(Repository repo, ObjectReader delegate, AnyObjectId objectId) {
        super(repo);
        this.delegate = delegate;
        this.objectId = objectId;
        hashCode = objectId.hashCode() * 31 + System.identityHashCode(repo);
    }

    @Override
    public int weigh(ObjectLoader value) {
        return Ints.saturatedCast(value.getSize());
    }

    @Override
    public CompletableFuture<ObjectLoader> execute() {
        try {
            // Do not leave a dubug log here because it will be called very frequently.
            return CompletableFuture.completedFuture(delegate.open(objectId, OBJ_TREE));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open an object: " + objectId, e);
        }
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
