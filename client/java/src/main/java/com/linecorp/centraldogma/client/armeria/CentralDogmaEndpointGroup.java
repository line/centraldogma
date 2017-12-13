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
package com.linecorp.centraldogma.client.armeria;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.common.Query;

/**
 * A CentralDogma based {@link EndpointGroup} implementation. This {@link EndpointGroup} retrieves the list of
 * {@link Endpoint}s from a route file served by CentralDogma, and update the list when upstream data changes.
 * Route file could be json file or normal text file.
 * <p>
 * <p>In below example, json file with below content will be served as route file:
 * <p>
 * <pre>{@code
 *  [
 *      "host1:port1",
 *      "host2:port2",
 *      "host3:port3"
 *  ]
 * }
 * </pre>
 * <p>
 * <p>The route file could be retrieve as {@link EndpointGroup} using below code:
 * <p>
 * <pre>{@code
 *  CentralDogmaEndpointGroup<JsonNode> endpointGroup = CentralDogmaEndpointGroup.ofJsonFile(
 *      centralDogma, "myProject", "myRepo",
 *      CentralDogmaCodec.DEFAULT_JSON_CODEC,
 *      Query.ofJsonPath("/route.json")
 *  )
 *  endpointGroup.endpoints();
 * }
 * </pre>
 *
 * @param <T> Type of CentralDomgma file (could be JsonNode or String)
 */
public final class CentralDogmaEndpointGroup<T> extends DynamicEndpointGroup {
    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaEndpointGroup.class);
    private static final long WATCH_INITIALIZATION_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

    private final Watcher<T> instanceListWatcher;
    private final EndpointListCodec<T> endpointCodec;

    /**
     * Creates a new {@link CentralDogmaEndpointGroup}.
     *
     * @param endpointCodec A {@link EndpointListCodec}
     * @param watcher A {@link Watcher}
     */
    public static <T> CentralDogmaEndpointGroup<T> ofWatcher(EndpointListCodec<T> endpointCodec,
                                                             Watcher<T> watcher) {
        return new CentralDogmaEndpointGroup<>(watcher, endpointCodec);
    }

    /**
     * Creates a new {@link CentralDogmaEndpointGroup}.
     * @param uri an uri of CentralDogma server
     * @param dogmaProject CentralDogma project name
     * @param dogmaRepo CentralDogma repository name
     * @param dogmaCodec A {@link CentralDogmaCodec}
     * @param waitTime a {@code long} define how long we should wait for initial result
     * @param waitUnit a {@link TimeUnit} define unit of wait time
     * @throws CentralDogmaEndpointException if couldn't get initial result from CentralDogma server
     */

    /**
     * Creates a new {@link CentralDogmaEndpointGroup}.
     *
     * @param centralDogma A {@link CentralDogma}
     * @param endpointCodec A {@link EndpointListCodec}
     * @param projectName CentralDogma project name
     * @param repositoryName CentralDogma repository name
     * @param query A {@link Query} to route file
     */
    public static <T> CentralDogmaEndpointGroup<T> of(CentralDogma centralDogma,
                                                      EndpointListCodec<T> endpointCodec,
                                                      String projectName, String repositoryName,
                                                      Query<T> query) {
        return ofWatcher(endpointCodec, centralDogma.fileWatcher(projectName, repositoryName, query));
    }

    private CentralDogmaEndpointGroup(Watcher<T> instanceListWatcher, EndpointListCodec<T> endpointCodec) {
        this.instanceListWatcher = requireNonNull(instanceListWatcher, "instanceListWatcher");
        this.endpointCodec = requireNonNull(endpointCodec, "endpointCodec");
        registerWatcher();
    }

    private void registerWatcher() {
        instanceListWatcher.watch((revision, instances) -> {
            try {
                List<Endpoint> newEndpoints = endpointCodec.decode(instances);
                if (newEndpoints.isEmpty()) {
                    logger.info("Not refreshing the endpoint list of {} because it's empty. {}",
                                instanceListWatcher, revision);
                    return;
                }
                setEndpoints(newEndpoints);
            } catch (Exception e) {
                logger.warn("Failed to refresh the endpoint list.", e);
            }
        });
        try {
            instanceListWatcher.awaitInitialValue(WATCH_INITIALIZATION_TIMEOUT_MILLIS,
                                                  TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException e) {
            logger.warn("Failed to initialize instance list on time.", e);
        }
    }

    @Override
    public void close() {
        instanceListWatcher.close();
    }
}
