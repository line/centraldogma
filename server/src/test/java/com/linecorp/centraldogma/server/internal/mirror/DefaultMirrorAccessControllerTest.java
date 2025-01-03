/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.mirror;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.internal.api.sysadmin.MirrorAccessControlRequest;
import com.linecorp.centraldogma.testing.internal.CrudRepositoryExtension;

class DefaultMirrorAccessControllerTest {

    @RegisterExtension
    CrudRepositoryExtension<MirrorAccessControl> repositoryExtension =
            new CrudRepositoryExtension<MirrorAccessControl>(MirrorAccessControl.class, "test_proj",
                                                             "test_repo", "/test_path/") {
                @Override
                protected boolean runForEachTest() {
                    return true;
                }
            };

    private DefaultMirrorAccessController accessController;

    @BeforeEach
    void setUp() {
        accessController = new DefaultMirrorAccessController(repositoryExtension.crudRepository());
    }

    @Test
    void testAddAndUpdate() {
        final MirrorAccessControlRequest req0 = new MirrorAccessControlRequest("foo_1",
                                                                               "https://github.com/foo/*",
                                                                               true,
                                                                               "description",
                                                                               0);
        final MirrorAccessControl stored0 = accessController.add(req0, Author.SYSTEM).join();
        assertThat(stored0)
                .usingRecursiveComparison()
                .ignoringFields("creation")
                .isEqualTo(req0);

        final MirrorAccessControlRequest req1 = new MirrorAccessControlRequest("foo_1",
                                                                               "https://github.com/bar/*",
                                                                               true,
                                                                               "description",
                                                                               0);
        final MirrorAccessControl stored1 = accessController.update(req1, Author.SYSTEM).join();
        assertThat(stored1)
                .usingRecursiveComparison()
                .ignoringFields("creation")
                .isEqualTo(req1);
    }

    @Test
    void shouldControlAccess() {
        final MirrorAccessControlRequest req0 = new MirrorAccessControlRequest(
                "id_0", "https://private.github.com/.*", false, "default", Integer.MAX_VALUE);
        accessController.add(req0, Author.SYSTEM).join();
        assertThat(accessController.isAllowed("https://private.github.com/line/centraldogma").join()).isFalse();
        assertThat(accessController.isAllowed("https://github.com/line/centraldogma").join()).isTrue();

        final MirrorAccessControlRequest req1 = new MirrorAccessControlRequest(
                "id_1", "https://private.github.com/line/.*", true, "allow line org", 0);
        accessController.add(req1, Author.SYSTEM).join();
        assertThat(accessController.isAllowed("https://private.github.com/line/centraldogma").join()).isTrue();

        final MirrorAccessControlRequest req2 = new MirrorAccessControlRequest(
                "id_2", "https://private.github.com/line/armeria", false, "disallow armeria", 0);
        accessController.add(req2, Author.SYSTEM).join();
        assertThat(accessController.isAllowed("https://private.github.com/line/armeria").join()).isFalse();
        assertThat(accessController.isAllowed("https://private.github.com/line/centraldogma").join()).isTrue();
        assertThat(accessController.isAllowed("https://github.com/line/centraldogma").join()).isTrue();
        assertThat(accessController.isAllowed("https://private.github.com/dot/block").join()).isFalse();

        accessController.allow("https://private.github.com/dot/block", "allow dot/block", 0).join();
        assertThat(accessController.isAllowed("https://private.github.com/dot/block").join()).isTrue();
    }

    @Test
    void respectOrder() {
        final MirrorAccessControlRequest req0 = new MirrorAccessControlRequest(
                "id_0", "https://private.github.com/.*", false, "default", Integer.MAX_VALUE);
        accessController.add(req0, Author.SYSTEM).join();

        final MirrorAccessControlRequest req1 = new MirrorAccessControlRequest(
                "id_1", "https://private.github.com/line/centraldogma", true, "allow centraldogma", 0);
        final MirrorAccessControlRequest req2 = new MirrorAccessControlRequest(
                "id_2", "https://private.github.com/line/centraldogma", false, "disallow centraldogma", 1);
        accessController.add(req1, Author.SYSTEM).join();
        accessController.add(req2, Author.SYSTEM).join();
        assertThat(accessController.isAllowed("https://private.github.com/line/centraldogma").join()).isTrue();

        final MirrorAccessControlRequest req3 = new MirrorAccessControlRequest(
                "id_3", "https://private.github.com/line/centraldogma", false, "disallow centraldogma", -1);
        accessController.add(req3, Author.SYSTEM).join();
        assertThat(accessController.isAllowed("https://private.github.com/line/centraldogma").join()).isFalse();
    }

    @Test
    void testNewItemsHaveHigherPriority() {
        final MirrorAccessControlRequest req0 = new MirrorAccessControlRequest(
                "id_0", "https://private.github.com/.*", false, "default", Integer.MAX_VALUE);
        accessController.add(req0, Author.SYSTEM).join();

        final MirrorAccessControlRequest req1 = new MirrorAccessControlRequest(
                "id_1", "https://private.github.com/line/centraldogma", true, "allow centraldogma", 0);
        accessController.add(req1, Author.SYSTEM).join();
        assertThat(accessController.isAllowed("https://private.github.com/line/centraldogma").join()).isTrue();
        final MirrorAccessControlRequest req2 = new MirrorAccessControlRequest(
                "id_2", "https://private.github.com/line/centraldogma", false, "disallow centraldogma", 0);
        accessController.add(req2, Author.SYSTEM).join();
        assertThat(accessController.isAllowed("https://private.github.com/line/centraldogma").join()).isFalse();
        final MirrorAccessControlRequest req3 = new MirrorAccessControlRequest(
                "id_3", "https://private.github.com/line/centraldogma", true, "allow centraldogma", 0);
        accessController.add(req3, Author.SYSTEM).join();
        assertThat(accessController.isAllowed("https://private.github.com/line/centraldogma").join()).isTrue();
    }
}
