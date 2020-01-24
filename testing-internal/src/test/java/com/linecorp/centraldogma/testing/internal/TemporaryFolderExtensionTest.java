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

package com.linecorp.centraldogma.testing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

class TemporaryFolderExtensionTest {

    @RegisterExtension
    final TemporaryFolderExtension extension = new TemporaryFolderExtension() {
        @Override
        public void before(ExtensionContext context) throws Exception {
            assertThat(exists()).isFalse();
            super.before(context);
            assertThat(exists()).isTrue();
        }

        @Override
        public void after(ExtensionContext context) throws Exception {
            assertThat(exists()).isTrue();
            super.after(context);
            assertThat(exists()).isFalse();
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void recreate() throws Exception {
        final String oldRoot = extension.getRoot().toString();

        extension.delete();
        assertThat(extension.exists()).isFalse();
        extension.create();
        assertThat(extension.exists()).isTrue();

        assertThat(extension.getRoot().toString()).isNotEqualTo(oldRoot);
    }

    @Test
    void newFolder() throws Exception {
        final Path root = extension.getRoot();
        final Path folder = extension.newFolder();

        assertThat(folder.toFile().exists());
        assertThat(Files.walk(root)).containsOnly(root, folder);
    }

    @Test
    void newFile() throws Exception {
        final Path root = extension.getRoot();
        final Path file = extension.newFile();

        assertThat(file.toFile().exists());
        assertThat(Files.walk(root)).containsOnly(root, file);
    }
}
