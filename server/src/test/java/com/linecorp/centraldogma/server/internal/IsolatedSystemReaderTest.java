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
package com.linecorp.centraldogma.server.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.Test;

class IsolatedSystemReaderTest {
    static {
        IsolatedSystemReader.install();
    }

    @Test
    void defaultSettings() throws Exception {
        final SystemReader reader = SystemReader.getInstance();
        assertThat(reader).isInstanceOf(IsolatedSystemReader.class);

        // Make sure all the necessary properties are set.
        final StoredConfig config = reader.getUserConfig();
        final String configText = config.toText();
        assertThat(configText).isEqualTo("[core]\n" +
                "\trepositoryformatversion = 1\n" +
                "\thidedotfiles = false\n" +
                "\tsymlinks = false\n" +
                "\tfilemode = false\n" +
                "[commit]\n" +
                "\tgpgSign = false\n" +
                "[diff]\n" +
                "\talgorithm = histogram\n" +
                "\trenames = false\n" +
                "[gc]\n" +
                "\tautopacklimit = 0\n" +
                "\tauto = 0\n");

        // Make sure user, system and jGit configs are all same with each other.
        assertThat(reader.getSystemConfig().toText()).isEqualTo(configText);
        assertThat(reader.getJGitConfig().toText()).isEqualTo(configText);
    }
}
