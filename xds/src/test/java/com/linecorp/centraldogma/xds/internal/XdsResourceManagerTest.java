/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.centraldogma.xds.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class XdsResourceManagerTest {

    // ---- injectYamlField -------------------------------------------------

    @Test
    void injectYamlField_replacesExistingField() {
        final String yaml = "name: old\ntype: EDS\n";
        assertThat(XdsResourceManager.injectYamlField(yaml, "name", "new_name"))
                .isEqualTo("name: new_name\ntype: EDS\n");
    }

    @Test
    void injectYamlField_prependsWhenFieldAbsent() {
        final String yaml = "type: EDS\n";
        assertThat(XdsResourceManager.injectYamlField(yaml, "name", "my_cluster"))
                .isEqualTo("name: my_cluster\ntype: EDS\n");
    }

    @Test
    void injectYamlField_preservesComments() {
        final String yaml = "# important comment\nname: old\ntype: EDS\n";
        assertThat(XdsResourceManager.injectYamlField(yaml, "name", "new_name"))
                .isEqualTo("# important comment\nname: new_name\ntype: EDS\n");
    }

    @Test
    void injectYamlField_doesNotMatchNestedField() {
        // A 'name:' that appears only as a value nested under another key is not matched
        // as the top-level field; it is prepended instead.
        final String yaml = "someField:\n  name: nested\n";
        assertThat(XdsResourceManager.injectYamlField(yaml, "name", "top_name"))
                .isEqualTo("name: top_name\nsomeField:\n  name: nested\n");
    }

    @Test
    void injectYamlField_replacesBlockScalarField() {
        // When the existing field value is a block scalar (|), the injection must replace
        // the entire block — indicator line AND content lines — not just the indicator line.
        final String yaml = "name: |\n  groups/foo/clusters/bar\ntype: EDS\n";
        assertThat(XdsResourceManager.injectYamlField(yaml, "name", "groups/new/clusters/new"))
                .isEqualTo("name: groups/new/clusters/new\ntype: EDS\n");
    }

    @Test
    void injectYamlField_replacesSnakeCaseKey() {
        // A user-supplied snake_case key (e.g. cluster_name) must be found, replaced, and
        // rewritten as the canonical camelCase field name.
        final String yaml = "cluster_name: old\nendpoints: []\n";
        assertThat(XdsResourceManager.injectYamlField(yaml, "clusterName",
                                                      "groups/g1/clusters/ep1"))
                .isEqualTo("clusterName: groups/g1/clusters/ep1\nendpoints: []\n");
    }
}
