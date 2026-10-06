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

import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.centraldogma.internal.CredentialUtil;
import com.linecorp.centraldogma.internal.Jackson;

class MirrorRequestTest {

    @Test
    void mirrorRequest() {
        assertThatThrownBy(() -> newMirror("some-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid credentialName: some-id (expected: ");

        assertThatThrownBy(() -> newMirror("projects/bar/repos/bar/credentials/credential-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectName and credentialName do not match: ");

        assertThatThrownBy(() -> newMirror("projects/foo/repos/foo/credentials/credential-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repoName and credentialName do not match: ");

        String credentialName = CredentialUtil.credentialName("foo", "bar", "credential-id");
        assertThat(newMirror(credentialName).credentialName())
                .isEqualTo(credentialName);

        assertThatThrownBy(() -> newMirror("projects/bar/credentials/credential-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectName and credentialName do not match: ");

        credentialName = credentialName("foo", "credential-id");
        assertThat(newMirror(credentialName).credentialName())
                .isEqualTo(credentialName);
    }

    @Test
    void serializesHistoryAndTagOptionsOnlyWhenEnabled() throws Exception {
        final String credentialName = credentialName("foo", "credential-id");

        assertThat(Jackson.writeValueAsString(newMirror(credentialName, false, false)))
                .doesNotContain("preserveRemoteCommitHistory", "publishRemoteCommitTags");
        assertThat(Jackson.writeValueAsString(newMirror(credentialName, true, false)))
                .contains("\"preserveRemoteCommitHistory\":true")
                .doesNotContain("publishRemoteCommitTags");
        assertThat(Jackson.writeValueAsString(newMirror(credentialName, false, true)))
                .contains("\"publishRemoteCommitTags\":true")
                .doesNotContain("preserveRemoteCommitHistory");
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        final String credentialName = credentialName("foo", "credential-id");
        final MirrorRequest request = newMirror(credentialName, true, true);
        final MirrorDto dto = new MirrorDto(
                "mirror-id", true, "foo", "0/1 * * * * ?", "REMOTE_TO_LOCAL", "bar", "/",
                "git+ssh", "github.com/line/centraldogma-authtest.git", "/", "main", null,
                credentialName, null, true, true, true);

        assertIgnoresUnknownField(request, MirrorRequest.class);
        assertIgnoresUnknownField(dto, MirrorDto.class);
    }

    private static MirrorRequest newMirror(String credentialName) {
        return newMirror(credentialName, false, false);
    }

    private static MirrorRequest newMirror(String credentialName, boolean preserveRemoteCommitHistory,
                                           boolean publishRemoteCommitTags) {
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
                                 credentialName,
                                 null,
                                 preserveRemoteCommitHistory,
                                 publishRemoteCommitTags);
    }

    private static <T> void assertIgnoresUnknownField(T value, Class<T> type) throws Exception {
        final ObjectNode json = Jackson.valueToTree(value);
        json.put("futureField", true);
        assertThat(Jackson.treeToValue(json, type)).isEqualTo(value);
    }
}
