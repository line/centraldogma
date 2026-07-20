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

    // ---- normalizeYamlKeys -----------------------------------------------

    @Test
    void normalizeYamlKeys_simpleSnakeCaseKey() {
        assertThat(XdsResourceManager.normalizeYamlKeys("cluster_name: foo\n"))
                .isEqualTo("clusterName: foo\n");
    }

    @Test
    void normalizeYamlKeys_multipleUnderscoreSegments() {
        assertThat(XdsResourceManager.normalizeYamlKeys("locality_lb_endpoints: []\n"))
                .isEqualTo("localityLbEndpoints: []\n");
    }

    @Test
    void normalizeYamlKeys_snakeCaseValueNotConverted() {
        // The value after the colon must NOT be touched, even if it looks like a snake_case
        // identifier.  The regex only matches at the start of a line followed by a colon.
        assertThat(XdsResourceManager.normalizeYamlKeys("someField: some_snake_value\n"))
                .isEqualTo("someField: some_snake_value\n");
    }

    @Test
    void normalizeYamlKeys_snakeCaseKeyWithSnakeCaseValue() {
        // The key portion is converted; the value is preserved verbatim.
        assertThat(XdsResourceManager.normalizeYamlKeys("cluster_name: my_cluster_name\n"))
                .isEqualTo("clusterName: my_cluster_name\n");
    }

    @Test
    void normalizeYamlKeys_valueIdenticalToKey() {
        // The value happens to be the same snake_case word as the key — only the key is
        // converted.
        assertThat(XdsResourceManager.normalizeYamlKeys("cluster_name: cluster_name\n"))
                .isEqualTo("clusterName: cluster_name\n");
    }

    @Test
    void normalizeYamlKeys_commentLineNotConverted() {
        // Lines beginning with '#' do not start with [a-z], so they are never matched.
        final String yaml = "# cluster_name: should stay as-is\ncluster_name: bar\n";
        assertThat(XdsResourceManager.normalizeYamlKeys(yaml))
                .isEqualTo("# cluster_name: should stay as-is\nclusterName: bar\n");
    }

    @Test
    void normalizeYamlKeys_inlineCommentNotConverted() {
        // The regex stops at the colon; the rest of the line (value + inline comment) is
        // never part of the match and is left unchanged.
        assertThat(XdsResourceManager.normalizeYamlKeys(
                "cluster_name: foo # cluster_name note\n"))
                .isEqualTo("clusterName: foo # cluster_name note\n");
    }

    @Test
    void normalizeYamlKeys_indentedSnakeCaseKey() {
        final String yaml = "edsClusterConfig:\n  service_name: foo\n";
        assertThat(XdsResourceManager.normalizeYamlKeys(yaml))
                .isEqualTo("edsClusterConfig:\n  serviceName: foo\n");
    }

    @Test
    void normalizeYamlKeys_deeplyNestedSnakeCaseKeys() {
        final String yaml =
                "locality_lb_endpoints:\n" +
                "  lb_endpoints:\n" +
                "    socket_address:\n" +
                "      port_value: 8080\n";
        assertThat(XdsResourceManager.normalizeYamlKeys(yaml))
                .isEqualTo(
                        "localityLbEndpoints:\n" +
                        "  lbEndpoints:\n" +
                        "    socketAddress:\n" +
                        "      portValue: 8080\n");
    }

    @Test
    void normalizeYamlKeys_camelCaseKeyUnchanged() {
        // camelCase keys have no underscore, so the pattern never matches them.
        assertThat(XdsResourceManager.normalizeYamlKeys("clusterName: foo\n"))
                .isEqualTo("clusterName: foo\n");
    }

    @Test
    void normalizeYamlKeys_singleWordKeyUnchanged() {
        // Single-word keys (no underscore) are not touched.
        final String yaml = "name: foo\nport: 8080\n";
        assertThat(XdsResourceManager.normalizeYamlKeys(yaml)).isEqualTo(yaml);
    }

    @Test
    void normalizeYamlKeys_mixedCamelAndSnakeKeys() {
        final String yaml =
                "clusterName: already_camel\n" +
                "connect_timeout: 5s\n" +
                "name: foo\n";
        assertThat(XdsResourceManager.normalizeYamlKeys(yaml))
                .isEqualTo(
                        "clusterName: already_camel\n" +
                        "connectTimeout: 5s\n" +
                        "name: foo\n");
    }

    @Test
    void normalizeYamlKeys_noSnakeCaseKeys() {
        // Nothing to convert — result must be identical to input.
        final String yaml = "name: foo\nclusterName: bar\n  serviceName: baz\n";
        assertThat(XdsResourceManager.normalizeYamlKeys(yaml)).isEqualTo(yaml);
    }

    @Test
    void normalizeYamlKeys_emptyString() {
        assertThat(XdsResourceManager.normalizeYamlKeys("")).isEmpty();
    }

    @Test
    void normalizeYamlKeys_keyWithSpaceBeforeColon() {
        // The pattern allows optional whitespace before the colon (group 3 is \s*:).
        assertThat(XdsResourceManager.normalizeYamlKeys("connect_timeout : 5s\n"))
                .isEqualTo("connectTimeout : 5s\n");
    }

    @Test
    void normalizeYamlKeys_realWorldClusterYaml() {
        // A typical cluster body a user might POST using snake_case field names.
        final String input =
                "# EDS cluster\n" +
                "type: EDS\n" +
                "connect_timeout: 5s\n" +
                "eds_cluster_config:\n" +
                "  eds_config:\n" +
                "    ads: {}\n" +
                "  service_name: my_service_name\n";
        final String expected =
                "# EDS cluster\n" +
                "type: EDS\n" +
                "connectTimeout: 5s\n" +
                "edsClusterConfig:\n" +
                "  edsConfig:\n" +
                "    ads: {}\n" +
                "  serviceName: my_service_name\n";
        assertThat(XdsResourceManager.normalizeYamlKeys(input)).isEqualTo(expected);
    }

    @Test
    void normalizeYamlKeys_realWorldEndpointYaml() {
        // A ClusterLoadAssignment body with snake_case field names and snake_case values.
        final String input =
                "cluster_name: my_cluster\n" +
                "endpoints:\n" +
                "  - locality:\n" +
                "      region: us_east_1\n" +           // 'region' is single-word, value unchanged
                "    lb_endpoints:\n" +
                "      - endpoint:\n" +
                "          address:\n" +
                "            socket_address:\n" +
                "              address: 127.0.0.1\n" +
                "              port_value: 8080\n";
        final String expected =
                "clusterName: my_cluster\n" +
                "endpoints:\n" +
                "  - locality:\n" +
                "      region: us_east_1\n" +
                "    lbEndpoints:\n" +                 // lb_endpoints converted
                "      - endpoint:\n" +
                "          address:\n" +
                "            socketAddress:\n" +
                "              address: 127.0.0.1\n" +
                "              portValue: 8080\n";
        assertThat(XdsResourceManager.normalizeYamlKeys(input)).isEqualTo(expected);
    }

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
    void normalizeYamlKeys_blockScalarContentNotConverted() {
        // Content lines of a block scalar must not be converted even if a line happens to
        // look like a snake_case key.  Only real mapping keys (as identified by the YAML AST)
        // are touched.
        final String yaml =
                "filterMetadata:\n" +
                "  inlineString: |\n" +
                "    connect_timeout: 5s\n" +
                "    retry_on: 5xx\n";
        // 'inlineString' is already camelCase; the block scalar content lines must be
        // left completely unchanged.
        assertThat(XdsResourceManager.normalizeYamlKeys(yaml)).isEqualTo(yaml);
    }

    @Test
    void normalizeYamlKeys_blockScalarKeyConverted() {
        // The key of a block scalar field IS a real mapping key and must be converted;
        // the block scalar content must not be touched.
        final String yaml =
                "inline_string: |\n" +
                "  connect_timeout: 5s\n";
        assertThat(XdsResourceManager.normalizeYamlKeys(yaml))
                .isEqualTo("inlineString: |\n  connect_timeout: 5s\n");
    }
}
