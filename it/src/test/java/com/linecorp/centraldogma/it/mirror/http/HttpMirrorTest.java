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
package com.linecorp.centraldogma.it.mirror.http;

import static com.google.common.base.MoreObjects.firstNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.TestUtil;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class HttpMirrorTest {

    private static final int MAX_NUM_BYTES = 1024; // 1 KiB

    private static final String REPO_FOO = "foo";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.mirroringEnabled(true);
            builder.maxNumBytesPerMirror(MAX_NUM_BYTES);
        }
    };

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new Object() {
                @Get("/get/:length")
                public String get(@Param int length) {
                    return Strings.repeat(".", length);
                }

                @Get("/204") // Generate a '204 No Content' response.
                public void respond204() {}

                @Get("/304")
                @StatusCode(304)
                public void respond304() {}
            });
        }
    };

    private static CentralDogma client;
    private static MirroringService mirroringService;

    @BeforeAll
    static void init() {
        client = dogma.client();
        mirroringService = dogma.mirroringService();
    }

    private String projName;

    @BeforeEach
    void initDogmaRepo(TestInfo testInfo) {
        projName = TestUtil.normalizedDisplayName(testInfo);
        client.createProject(projName).join();
        client.createRepository(projName, REPO_FOO).join();
    }

    @AfterEach
    void destroyDogmaRepo() {
        client.removeProject(projName).join();
    }

    @Test
    void simple() throws Exception {
        // Configure the server to mirror http://.../get/7 into /bar.txt.
        pushMirrorSettings(REPO_FOO, "/bar.txt", 7);
        testSuccessfulMirror(".......\n");
    }

    @Test
    void tooLargeContent() throws Exception {
        // Configure the server to mirror http://.../get/<MAX_NUM_BYTES + 1> into /bar.txt.
        pushMirrorSettings(REPO_FOO, "/bar.txt", MAX_NUM_BYTES + 1);
        testFailedMirror(ContentTooLargeException.class);
    }

    @Test
    void shouldHandle204() throws Exception {
        // Configure the server to mirror http://.../204 into /bar.txt.
        pushMirrorSettings(REPO_FOO, "/bar.txt", server.httpUri() + "/204");
        testSuccessfulMirror("");
    }

    @Test
    void shouldRejectNon2xx() throws Exception {
        // Configure the server to mirror http://.../get/<MAX_NUM_BYTES + 1> into /bar.txt.
        pushMirrorSettings(REPO_FOO, "/bar.txt", server.httpUri() + "/304");
        testFailedMirror(HttpStatusException.class);
    }

    private void pushMirrorSettings(String localRepo, @Nullable String localPath, int length) {
        pushMirrorSettings(localRepo, localPath, remoteUri(length));
    }

    private void pushMirrorSettings(String localRepo, @Nullable String localPath, String remoteUri) {
        client.forRepo(projName, Project.REPO_META)
              .commit("Add /mirrors.json",
                      Change.ofJsonUpsert("/mirrors.json",
                                          "[{" +
                                          "  \"type\": \"single\"," +
                                          "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                                          "  \"localRepo\": \"" + localRepo + "\"," +
                                          "  \"localPath\": \"" + firstNonNull(localPath, "/") + "\"," +
                                          "  \"remoteUri\": \"" + remoteUri + "\"," +
                                          "  \"schedule\": \"0 0 0 1 1 ? 2099\"" +
                                          "}]"))
              .push().join();
    }

    private void testSuccessfulMirror(String expectedContent) {
        // Trigger the mirroring task.
        mirroringService.mirror().join();

        // On successful mirroring, /bar.txt should contain 7 periods.
        final Entry<?> entry = client.forRepo(projName, REPO_FOO)
                                     .file("/bar.txt")
                                     .get().join();

        assertThat(entry.contentAsText()).isEqualTo(expectedContent);
    }

    private void testFailedMirror(Class<? extends Throwable> rootCause) {
        // Trigger the mirroring task, which will fail because the response was too large.
        assertThatThrownBy(() -> mirroringService.mirror().join()).cause().satisfies(cause -> {
            assertThat(cause).isInstanceOf(MirrorException.class)
                             .hasCauseInstanceOf(rootCause);
        });

        // As a result, /bar.txt shouldn't exist.
        assertThatThrownBy(() -> {
            client.forRepo(projName, REPO_FOO)
                  .file("/bar.txt")
                  .get().join();
        }).hasCauseInstanceOf(EntryNotFoundException.class);
    }

    private static String remoteUri(int length) {
        return String.format("%s/get/%d", server.httpUri(), length);
    }
}
