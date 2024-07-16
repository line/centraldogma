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
package com.linecorp.centraldogma.xds.application.v1;

import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.server.internal.api.RepositoryServiceUtil.createRepository;
import static com.linecorp.centraldogma.server.internal.api.RepositoryServiceUtil.removeRepository;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;

import com.google.protobuf.Empty;

import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.xds.application.v1.XdsApplicationServiceGrpc.XdsApplicationServiceImplBase;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

/**
 * An {@link XdsApplicationServiceImplBase} implementation that provides methods to manage XDS applications.
 */
public final class XdsApplicationService extends XdsApplicationServiceImplBase {

    private final ProjectManager projectManager;
    private final CommandExecutor commandExecutor;
    private final MetadataService mds;

    /**
     * Creates a new instance.
     */
    public XdsApplicationService(ProjectManager projectManager, CommandExecutor commandExecutor) {
        this.projectManager = projectManager;
        this.commandExecutor = commandExecutor;
        mds = new MetadataService(projectManager, commandExecutor);
    }

    @Override
    public void createApplication(CreateApplicationRequest request,
                                  StreamObserver<Application> responseObserver) {
        final String applicationName = request.getApplication().getName();
        final String name = removePrefix("applications/", applicationName);
        if (projectManager.get(XDS_CENTRAL_DOGMA_PROJECT).repos().exists(name)) {
            throwAlreadyExists(name);
        }
        createRepository(commandExecutor, mds, currentAuthor(), XDS_CENTRAL_DOGMA_PROJECT, name)
                .handle((unused, cause) -> {
                    if (cause != null) {
                        if (cause instanceof RepositoryExistsException) {
                            throwAlreadyExists(name);
                        }
                        responseObserver.onError(
                                Status.INTERNAL.withCause(cause).asRuntimeException());
                        return null;
                    }
                    responseObserver.onNext(Application.newBuilder().setName(applicationName).build());
                    responseObserver.onCompleted();
                    return null;
                });
    }

    private static String removePrefix(String prefix, String name) {
        if (!name.startsWith(prefix)) {
            throw new StatusRuntimeException(
                    Status.INVALID_ARGUMENT.withDescription(name + " does not start with prefix: " + prefix));
        }
        return name.substring(prefix.length());
    }

    private static void throwAlreadyExists(String applicationName) {
        throw Status.ALREADY_EXISTS.withDescription("Application already exists: " + applicationName)
                                   .asRuntimeException();
    }

    @Override
    public void deleteApplication(DeleteApplicationRequest request, StreamObserver<Empty> responseObserver) {
        final String applicationName = request.getName();
        final String name = removePrefix("applications/", applicationName);
        if (!projectManager.get(XDS_CENTRAL_DOGMA_PROJECT).repos().exists(name)) {
            throw Status.NOT_FOUND.withDescription("Application does not exist: " + applicationName)
                                  .asRuntimeException();
        }
        if (Project.isReservedRepoName(name)) {
            throw Status.PERMISSION_DENIED.withDescription("Now allowed to delete " + applicationName)
                                          .asRuntimeException();
        }

        // TODO(minwoox): Check the permission.
        removeRepository(commandExecutor, mds, currentAuthor(), XDS_CENTRAL_DOGMA_PROJECT, name)
                .handle((unused, cause1) -> {
                    if (cause1 != null) {
                        responseObserver.onError(
                                Status.INTERNAL.withDescription("Failed to delete " + applicationName)
                                               .withCause(cause1).asRuntimeException());
                    }
                    responseObserver.onNext(Empty.getDefaultInstance());
                    responseObserver.onCompleted();
                    return null;
                });
    }
}
