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
package com.linecorp.centraldogma.server.internal.api;

import static java.util.Objects.requireNonNull;
import static org.eclipse.jgit.transport.GitProtocolConstants.COMMAND_FETCH;
import static org.eclipse.jgit.transport.GitProtocolConstants.COMMAND_LS_REFS;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_SHALLOW;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_WAIT_FOR_DONE;
import static org.eclipse.jgit.transport.GitProtocolConstants.VERSION_2_REQUEST;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UploadPack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ServerCacheControl;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.ByteStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresReadPermission;
import com.linecorp.centraldogma.server.internal.api.converter.HttpApiRequestConverter;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectApiManager;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.project.Project;

/**
 * A service that provides Git HTTP protocol.
 */
@RequestConverter(HttpApiRequestConverter.class)
@RequiresReadPermission
public final class GitHttpService {

    private static final Logger logger = LoggerFactory.getLogger(GitHttpService.class);

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    // TODO(minwoox): Add the headers in this class to Armeria.
    private static final AggregatedHttpResponse CAPABILITY_ADVERTISEMENT_RESPONSE = AggregatedHttpResponse.of(
            ResponseHeaders.builder(200)
                           .contentType(MediaType.GIT_UPLOAD_PACK_ADVERTISEMENT)
                           .add(HttpHeaderNames.CACHE_CONTROL, ServerCacheControl.REVALIDATED.asHeaderValue())
                           .build(),
            HttpData.ofUtf8(capabilityAdvertisement()));

    // https://git-scm.com/docs/protocol-capabilities/
    private static String capabilityAdvertisement() {
        final PacketLineFraming packetLineFraming = new PacketLineFraming();
        packetLineFraming.putWithoutPktLine("001e# service=git-upload-pack\n");
        packetLineFraming.flush();
        packetLineFraming.put("version 2");
        packetLineFraming.put(COMMAND_LS_REFS);
        // Support limited options for now due to the unique characteristics of Git repositories in
        // Central Dogma, such as having only a master branch and no tags, among other specifics.
        packetLineFraming.put(COMMAND_FETCH + '=' + OPTION_WAIT_FOR_DONE + ' ' + OPTION_SHALLOW);
        // TODO(minwoox): Migrate hash function https://git-scm.com/docs/hash-function-transition
        packetLineFraming.put("object-format=sha1");
        packetLineFraming.flush();
        return packetLineFraming.toString();
    }

    private final ProjectApiManager projectApiManager;

    public GitHttpService(ProjectApiManager projectApiManager) {
        this.projectApiManager = requireNonNull(projectApiManager, "projectApiManager");
    }

    // https://www.git-scm.com/docs/gitprotocol-http#_smart_clients
    @Get("/{projectName}/{repoName}/info/refs")
    public HttpResponse advertiseCapability(@Header("git-protocol") @Nullable String gitProtocol,
                                            @Param String service,
                                            @Param String projectName, @Param String repoName, User user) {
        repoName = maybeRemoveGitSuffix(repoName);
        if (!"git-upload-pack".equals(service)) {
            // Return 403 https://www.git-scm.com/docs/http-protocol#_smart_server_response
            return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.PLAIN_TEXT_UTF_8,
                                   "Unsupported service: " + service);
        }

        if (gitProtocol == null || !gitProtocol.contains(VERSION_2_REQUEST)) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                   "Unsupported git-protocol: " + gitProtocol);
        }

        if (!projectApiManager.exists(projectName)) {
            return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.PLAIN_TEXT_UTF_8,
                                   "Project not found: " + projectName);
        }
        if (!projectApiManager.getProject(projectName, user).repos().exists(repoName)) {
            return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.PLAIN_TEXT_UTF_8,
                                   "Repository not found: " + repoName);
        }
        return CAPABILITY_ADVERTISEMENT_RESPONSE.toHttpResponse();
    }

    private static String maybeRemoveGitSuffix(String repoName) {
        if (repoName.length() >= 5 && repoName.endsWith(".git")) {
            repoName = repoName.substring(0, repoName.length() - 4);
        }
        return repoName;
    }

    // https://www.git-scm.com/docs/gitprotocol-http#_smart_service_git_upload_pack
    @Post("/{projectName}/{repoName}/git-upload-pack")
    public HttpResponse gitUploadPack(AggregatedHttpRequest req,
                                      @Param String projectName, @Param String repoName, User user) {
        repoName = maybeRemoveGitSuffix(repoName);
        final String gitProtocol = req.headers().get(HttpHeaderNames.GIT_PROTOCOL);
        if (gitProtocol == null || !gitProtocol.contains(VERSION_2_REQUEST)) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                   "Unsupported git-protocol: " + gitProtocol);
        }

        final MediaType contentType = req.headers().contentType();
        if (MediaType.GIT_UPLOAD_PACK_REQUEST != contentType) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                   "Unsupported content-type: " + contentType);
        }

        if (!projectApiManager.exists(projectName)) {
            return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.PLAIN_TEXT_UTF_8,
                                   "Project not found: " + projectName);
        }
        final Project project = projectApiManager.getProject(projectName, user);
        if (!project.repos().exists(repoName)) {
            return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.PLAIN_TEXT_UTF_8,
                                   "Repository not found: " + repoName);
        }

        final Repository jGitRepository = project.repos().get(repoName).jGitRepository();

        final ByteStreamMessage body = StreamMessage.fromOutputStream(os -> {
            // Don't need to close the input stream.
            final ByteArrayInputStream inputStream = new ByteArrayInputStream(req.content().byteBuf().array());
            // Don't need to close because we don't use the timer inside it.
            final UploadPack uploadPack = new UploadPack(jGitRepository);
            uploadPack.setTimeout(0); // Disable timeout because Armeria server will handle it.
            // HTTP does not use bidirectional pipe.
            uploadPack.setBiDirectionalPipe(false);
            uploadPack.setExtraParameters(ImmutableList.of(VERSION_2_REQUEST));
            try {
                uploadPack.upload(inputStream, os, null);
            } catch (IOException e) {
                // Log until https://github.com/line/centraldogma/pull/719 is implemented.
                logger.debug("Failed to respond git-upload-pack-request: {}", req.contentUtf8(), e);
                throw new RuntimeException("failed to respond git-upload-pack-request: " +
                                           req.contentUtf8(), e);
            }
            try {
                os.close();
            } catch (IOException e) {
                // Should never reach here because StreamWriterOutputStream.close() never throws an exception.
                logger.warn("Failed to close the output stream. request: {}", req.contentUtf8(), e);
            }
        });
        return HttpResponse.of(
                ResponseHeaders.builder(200)
                               .contentType(MediaType.GIT_UPLOAD_PACK_RESULT)
                               .add(HttpHeaderNames.CACHE_CONTROL,
                                    ServerCacheControl.REVALIDATED.asHeaderValue())
                               .build(), body);
    }

    static class PacketLineFraming {
        private final StringBuilder sb = new StringBuilder();

        // https://git-scm.com/docs/protocol-common#_pkt_line_format
        void put(String line) {
            lineLength(sb, line.getBytes(StandardCharsets.UTF_8).length + 5);
            sb.append(line).append('\n');
        }

        private static void lineLength(StringBuilder sb, int length) {
            for (int i = 3; i >= 0; i--) {
                sb.append(HEX_DIGITS[(length >>> (4 * i)) & 0xf]);
            }
        }

        void putWithoutPktLine(String line) {
            sb.append(line);
        }

        void delim() {
            sb.append("0001");
        }

        void flush() {
            // https: //git-scm.com/docs/protocol-v2/2.31.0#_packet_line_framing
            sb.append("0000");
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
