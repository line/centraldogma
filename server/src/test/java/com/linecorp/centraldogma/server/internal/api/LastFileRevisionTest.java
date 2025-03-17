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

package com.linecorp.centraldogma.server.internal.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class LastFileRevisionTest {

    @RegisterExtension
    final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void shouldFillFileRevisions() {
        final CentralDogmaRepository repo = dogma.client().forRepo("foo", "bar");
        final PushResult resultA = repo.commit("add a file", Change.ofTextUpsert("/a.txt", "aaa"))
                                       .push().join();
        final PushResult resultB = repo.commit("add a file", Change.ofTextUpsert("/b.txt", "bbb"))
                                       .push().join();
        assertThat(resultB.revision().major()).isEqualTo(3);
        final PushResult resultC = repo.commit("add a file", Change.ofTextUpsert("/a/c.txt", "ccc")).push()
                                       .join();
        final PushResult resultD = repo.commit("add a file", Change.ofTextUpsert("/a/c/d.txt", "ddd")).push()
                                       .join();
        assertThat(resultD.revision().major()).isEqualTo(5);
        final PushResult resultE = repo.commit("add a file", Change.ofTextUpsert("/a/c/d/e.txt", "eee")).push()
                                       .join();
        final PushResult resultB1 = repo.commit("update a file", Change.ofTextUpsert("/b.txt", "bbbb"))
                                        .push().join();
        final PushResult resultD1 = repo.commit("update a file", Change.ofTextUpsert("/a/c/d.txt", "dddd"))
                                        .push().join();

        final Map<String, Entry<?>> listFilesWithLastRevision = repo.file(PathPattern.all())
                                                                    .includeLastFileRevision(10)
                                                                    .list().join();
        Entry<?> entryA = listFilesWithLastRevision.get("/a.txt");
        assertThat(entryA.revision()).isEqualTo(resultA.revision());
        assertThat(entryA.hasContent()).isFalse();

        Entry<?> entryB = listFilesWithLastRevision.get("/b.txt");
        assertThat(entryB.revision()).isEqualTo(resultB1.revision());
        assertThat(entryB.hasContent()).isFalse();

        Entry<?> entryC = listFilesWithLastRevision.get("/a/c.txt");
        assertThat(entryC.revision()).isEqualTo(resultC.revision());
        assertThat(entryC.hasContent()).isFalse();

        Entry<?> entryD = listFilesWithLastRevision.get("/a/c/d.txt");
        assertThat(entryD.revision()).isEqualTo(resultD1.revision());
        assertThat(entryD.hasContent()).isFalse();

        Entry<?> entryE = listFilesWithLastRevision.get("/a/c/d/e.txt");
        assertThat(entryE.revision()).isEqualTo(resultE.revision());
        assertThat(entryE.hasContent()).isFalse();

        final Map<String, Entry<?>> getFilesWithLastRevision = repo.file(PathPattern.all())
                                                                   .includeLastFileRevision(10)
                                                                   .get().join();

        entryA = getFilesWithLastRevision.get("/a.txt");
        assertThat(entryA.revision()).isEqualTo(resultA.revision());
        assertThat(entryA.contentAsText().trim()).isEqualTo("aaa");

        entryB = getFilesWithLastRevision.get("/b.txt");
        assertThat(entryB.revision()).isEqualTo(resultB1.revision());
        assertThat(entryB.contentAsText().trim()).isEqualTo("bbbb");

        entryC = getFilesWithLastRevision.get("/a/c.txt");
        assertThat(entryC.revision()).isEqualTo(resultC.revision());
        assertThat(entryC.contentAsText().trim()).isEqualTo("ccc");

        entryD = getFilesWithLastRevision.get("/a/c/d.txt");
        assertThat(entryD.revision()).isEqualTo(resultD1.revision());
        assertThat(entryD.contentAsText().trim()).isEqualTo("dddd");

        entryE = getFilesWithLastRevision.get("/a/c/d/e.txt");
        assertThat(entryE.revision()).isEqualTo(resultE.revision());
        assertThat(entryE.contentAsText().trim()).isEqualTo("eee");

        final Map<String, Entry<?>> content = repo.file(PathPattern.all()).get().join();
        content.forEach((path, entry) -> {
            assertThat(entry.revision()).isEqualTo(resultD1.revision());
        });
    }

    @Test
    void shouldFillInitRevisionOnMissing() {
        final CentralDogmaRepository repo = dogma.client().forRepo("foo", "bar");
        final PushResult resultA = repo.commit("add a file", Change.ofTextUpsert("/a.txt", "aaa"))
                                       .push().join();
        final PushResult resultB = repo.commit("add a file", Change.ofTextUpsert("/b.txt", "bbb"))
                                       .push().join();
        final PushResult resultB1 = repo.commit("update a file", Change.ofTextUpsert("/b.txt", "bbbb"))
                                        .push().join();

        final Map<String, Entry<?>> getFilesWithLastRevision = repo.file(PathPattern.all())
                                                                   .includeLastFileRevision(2)
                                                                   .get().join();
        Entry<?> entryA = getFilesWithLastRevision.get("/a.txt");
        assertThat(entryA.revision()).isEqualTo(Revision.INIT);
        assertThat(entryA.contentAsText().trim()).isEqualTo("aaa");

        Entry<?> entryB = getFilesWithLastRevision.get("/b.txt");
        assertThat(entryB.revision()).isEqualTo(resultB1.revision());
        assertThat(entryB.contentAsText().trim()).isEqualTo("bbbb");

        final Map<String, Entry<?>> listFilesWithLastRevision = repo.file(PathPattern.all())
                                                                    .includeLastFileRevision(2)
                                                                    .list().join();

        entryA = listFilesWithLastRevision.get("/a.txt");
        assertThat(entryA.revision()).isEqualTo(Revision.INIT);
        assertThat(entryA.hasContent()).isFalse();

        entryB = listFilesWithLastRevision.get("/b.txt");
        assertThat(entryB.revision()).isEqualTo(resultB1.revision());
        assertThat(entryB.hasContent()).isFalse();
    }
}
