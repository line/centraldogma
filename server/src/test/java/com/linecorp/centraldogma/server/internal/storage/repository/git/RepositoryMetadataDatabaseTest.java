/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static com.linecorp.centraldogma.server.internal.storage.repository.git.RepositoryMetadataDatabase.addOne;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryMetadataDatabaseTest {

    @TempDir
    File tempDir;

    @Nullable
    private RepositoryMetadataDatabase db;

    @BeforeEach
    void setUp() {
        db = new RepositoryMetadataDatabase(tempDir, true);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void secondaryDirIsGreaterThanPrimaryByOne() {
        assertThat(db.primaryRepoDir().getName()).isEqualTo(tempDir.getName() + "_0000000000");
        assertThat(db.secondaryRepoDir().getName()).isEqualTo(tempDir.getName() + "_0000000001");

        db.setPrimaryRepoDir(new File(tempDir, tempDir.getName() + "_0000000001"));
        assertThat(db.primaryRepoDir().getName()).isEqualTo(tempDir.getName() + "_0000000001");
        assertThat(db.secondaryRepoDir().getName()).isEqualTo(tempDir.getName() + "_0000000002");
    }

    @Test
    void nextPrimaryRepoShouldBeGreaterByOne() {
        assertThat(db.primaryRepoDir().getName()).isEqualTo(tempDir.getName() + "_0000000000");
        assertThat(db.secondaryRepoDir().getName()).isEqualTo(tempDir.getName() + "_0000000001");

        assertThatThrownBy(() -> db.setPrimaryRepoDir(new File(tempDir, tempDir.getName() + "_1111111111")))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void addOneToSuffixTest() {
        String suffix = "0000000000";
        assertThat(addOne(suffix)).isEqualTo("0000000001");

        suffix = "0000000009";
        assertThat(addOne(suffix)).isEqualTo("0000000010");

        suffix = "0000000099";
        assertThat(addOne(suffix)).isEqualTo("0000000100");

        suffix = "1111111111";
        assertThat(addOne(suffix)).isEqualTo("1111111112");
    }
}
