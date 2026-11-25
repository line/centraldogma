/*
 * Copyright 2026 LINE Corporation
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

import static com.linecorp.centraldogma.server.internal.api.ContentServiceV1.checkMetaRepoPush;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.InvalidPushException;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.project.Project;

/**
 * Verifies the meta/dogma repository push restriction enforced by
 * {@link ContentServiceV1#checkMetaRepoPush(User, String, Iterable)}.
 *
 * <p>The restriction is relaxed only for mirror and credential files pushed by a system
 * administrator. A regular file remains reserved for internal usage and is rejected for
 * everyone, including a system administrator.
 */
class CheckMetaRepoPushTest {

    private static final String REGULAR_FILE = "/foo.json";
    private static final String METADATA_FILE = "/metadata.json";
    private static final String MIRROR_FILE = "/repos/foo/mirrors/bar.json";
    private static final String REPO_CREDENTIAL_FILE = "/repos/foo/credentials/bar.json";
    private static final String PROJECT_CREDENTIAL_FILE = "/credentials/bar.json";

    @ParameterizedTest
    @ValueSource(strings = { Project.REPO_DOGMA, Project.REPO_META })
    void systemAdminMayPushMirrorAndCredentialFiles(String repoName) {
        assertThatCode(() -> checkMetaRepoPush(User.SYSTEM_ADMIN, repoName, changes(MIRROR_FILE)))
                .doesNotThrowAnyException();
        assertThatCode(() -> checkMetaRepoPush(User.SYSTEM_ADMIN, repoName, changes(REPO_CREDENTIAL_FILE)))
                .doesNotThrowAnyException();
        assertThatCode(() -> checkMetaRepoPush(User.SYSTEM_ADMIN, repoName, changes(PROJECT_CREDENTIAL_FILE)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = { Project.REPO_DOGMA, Project.REPO_META })
    void systemAdminMayNotPushRegularFile(String repoName) {
        // A regular file is reserved for internal usage even for a system administrator.
        assertThatThrownBy(() -> checkMetaRepoPush(User.SYSTEM_ADMIN, repoName, changes(REGULAR_FILE)))
                .isInstanceOf(InvalidPushException.class)
                .hasMessageContaining("reserved for internal usage");
    }

    @ParameterizedTest
    @ValueSource(strings = { Project.REPO_DOGMA, Project.REPO_META })
    void nonAdminMayNotPushMirrorOrCredentialFiles(String repoName) {
        for (String path : ImmutableList.of(MIRROR_FILE, REPO_CREDENTIAL_FILE, PROJECT_CREDENTIAL_FILE)) {
            assertThatThrownBy(() -> checkMetaRepoPush(User.DEFAULT, repoName, changes(path)))
                    .isInstanceOf(InvalidPushException.class)
                    .hasMessageContaining("Mirror and credential files cannot be modified via the push API");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { Project.REPO_DOGMA, Project.REPO_META })
    void nonAdminMayNotPushRegularFile(String repoName) {
        assertThatThrownBy(() -> checkMetaRepoPush(User.DEFAULT, repoName, changes(REGULAR_FILE)))
                .isInstanceOf(InvalidPushException.class)
                .hasMessageContaining("reserved for internal usage");
    }

    @ParameterizedTest
    @ValueSource(strings = { Project.REPO_DOGMA, Project.REPO_META })
    void anyoneMayPushMetadataFile(String repoName) {
        assertThatCode(() -> checkMetaRepoPush(User.SYSTEM_ADMIN, repoName, changes(METADATA_FILE)))
                .doesNotThrowAnyException();
        assertThatCode(() -> checkMetaRepoPush(User.DEFAULT, repoName, changes(METADATA_FILE)))
                .doesNotThrowAnyException();
        // The Thrift push path passes a null user.
        assertThatCode(() -> checkMetaRepoPush(null, repoName, changes(METADATA_FILE)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = { Project.REPO_DOGMA, Project.REPO_META })
    void nullUserIsNotTreatedAsSystemAdmin(String repoName) {
        // The Thrift push path passes a null user, which must not be granted the system-admin relaxation.
        assertThatThrownBy(() -> checkMetaRepoPush(null, repoName, changes(MIRROR_FILE)))
                .isInstanceOf(InvalidPushException.class)
                .hasMessageContaining("Mirror and credential files cannot be modified via the push API");
        assertThatThrownBy(() -> checkMetaRepoPush(null, repoName, changes(REGULAR_FILE)))
                .isInstanceOf(InvalidPushException.class)
                .hasMessageContaining("reserved for internal usage");
    }

    @Test
    void nonMetaRepositoryHasNoRestriction() {
        // A regular repository accepts any file regardless of the user.
        assertThatCode(() -> checkMetaRepoPush(User.DEFAULT, "myRepo", changes(REGULAR_FILE)))
                .doesNotThrowAnyException();
        assertThatCode(() -> checkMetaRepoPush(null, "myRepo", changes(MIRROR_FILE)))
                .doesNotThrowAnyException();
    }

    private static Iterable<Change<?>> changes(String path) {
        return ImmutableList.of(Change.ofJsonUpsert(path, "{}"));
    }
}
