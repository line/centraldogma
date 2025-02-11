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

import javax.annotation.Nullable;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ObjectReader.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class CachingTreeObjectReader extends Filter {

    private static final Logger logger = LoggerFactory.getLogger(CachingTreeObjectReader.class);

    private final Repository repository;

    private final ObjectReader delegate;

    @Nullable
    private final RepositoryCache cache;

    CachingTreeObjectReader(Repository repository, ObjectReader delegate, @Nullable RepositoryCache cache) {
        this.repository = repository;
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    protected ObjectReader delegate() {
        return delegate;
    }

    @Override
    public ObjectLoader open(AnyObjectId objectId, int typeHint)
            throws IOException {
        // Only cache tree objects.
        if (OBJ_TREE != typeHint || cache == null) {
            return delegate.open(objectId, typeHint);
        }

        // Need to convert to objectId from MutableObjectId
        final AnyObjectId objectId0 = objectId.toObjectId();

        final CacheableObjectLoaderCall key = new CacheableObjectLoaderCall(repository, objectId0);
        return cache.load(key, () -> {
            try {
                return delegate.open(objectId0, typeHint);
            } catch (IOException e) {
                throw new RuntimeException("failed to open an object: " + objectId0, e);
            }
        }, false);
    }
}
