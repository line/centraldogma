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
 *
 */
package com.linecorp.centraldogma.internal.api.v1;

import static com.linecorp.centraldogma.internal.CredentialUtil.projectCredentialResourceName;
import static com.linecorp.centraldogma.internal.CredentialUtil.repoCredentialResourceName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MirrorRequestTest {

    @Test
    void mirrorRequest() {
        assertThatThrownBy(() -> newMirror("some-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid credentialResourceName: some-id (expected: ");

        assertThatThrownBy(() -> newMirror("projects/bar/repos/bar/credentials/credential-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectName and credentialResourceName do not match: ");

        assertThatThrownBy(() -> newMirror("projects/foo/repos/foo/credentials/credential-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repoName and credentialResourceName do not match: ");

        String credentialResourceName = repoCredentialResourceName("foo", "bar", "credential-id");
        assertThat(newMirror(credentialResourceName).credentialResourceName())
                .isEqualTo(credentialResourceName);

        assertThatThrownBy(() -> newMirror("projects/bar/credentials/credential-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectName and credentialResourceName do not match: ");

        credentialResourceName = projectCredentialResourceName("foo", "credential-id");
        assertThat(newMirror(credentialResourceName).credentialResourceName())
                .isEqualTo(credentialResourceName);
    }

    private static MirrorRequest newMirror(String credentialResourceName) {
        return new MirrorRequest("mirror-id",
                                 true,
                                 "foo",
                                 "0/1 * * * * ?",
                                 "REMOTE_TO_LOCAL",
                                 "bar",
                                 "/",
                                 "git+ssh",
                                 "github.com/line/centraldogma-authtest.git",
                                 "/",
                                 "main",
                                 null,
                                 null,
                                 credentialResourceName,
                                 null);
    }
}
