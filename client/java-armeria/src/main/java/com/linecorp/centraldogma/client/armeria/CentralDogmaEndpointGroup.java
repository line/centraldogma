/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.centraldogma.client.armeria;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.common.Query;

/**
 * A {@link DynamicEndpointGroup} implementation that retrieves the {@link Endpoint} list from an entry in
 * Central Dogma. The entry can be a JSON file or a plain text file.
 *
 * <p>For example, the following JSON array will be served as a list of {@link Endpoint}s:
 * <pre>{@code
 *  [
 *      "host1:port1",
 *      "host2:port2",
 *      "host3:port3"
 *  ]
 * }</pre>
 *
 * <p>The JSON array file could be retrieved as an {@link EndpointGroup} using the following code:
 * <pre>{@code
 * CentralDogmaEndpointGroup<JsonNode> endpointGroup = CentralDogmaEndpointGroup.of(
 *      centralDogma, "myProject", "myRepo",
 *      Query.ofJson("/endpoints.json"),
 *      EndpointListDecoder.JSON);
 * endpointGroup.awaitInitialEndpoints();
 * endpointGroup.endpoints();
 * }</pre>
 *
 * @param <T> the type of the file in Central Dogma
 */
public final class CentralDogmaEndpointGroup<T> extends DynamicEndpointGroup {
    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaEndpointGroup.class);

    private final Watcher<T> instanceListWatcher;
    private final EndpointListDecoder<T> endpointListDecoder;

    /**
     * Creates a new {@link CentralDogmaEndpointGroup}.
     *
     * @param watcher a {@link Watcher}
     * @param endpointListDecoder an {@link EndpointListDecoder}
     */
    public static <T> CentralDogmaEndpointGroup<T> ofWatcher(Watcher<T> watcher,
                                                             EndpointListDecoder<T> endpointListDecoder) {
        return new CentralDogmaEndpointGroup<>(EndpointSelectionStrategy.weightedRoundRobin(),
                                               watcher, endpointListDecoder);
    }

    /**
     * Creates a new {@link CentralDogmaEndpointGroup}.
     *
     * @param centralDogma a {@link CentralDogma}
     * @param projectName a Central Dogma project name
     * @param repositoryName a Central Dogma repository name
     * @param query a {@link Query} to route file
     * @param endpointListDecoder an {@link EndpointListDecoder}
     */
    public static <T> CentralDogmaEndpointGroup<T> of(CentralDogma centralDogma,
                                                      String projectName, String repositoryName,
                                                      Query<T> query,
                                                      EndpointListDecoder<T> endpointListDecoder) {
        return ofWatcher(centralDogma.forRepo(projectName, repositoryName)
                                     .watcher(query)
                                     .start(),
                         endpointListDecoder);
    }

    /**
     * Returns a new {@link CentralDogmaEndpointGroupBuilder} with the {@link Watcher}
     * and {@link EndpointListDecoder}. You can create a {@link Watcher} using {@link CentralDogma}:
     *
     * <pre>{@code
     * CentralDogma centralDogma = ...
     * Query<T> query = ... // The query to the entry that contains the list of endpoints.
     * Watcher watcher = centralDogma.fileWatcher(projectName, repositoryName, query);
     * }</pre>
     */
    public static <T> CentralDogmaEndpointGroupBuilder<T> builder(Watcher<T> watcher,
                                                                  EndpointListDecoder<T> endpointListDecoder) {
        return new CentralDogmaEndpointGroupBuilder<>(watcher, endpointListDecoder);
    }

    CentralDogmaEndpointGroup(EndpointSelectionStrategy strategy,
                              Watcher<T> instanceListWatcher,
                              EndpointListDecoder<T> endpointListDecoder) {
        super(strategy);
        this.instanceListWatcher = requireNonNull(instanceListWatcher, "instanceListWatcher");
        this.endpointListDecoder = requireNonNull(endpointListDecoder, "endpointListDecoder");
        registerWatcher();
    }

    private void registerWatcher() {
        instanceListWatcher.watch((revision, instances) -> {
            try {
                final List<Endpoint> newEndpoints = endpointListDecoder.decode(instances);
                if (newEndpoints.isEmpty()) {
                    logger.info("Not refreshing the endpoint list of {} because it's empty. {}",
                                instanceListWatcher, revision);
                    return;
                }
                setEndpoints(newEndpoints);
            } catch (Exception e) {
                logger.warn("Failed to re-retrieve the endpoint list from Central Dogma.", e);
            }
        });
        instanceListWatcher.initialValueFuture().exceptionally(e -> {
            logger.warn("Failed to retrieve the initial instance list from Central Dogma.", e);
            return null;
        });
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        instanceListWatcher.close();
        future.complete(null);
    }

    @Override
    public String toString() {
        return toStringHelper()
                .add("instanceListWatcher", instanceListWatcher)
                .add("endpointListDecoder", endpointListDecoder)
                .toString();
    }
}
