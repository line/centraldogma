/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.mirror;

import java.io.File;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.model.Cron;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public final class HttpMirror extends AbstractMirror {

    private static final Logger logger = LoggerFactory.getLogger(HttpMirror.class);

    public HttpMirror(Cron schedule, MirrorDirection direction, MirrorCredential credential,
                      Repository localRepo, String localPath, URI remoteUri) {
        super(schedule, direction, credential, localRepo, localPath, remoteUri, null, null, null);
    }

    @Override
    protected void mirrorLocalToRemote(File workDir, int maxNumFiles, long maxNumBytes) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void mirrorRemoteToLocal(File workDir, CommandExecutor executor, int maxNumFiles,
                                       long maxNumBytes) throws Exception {
        final BlockingWebClient client = WebClient.builder()
                                                  .maxResponseLength(maxNumBytes)
                                                  .build()
                                                  .blocking();

        final AggregatedHttpResponse res = client.get(remoteRepoUri().toASCIIString());
        if (!res.status().isSuccess()) {
            throw HttpStatusException.of(res.status());
        }

        final MediaType contentType = res.contentType();
        final Charset charset;
        if (contentType != null) {
            charset = contentType.charset(StandardCharsets.UTF_8);
        } else {
            charset = StandardCharsets.UTF_8;
        }

        final String content = res.content(charset);
        final String summary = "Mirror " + remoteRepoUri() + " to the repository '" + localRepo().name() + '\'';
        logger.info(summary);

        executor.execute(Command.push(
                MIRROR_AUTHOR, localRepo().parent().name(), localRepo().name(),
                Revision.HEAD, summary, "", Markup.PLAINTEXT,
                Change.ofTextUpsert(localPath(), content))).join();
    }
}
