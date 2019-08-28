/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.centraldogma.client;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.linecorp.centraldogma.common.Revision;

/**
 * A watcher for a specific part of a JSON file, that piggy-rides on a watcher of the whole JSON file.
 * It does not make extra requests, and it will not notify listeners of irrelevant changes.
 */
public class FilteredJsonWatcher implements Watcher<JsonNode> {

    private static JsonNode orMissing(@Nullable JsonNode node) {
        return node == null ? MissingNode.getInstance() : node;
    }

    private final Watcher<JsonNode> parent;
    private final String jsonPath;
    @Nullable
    private volatile Latest<JsonNode> filteredLatestNode;

    public FilteredJsonWatcher(Watcher<JsonNode> parent, String jsonPath) {
        this.parent = parent;
        this.jsonPath = jsonPath;
    }

    @Override
    public CompletableFuture<Latest<JsonNode>> initialValueFuture() {
        return parent.initialValueFuture().thenApply(ignored -> latest());
    }

    @Override
    public Latest<JsonNode> latest() {
        filteredLatestNode = applyFilter(parent.latest());
        return filteredLatestNode;
    }

    @Nonnull
    private Latest<JsonNode> applyFilter(Latest<JsonNode> latestParentNode) {
        return new Latest<>(latestParentNode.revision(), orMissing(latestParentNode.value()).at(jsonPath));
    }

    @Override
    public void close() {
        // do nothing. parent will take care of itself
    }

    @Override
    public void watch(BiConsumer<? super Revision, ? super JsonNode> listener) {
        parent.watch((rev, parentNode) -> {
            final JsonNode subNode = orMissing(parentNode).at(jsonPath);
            Optional<JsonNode> unchanged = Optional
                    .ofNullable(filteredLatestNode)
                    .map(Latest::value)
                    .filter(subNode::equals);
            if (!unchanged.isPresent()) {
                filteredLatestNode = new Latest<>(rev, subNode);
                listener.accept(rev, subNode);
            } // else, node has not changed
        });
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " filtering " + parent + " at " + jsonPath;
    }
}
