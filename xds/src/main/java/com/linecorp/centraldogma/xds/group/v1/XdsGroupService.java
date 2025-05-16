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
package com.linecorp.centraldogma.xds.group.v1;

import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.server.internal.api.RepositoryServiceUtil.createRepository;
import static com.linecorp.centraldogma.server.internal.api.RepositoryServiceUtil.removeRepository;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.checkGroupId;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.removePrefix;

import com.google.protobuf.Empty;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.xds.group.v1.XdsGroupServiceGrpc.XdsGroupServiceImplBase;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * An {@link XdsGroupServiceImplBase} implementation that provides methods to manage XDS groups.
 */
public final class XdsGroupService extends XdsGroupServiceImplBase {

    private final ProjectManager projectManager;
    private final CommandExecutor commandExecutor;
    private final MetadataService mds;

    /**
     * Creates a new instance.
     */
    public XdsGroupService(ProjectManager projectManager, CommandExecutor commandExecutor,
                           InternalProjectInitializer internalProjectInitializer) {
        this.projectManager = projectManager;
        this.commandExecutor = commandExecutor;
        mds = new MetadataService(projectManager, commandExecutor, internalProjectInitializer);
    }

    @Override
    public void createGroup(CreateGroupRequest request,
                            StreamObserver<Group> responseObserver) {
        final String groupId = request.getGroupId();
        checkGroupId(groupId);
        if (projectManager.get(XDS_CENTRAL_DOGMA_PROJECT).repos().exists(groupId)) {
            throw alreadyExistsException(groupId);
        }
        createRepository(commandExecutor, mds, currentAuthor(), XDS_CENTRAL_DOGMA_PROJECT, groupId, false, null)
                .handle((unused, cause) -> {
                    if (cause != null) {
                        final Throwable peeled = Exceptions.peel(cause);
                        if (peeled instanceof RepositoryExistsException) {
                            responseObserver.onError(alreadyExistsException(groupId));
                        } else {
                            responseObserver.onError(
                                    Status.INTERNAL.withCause(peeled).asRuntimeException());
                        }
                        return null;
                    }
                    responseObserver.onNext(Group.newBuilder().setName("groups/" + groupId).build());
                    responseObserver.onCompleted();
                    return null;
                });
    }

    private static RuntimeException alreadyExistsException(String groupName) {
        return Status.ALREADY_EXISTS.withDescription("Group already exists: " + groupName)
                                    .asRuntimeException();
    }

    @Override
    public void deleteGroup(DeleteGroupRequest request, StreamObserver<Empty> responseObserver) {
        final String groupName = request.getName();
        final String name = removePrefix("groups/", groupName);
        if (!projectManager.get(XDS_CENTRAL_DOGMA_PROJECT).repos().exists(name)) {
            throw Status.NOT_FOUND.withDescription("Group does not exist: " + groupName)
                                  .asRuntimeException();
        }
        if (Project.isInternalRepo(name)) {
            throw Status.PERMISSION_DENIED.withDescription("Now allowed to delete " + groupName)
                                          .asRuntimeException();
        }

        // TODO(minwoox): Check the permission.
        removeRepository(commandExecutor, mds, currentAuthor(), XDS_CENTRAL_DOGMA_PROJECT, name)
                .handle((unused, cause1) -> {
                    if (cause1 != null) {
                        responseObserver.onError(
                                Status.INTERNAL.withDescription("Failed to delete " + groupName)
                                               .withCause(cause1).asRuntimeException());
                        return null;
                    }
                    responseObserver.onNext(Empty.getDefaultInstance());
                    responseObserver.onCompleted();
                    return null;
                });
    }
}
