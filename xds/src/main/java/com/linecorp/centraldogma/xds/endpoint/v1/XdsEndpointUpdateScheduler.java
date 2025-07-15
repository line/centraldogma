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
package com.linecorp.centraldogma.xds.endpoint.v1;

import static com.linecorp.centraldogma.server.storage.repository.FindOptions.FIND_ONE_WITHOUT_CONTENT;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Empty;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.ContentTransformer;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.xds.internal.XdsResourceManager;

import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Builder;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

final class XdsEndpointUpdateScheduler {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            ThreadFactories.newThreadFactory("xds-endpoint-update-scheduler", true));

    private final XdsResourceManager xdsResourceManager;

    private final ConcurrentMap<String, BatchUpdateTask> batchUpdateTasks = new ConcurrentHashMap<>();

    XdsEndpointUpdateScheduler(XdsResourceManager xdsResourceManager) {
        this.xdsResourceManager = xdsResourceManager;
    }

    int batchUpdateTaskSize() {
        return batchUpdateTasks.size();
    }

    void schedule(String group, String endpointName, String fileName,
                  LocalityLbEndpoint localityLbEndpoint, StreamObserver<?> streamObserver, boolean register) {
        final EndpointIdentifier identifier = EndpointIdentifier.of(localityLbEndpoint);

        batchUpdateTasks.compute(endpointName, (key, task) -> {
            if (task == null) {
                task = new BatchUpdateTask(group, key, fileName);
            }

            task.addOperationAndSchedule(identifier, register, localityLbEndpoint, streamObserver);
            return task;
        });
    }

    private final class BatchUpdateTask {

        private final String group;
        private final String endpointName;
        private final String fileName;

        private final ReentrantShortLock lock = new ReentrantShortLock();
        private final Map<EndpointIdentifier, PendingUpdate> pendingUpdates = new LinkedHashMap<>();
        @Nullable
        private ScheduledFuture<?> scheduledFuture;

        BatchUpdateTask(String group, String endpointName, String fileName) {
            this.group = group;
            this.endpointName = endpointName;
            this.fileName = fileName;
        }

        void addOperationAndSchedule(EndpointIdentifier identifier, boolean register,
                                     LocalityLbEndpoint localityLbEndpoint, StreamObserver<?> streamObserver) {
            final PendingUpdate previous;
            lock.lock();
            try {
                previous = pendingUpdates.put(identifier,
                                              new PendingUpdate(register, localityLbEndpoint, streamObserver));
                if (scheduledFuture == null) {
                    scheduledFuture = scheduler.schedule(this::flush, 3, TimeUnit.SECONDS);
                }
            } finally {
                lock.unlock();
            }

            if (previous != null) {
                try (SafeCloseable ignored = RequestContextUtil.pop()) {
                    previous.streamObserver.onError(
                            Status.ABORTED
                                    .withDescription("Aborted due to a new update for the same endpoint")
                                    .asRuntimeException());
                }
            }
        }

        private void flush() {
            final List<PendingUpdate> copied;
            lock.lock();
            try {
                assert !pendingUpdates.isEmpty();
                copied = ImmutableList.copyOf(pendingUpdates.values());
                pendingUpdates.clear();
                scheduledFuture = null;
            } finally {
                lock.unlock();
            }

            batchUpdateTasks.computeIfPresent(endpointName, (key, task) -> {
                lock.lock();
                try {
                    if (pendingUpdates.isEmpty()) {
                        return null; // Remove the task if there are no pending updates.
                    }
                    return task; // Keep the task for future updates.
                } finally {
                    lock.unlock();
                }
            });

            final List<LocalityLbEndpoint> toRegister = new ArrayList<>();
            final List<LocalityLbEndpoint> toDeregister = new ArrayList<>();
            for (PendingUpdate pendingUpdate : copied) {
                if (pendingUpdate.register) {
                    toRegister.add(pendingUpdate.endpoint);
                } else {
                    toDeregister.add(pendingUpdate.endpoint);
                }
            }

            final ContentTransformer<JsonNode> transformer = new ContentTransformer<>(
                    fileName, EntryType.JSON, new BatchUpdateTransformer(toRegister, toDeregister));

            final Repository repository = xdsResourceManager.xdsProject().repos().get(group);
            repository.find(Revision.HEAD, fileName, FIND_ONE_WITHOUT_CONTENT).handle((entries, cause) -> {
                if (cause != null) {
                    copied.forEach(pendingUpdate -> pendingUpdate.streamObserver.onError(cause));
                    return null;
                }
                if (entries.isEmpty()) {
                    final StatusRuntimeException runtimeException =
                            Status.NOT_FOUND.withDescription("Resource not found: " + fileName)
                                            .asRuntimeException();
                    copied.forEach(pendingUpdate -> pendingUpdate.streamObserver.onError(runtimeException));
                    return null;
                }
                final String commitMessage =
                        "Batch update for " + endpointName + " in group " + group + ": " +
                        toRegister.size() + " register, " + toDeregister.size() + " deregister";
                xdsResourceManager.commandExecutor()
                                  .execute(Command.transform(
                                          null, Author.SYSTEM, XDS_CENTRAL_DOGMA_PROJECT, group, Revision.HEAD,
                                          commitMessage, "", Markup.PLAINTEXT, transformer))
                                  .handle((result, cause2) -> {
                                      if (cause2 != null) {
                                          final Throwable peeled = Exceptions.peel(cause2);
                                          if (!(peeled instanceof RedundantChangeException)) {
                                              copied.forEach(pendingUpdate -> pendingUpdate
                                                      .streamObserver.onError(peeled));
                                              return null;
                                          }
                                          // If the change is redundant, we just ignore it and complete
                                          // the stream observer without error.
                                      }
                                      copied.forEach(pendingUpdate -> {
                                          final StreamObserver<?> streamObserver = pendingUpdate.streamObserver;
                                          if (pendingUpdate.register) {
                                              //noinspection unchecked
                                              ((StreamObserver<LocalityLbEndpoint> ) streamObserver).onNext(
                                                      pendingUpdate.endpoint);
                                          } else {
                                              //noinspection unchecked
                                              ((StreamObserver<Empty>) streamObserver).onNext(
                                                      Empty.getDefaultInstance());
                                          }
                                          streamObserver.onCompleted();
                                      });
                                      return null;
                                  });
                return null;
            });
        }
    }

    private static class PendingUpdate {
        final boolean register;
        final LocalityLbEndpoint endpoint;
        final StreamObserver<?> streamObserver;

        PendingUpdate(boolean register, LocalityLbEndpoint endpoint, StreamObserver<?> streamObserver) {
            this.register = register;
            this.endpoint = endpoint;
            this.streamObserver = streamObserver;
        }
    }

    private static final class BatchUpdateTransformer implements BiFunction<Revision, JsonNode, JsonNode> {

        private final List<LocalityLbEndpoint> toRegister;
        private final List<LocalityLbEndpoint> toDeregister;

        BatchUpdateTransformer(List<LocalityLbEndpoint> toRegister, List<LocalityLbEndpoint> toDeregister) {
            this.toRegister = toRegister;
            this.toDeregister = toDeregister;
        }

        @Override
        public JsonNode apply(Revision revision, JsonNode oldJsonNode) {
            if (oldJsonNode.isNull()) {
                throw new EntryNotFoundException();
            }
            final ClusterLoadAssignment.Builder builder = toClusterLoadAssignmentBuilder(oldJsonNode);

            // 1. Deregister endpoints
            for (LocalityLbEndpoint endpoint : toDeregister) {
                final int localityIndex = findLocalityAndPriorityIndex(builder, endpoint);
                if (localityIndex < 0) {
                    continue;
                }
                final LocalityLbEndpoints localityEndpoints = builder.getEndpoints(localityIndex);
                final int lbEndpointIndex = findLbEndpointIndex(localityEndpoints, endpoint);

                if (lbEndpointIndex >= 0) {
                    final LocalityLbEndpoints.Builder localityBuilder = localityEndpoints.toBuilder();
                    localityBuilder.removeLbEndpoints(lbEndpointIndex);
                    builder.removeEndpoints(localityIndex);
                    if (localityBuilder.getLbEndpointsCount() > 0) {
                        builder.addEndpoints(localityBuilder);
                    }
                }
            }

            // 2. Register endpoints
            for (LocalityLbEndpoint endpoint : toRegister) {
                final int localityIndex = findLocalityAndPriorityIndex(builder, endpoint);
                if (localityIndex < 0) {
                    builder.addEndpoints(
                            LocalityLbEndpoints.newBuilder()
                                               .setLocality(endpoint.getLocality())
                                               .setPriority(endpoint.getPriority())
                                               .addLbEndpoints(endpoint.getLbEndpoint())
                                               .build());
                } else {
                    final LocalityLbEndpoints localityEndpoints = builder.getEndpoints(localityIndex);
                    final LocalityLbEndpoints.Builder localityBuilder = localityEndpoints.toBuilder();

                    final int lbEndpointIndex = findLbEndpointIndex(localityEndpoints, endpoint);
                    if (lbEndpointIndex >= 0) {
                        localityBuilder.removeLbEndpoints(lbEndpointIndex);
                    }
                    localityBuilder.addLbEndpoints(endpoint.getLbEndpoint());

                    builder.removeEndpoints(localityIndex);
                    builder.addEndpoints(localityBuilder);
                }
            }

            return toJsonNode(builder);
        }

        private static ClusterLoadAssignment.Builder toClusterLoadAssignmentBuilder(JsonNode oldJsonNode) {
            final Builder clusterLoadAssignmentBuilder =
                    ClusterLoadAssignment.newBuilder();
            try {
                JSON_MESSAGE_MARSHALLER.mergeValue(Jackson.writeValueAsString(oldJsonNode),
                                                   clusterLoadAssignmentBuilder);
            } catch (Throwable t) {
                // Should never reach here.
                throw new Error();
            }
            return clusterLoadAssignmentBuilder;
        }

        /**
         * Find the index of the {@link LocalityLbEndpoints} that has the same locality and priority as the
         * specified {@link LocalityLbEndpoint}.
         * If the locality and priority are not found, return -1.
         */
        private static int findLocalityAndPriorityIndex(Builder clusterLoadAssignmentBuilder,
                                                        LocalityLbEndpoint localityLbEndpoint) {
            int sameLocalityIndex = -1;

            final List<LocalityLbEndpoints> localityLbEndpointsList =
                    clusterLoadAssignmentBuilder.getEndpointsList();
            for (int i = 0; i < localityLbEndpointsList.size(); i++) {
                final LocalityLbEndpoints localityLbEndpoints = localityLbEndpointsList.get(i);
                if (localityLbEndpoints.getLocality().equals(localityLbEndpoint.getLocality()) &&
                    localityLbEndpoints.getPriority() == localityLbEndpoint.getPriority()) {
                    sameLocalityIndex = i;
                    break;
                }
            }
            return sameLocalityIndex;
        }

        private static int findLbEndpointIndex(LocalityLbEndpoints targetLocalityLbEndpoints,
                                               LocalityLbEndpoint localityLbEndpoint) {
            int sameLbEndpointIndex = -1;
            final List<LbEndpoint> lbEndpointsList = targetLocalityLbEndpoints.getLbEndpointsList();
            for (int i = 0; i < lbEndpointsList.size(); i++) {
                final LbEndpoint lbEndpoint = lbEndpointsList.get(i);
                if (lbEndpoint.getEndpoint().getAddress().equals(
                        localityLbEndpoint.getLbEndpoint().getEndpoint().getAddress())) {
                    sameLbEndpointIndex = i;
                    break;
                }
            }
            return sameLbEndpointIndex;
        }

        private static JsonNode toJsonNode(ClusterLoadAssignment.Builder clusterLoadAssignmentBuilder) {
            try {
                return Jackson.readTree(JSON_MESSAGE_MARSHALLER.writeValueAsString(
                        clusterLoadAssignmentBuilder.build()));
            } catch (IOException e) {
                // Should never reach here
                throw new Error(e);
            }
        }
    }

    private static final class EndpointIdentifier {

        static EndpointIdentifier of(LocalityLbEndpoint endpoint) {
            return new EndpointIdentifier(
                    endpoint.getLocality(),
                    endpoint.getPriority(),
                    endpoint.getLbEndpoint().getEndpoint().getAddress());
        }

        private final Locality locality;
        private final int priority;
        private final Address address;

        EndpointIdentifier(Locality locality, int priority, Address address) {
            this.locality = locality;
            this.priority = priority;
            this.address = address;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof EndpointIdentifier)) {
                return false;
            }
            final EndpointIdentifier that = (EndpointIdentifier) obj;
            return priority == that.priority &&
                   locality.equals(that.locality) &&
                   address.equals(that.address);
        }

        @Override
        public int hashCode() {
            return (locality.hashCode() * 31 + priority) * 31 + address.hashCode();
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("locality", locality)
                              .add("priority", priority)
                              .add("address", address)
                              .toString();
        }
    }
}
