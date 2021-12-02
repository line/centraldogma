/*
 * Copyright 2021 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRequestPreparation;
import com.linecorp.centraldogma.client.CentralDogmaWatchingFileRequest;
import com.linecorp.centraldogma.client.CentralDogmaWatchingFilesRequest;
import com.linecorp.centraldogma.client.Latest;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class CentralDogmaWatchingRequestTest {

    @RegisterExtension
    static final CentralDogmaExtension centralDogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
            client.forRepo("foo", "bar")
                  .commit("commit2", ImmutableList.of(Change.ofJsonUpsert("/foo.json", "{ \"a\": \"b\" }")))
                  .push(Revision.HEAD)
                  .join();
        }
    };

    @Test
    void watch() throws Exception {
        final CentralDogmaRequestPreparation preparation = centralDogma.client().forRepo("foo", "bar");
        CompletableFuture<? extends Latest<?>> fileFuture =
                preparation.watchingFile("/foo.json")
                           .map(json -> {
                               assert json instanceof JsonNode;
                               return ((JsonNode) json).get("a").asText();
                           })
                           .watch(Revision.HEAD);
        checkWatchFuture(preparation, fileFuture, 3, "c", true);

        final CentralDogmaWatchingFileRequest<String> watchingFileRequest =
                preparation.watchingFile(Query.ofJson("/foo.json"))
                           .map(json -> json.get("a").asText());
        fileFuture = watchingFileRequest
                .watch(Revision.HEAD);
        checkWatchFuture(preparation, fileFuture, 4, "d", true);

        // multiple map
        final Watcher<Boolean> fileWatcher = watchingFileRequest.map(str -> 1)
                                                                .map(integer -> integer > 0)
                                                                .newWatcher();
        assertThat(fileWatcher.awaitInitialValue()).isEqualTo(new Latest<>(new Revision(4), true));
        fileWatcher.close();

        // files watch
        final CentralDogmaWatchingFilesRequest<String> watchingFilesRequest =
                preparation.watchingFiles("/**")
                           .map(revision -> {
                               final JsonNode content = (JsonNode) preparation.file(
                                                                                      "/foo.json").get(
                                                                                      revision).join()
                                                                              .content();
                               return content.get("a").asText();
                           });
        final CompletableFuture<String> filesFuture = watchingFilesRequest.watch(Revision.HEAD);
        checkWatchFuture(preparation, filesFuture, 5, "e", false);
        final Watcher<String> filesWatcher = watchingFilesRequest.newWatcher();
        assertThat(filesWatcher.awaitInitialValue()).isEqualTo(new Latest<>(new Revision(5), "e"));
        filesWatcher.close();
    }

    private static void checkWatchFuture(CentralDogmaRequestPreparation preparation,
                                         CompletableFuture<?> watchFuture,
                                         int revision, String newString, boolean file) {
        assertThat(watchFuture.isDone()).isFalse();
        assertThat(preparation.commit("commit", Change.ofJsonUpsert("/foo.json",
                                                                  "{ \"a\": \"" + newString + "\" }"))
                              .push(Revision.HEAD)
                              .join().revision()).isEqualTo(new Revision(revision));
        if (file) {
            assertThat(watchFuture.join()).isEqualTo(new Latest<>(new Revision(revision), newString));
        } else {
            assertThat(watchFuture.join()).isEqualTo(newString);
        }
    }
}
