/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.centraldogma.it;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNoContentException;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Revision;

class FileManagementTest {

    private static final String TEST_ROOT = '/' + TestConstants.randomText() + '/';

    private static final int NUM_FILES = 10;

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding() {
        @Override
        protected void scaffold(CentralDogma client) {
            super.scaffold(client);

            for (int i = 0; i < NUM_FILES; i++) {
                client.forRepo(project(), repo1())
                      .commit("Put test files", Change.ofJsonUpsert(TEST_ROOT + i + ".json", "{}"))
                      .push(Revision.HEAD).join();
            }
        }
    };

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void getFiles(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final Revision headRev = client.normalizeRevision(
                dogma.project(), dogma.repo1(), Revision.HEAD).join();
        final Map<String, Entry<?>> files = client.getFiles(
                dogma.project(), dogma.repo1(), Revision.HEAD, PathPattern.of(TEST_ROOT + "*.json")).join();
        assertThat(files).hasSize(NUM_FILES);
        files.values().forEach(f -> {
            assertThat(f.revision()).isEqualTo(headRev);
            assertThatJson(f.content()).isEqualTo("{}");
        });
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void getFilesWithDirectory(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final String testRootWithoutSlash = TEST_ROOT.substring(0, TEST_ROOT.length() - 1);
        final Map<String, Entry<?>> files = client.getFiles(
                dogma.project(), dogma.repo1(), Revision.HEAD,
                PathPattern.of(testRootWithoutSlash, TEST_ROOT + '*')).join();

        assertThat(files).hasSize(NUM_FILES + 1);

        final Entry<?> dir = files.get(testRootWithoutSlash);
        assertThat(dir.type()).isEqualTo(EntryType.DIRECTORY);
        assertThat(dir.path()).isEqualTo(testRootWithoutSlash);
        assertThat(dir.hasContent()).isFalse();
        assertThatThrownBy(dir::content).isInstanceOf(EntryNoContentException.class);

        files.values().forEach(f -> {
            if (f.type() != EntryType.DIRECTORY) {
                assertThatJson(f.content()).isEqualTo("{}");
            }
        });
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void listFiles(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final Map<String, EntryType> files = client.listFiles(
                dogma.project(), dogma.repo1(), Revision.HEAD, PathPattern.of(TEST_ROOT + "*.json")).join();
        assertThat(files).hasSize(NUM_FILES);
        files.values().forEach(t -> assertThat(t).isEqualTo(EntryType.JSON));
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void listFilesEmpty(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final Map<String, EntryType> files = client.listFiles(
                dogma.project(), dogma.repo1(), Revision.HEAD, PathPattern.of(TEST_ROOT + "*.none")).join();
        assertThat(files).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(ClientType.class)
    void listFilesSingle(ClientType clientType) {
        final CentralDogma client = clientType.client(dogma);
        final String path = TEST_ROOT + "0.json";
        final Map<String, EntryType> files = client.listFiles(
                dogma.project(), dogma.repo1(), Revision.HEAD, PathPattern.of(path)).join();
        assertThat(files).hasSize(1);
        assertThat(files.get(path)).isEqualTo(EntryType.JSON);
    }
}
