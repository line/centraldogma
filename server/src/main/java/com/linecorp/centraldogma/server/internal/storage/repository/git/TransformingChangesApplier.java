/*
 * Copyright 2024 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.Objects;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.ContentTransformer;
import com.linecorp.centraldogma.server.internal.admin.service.TokenNotFoundException;

final class TransformingChangesApplier extends AbstractChangesApplier {

    private final ContentTransformer<JsonNode> transformer;

    TransformingChangesApplier(ContentTransformer<?> transformer) {
        checkArgument(transformer.entryType() == EntryType.JSON,
                      "transformer: %s (expected: JSON type)", transformer);
        //noinspection unchecked
        this.transformer = (ContentTransformer<JsonNode>) transformer;
    }

    @Override
    int doApply(DirCache dirCache, ObjectReader reader, ObjectInserter inserter) throws IOException {
        final String changePath = transformer.path().substring(1); // Strip the leading '/'.
        final DirCacheEntry oldEntry = dirCache.getEntry(changePath);
        final byte[] oldContent = oldEntry != null ? reader.open(oldEntry.getObjectId()).getBytes()
                                                   : null;
        final JsonNode oldJsonNode = oldContent != null ? Jackson.readTree(oldContent)
                                                        : JsonNodeFactory.instance.nullNode();
        try {
            final JsonNode newJsonNode = transformer.transformer().apply(oldJsonNode.deepCopy());
            requireNonNull(newJsonNode, "transformer.transformer().apply() returned null");
            if (!Objects.equals(newJsonNode, oldJsonNode)) {
                applyPathEdit(dirCache, new InsertJson(changePath, inserter, newJsonNode));
                return 1;
            }
        } catch (CentralDogmaException | TokenNotFoundException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new ChangeConflictException("failed to transform the content: " + oldJsonNode +
                                              " transformer: " + transformer, e);
        }
        return 0;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("transformer", transformer)
                          .toString();
    }
}
