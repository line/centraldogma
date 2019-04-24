/*
 * Copyright 2017 LINE Corporation
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

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Revision;

public class FileManagementTest extends AbstractMultiClientTest {

    private static final String TEST_ROOT = '/' + TestConstants.randomText() + '/';

    private static final int NUM_FILES = 10;

    @ClassRule
    public static final CentralDogmaRuleWithScaffolding rule = new CentralDogmaRuleWithScaffolding() {
        @Override
        protected void scaffold(CentralDogma client) {
            super.scaffold(client);

            for (int i = 0; i < NUM_FILES; i++) {
                client.push(project(), repo1(), Revision.HEAD,
                            "Put test files", Change.ofJsonUpsert(TEST_ROOT + i + ".json", "{}")).join();
            }
        }
    };

    public FileManagementTest(ClientType clientType) {
        super(clientType);
    }

    @Test
    public void testGetFiles() throws Exception {
        final Revision headRev = client().normalizeRevision(
                rule.project(), rule.repo1(), Revision.HEAD).join();
        final Map<String, Entry<?>> files = client().getFiles(
                rule.project(), rule.repo1(), Revision.HEAD, TEST_ROOT + "*.json").join();
        assertThat(files).hasSize(NUM_FILES);
        files.values().forEach(f -> {
            assertThat(f.revision()).isEqualTo(headRev);
            assertThatJson(f.content()).isEqualTo("{}");
        });
    }

    @Test
    public void testGetFilesWithDirectory() throws Exception {
        final String testRootWithoutSlash = TEST_ROOT.substring(0, TEST_ROOT.length() - 1);
        final Map<String, Entry<?>> files = client().getFiles(
                rule.project(), rule.repo1(), Revision.HEAD,
                testRootWithoutSlash + ", " + TEST_ROOT + '*').join();

        assertThat(files).hasSize(NUM_FILES + 1);

        final Entry<?> dir = files.get(testRootWithoutSlash);
        assertThat(dir.type()).isEqualTo(EntryType.DIRECTORY);
        assertThat(dir.path()).isEqualTo(testRootWithoutSlash);
        assertThat(dir.hasContent()).isFalse();
        assertThatThrownBy(dir::content).isInstanceOf(IllegalStateException.class);

        files.values().forEach(f -> {
            if (f.type() != EntryType.DIRECTORY) {
                assertThatJson(f.content()).isEqualTo("{}");
            }
        });
    }

    @Test
    public void testListFiles() throws Exception {
        final Map<String, EntryType> files = client().listFiles(
                rule.project(), rule.repo1(), Revision.HEAD, TEST_ROOT + "*.json").join();
        assertThat(files).hasSize(NUM_FILES);
        files.values().forEach(t -> assertThat(t).isEqualTo(EntryType.JSON));
    }

    @Test
    public void testListFilesEmpty() throws Exception {
        final Map<String, EntryType> files = client().listFiles(
                rule.project(), rule.repo1(), Revision.HEAD, TEST_ROOT + "*.none").join();
        assertThat(files).isEmpty();
    }

    @Test
    public void testListFilesSingle() throws Exception {
        final String path = TEST_ROOT + "0.json";
        final Map<String, EntryType> files = client().listFiles(
                rule.project(), rule.repo1(), Revision.HEAD, path).join();
        assertThat(files).hasSize(1);
        assertThat(files.get(path)).isEqualTo(EntryType.JSON);
    }
}
