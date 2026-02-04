/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.CommitMessageDto;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.internal.api.v1.RevertRequest;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class RevertApiTest {

    private static final String REVERT_PREFIX = "/api/v1/projects/myPro/repos/myRepo/revert";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("myPro").join();
            client.createRepository("myPro", "myRepo").join();
        }
    };

    @Test
    void revertRestoresAndDeletes() throws Exception {
        final BlockingWebClient client = dogma.httpClient().blocking();
        final CentralDogmaRepository repo = dogma.client().forRepo("myPro", "myRepo");

        addFooJson(repo); // rev 2
        addBarTxt(repo);  // rev 3
        editFooJson(repo); // rev 4

        final ResponseEntity<PushResultDto> revertRes =
                client.prepare()
                      .post(REVERT_PREFIX)
                      .contentJson(revertRequest(2))
                      .asJson(PushResultDto.class)
                      .execute();
        assertThat(revertRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(revertRes.content().revision().major()).isEqualTo(5);

        final Entry<?> fooEntry = repo.file("/foo.json").get().join();
        assertThat(fooEntry.path()).isEqualTo("/foo.json");
        assertThat(fooEntry.type().name()).isEqualTo("JSON");
        assertThat(fooEntry.revision().major()).isEqualTo(5);
        assertThat(fooEntry.contentAsJson().get("a").asText()).isEqualTo("bar");

        assertThatThrownBy(() -> repo.file("/a/bar.txt").get().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(EntryNotFoundException.class);
    }

    @Test
    void revertRestoresDeletedFile() throws Exception {
        final BlockingWebClient client = dogma.httpClient().blocking();
        final CentralDogmaRepository repo = dogma.client().forRepo("myPro", "myRepo");

        addFooJson(repo); // rev 2
        removeFooJson(repo); // rev 3

        final ResponseEntity<PushResultDto> revertRes =
                client.prepare()
                      .post(REVERT_PREFIX)
                      .contentJson(revertRequest(2))
                      .asJson(PushResultDto.class)
                      .execute();
        assertThat(revertRes.status()).isEqualTo(HttpStatus.OK);

        final Entry<?> fooEntry = repo.file("/foo.json").get().join();
        assertThat(fooEntry.contentAsJson().get("a").asText()).isEqualTo("bar");
    }

    @Test
    void revertDeletesOnlyExtraFiles() throws Exception {
        final BlockingWebClient client = dogma.httpClient().blocking();
        final CentralDogmaRepository repo = dogma.client().forRepo("myPro", "myRepo");

        addFooJson(repo); // rev 2
        addBarTxt(repo); // rev 3

        final ResponseEntity<PushResultDto> revertRes =
                client.prepare()
                      .post(REVERT_PREFIX)
                      .contentJson(revertRequest(2))
                      .asJson(PushResultDto.class)
                      .execute();
        assertThat(revertRes.status()).isEqualTo(HttpStatus.OK);

        assertThatThrownBy(() -> repo.file("/a/bar.txt").get().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(EntryNotFoundException.class);

        final Entry<?> fooEntry = repo.file("/foo.json").get().join();
        assertThat(fooEntry.contentAsJson().get("a").asText()).isEqualTo("bar");
    }

    @Test
    void revertNoChangesThrowsRedundantChangeException() throws Exception {
        final BlockingWebClient client = dogma.httpClient().blocking();

        addFooJson(dogma.client().forRepo("myPro", "myRepo")); // rev 2

        final AggregatedHttpResponse res =
                client.prepare().post(REVERT_PREFIX).contentJson(revertRequest(2)).execute();
        assertThat(res.status()).isEqualTo(HttpStatus.CONFLICT);
        final JsonNode errorJson = Jackson.readTree(res.contentUtf8());
        assertThat(errorJson.get("exception").asText())
                .isEqualTo(RedundantChangeException.class.getName());
    }

    private static RevertRequest revertRequest(int targetRevision) {
        return new RevertRequest(targetRevision,
                                 new CommitMessageDto("Revert", "Revert to target", Markup.PLAINTEXT));
    }

    private static void addFooJson(CentralDogmaRepository repo) {
        repo.commit("Add foo.json", Change.ofJsonUpsert("/foo.json", "{ \"a\": \"bar\" }"))
            .push().join();
    }

    private static void editFooJson(CentralDogmaRepository repo) {
        repo.commit("Edit foo.json", Change.ofJsonUpsert("/foo.json", "{ \"a\": \"baz\" }"))
            .push().join();
    }

    private static void addBarTxt(CentralDogmaRepository repo) {
        repo.commit("Add bar.txt", Change.ofTextUpsert("/a/bar.txt", "text in the file.\n"))
            .push().join();
    }

    private static void removeFooJson(CentralDogmaRepository repo) {
        repo.commit("Remove foo.json", Change.ofRemoval("/foo.json"))
            .push().join();
    }
}
