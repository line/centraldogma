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

package com.linecorp.centraldogma.internal.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.internal.thrift.CentralDogmaService.diffFile_args;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService.getDiffs_args;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService.getHistory_args;

/**
 * Makes sure {@code fromRevision} and {@code toRevision} fields are renamed to {@code from} and {@code to}
 * during the build process.
 */
class ThriftTextCompatibilityTest {

    @Test
    void test() {
        assertThat(getDiffs_args._Fields.findByName("fromRevision")).isNull();
        assertThat(getDiffs_args._Fields.findByName("from")).isNotNull();
        assertThat(getDiffs_args._Fields.findByName("toRevision")).isNull();
        assertThat(getDiffs_args._Fields.findByName("to")).isNotNull();
        assertThat(getHistory_args._Fields.findByName("fromRevision")).isNull();
        assertThat(getHistory_args._Fields.findByName("from")).isNotNull();
        assertThat(getHistory_args._Fields.findByName("toRevision")).isNull();
        assertThat(getHistory_args._Fields.findByName("to")).isNotNull();
        assertThat(diffFile_args._Fields.findByName("fromRevision")).isNull();
        assertThat(diffFile_args._Fields.findByName("from")).isNotNull();
        assertThat(diffFile_args._Fields.findByName("toRevision")).isNull();
        assertThat(diffFile_args._Fields.findByName("to")).isNotNull();
    }
}
