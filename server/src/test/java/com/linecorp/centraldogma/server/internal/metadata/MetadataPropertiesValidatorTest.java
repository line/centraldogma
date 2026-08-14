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

package com.linecorp.centraldogma.server.internal.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.MetadataPropertiesConfig;
import com.linecorp.centraldogma.server.internal.metadata.MetadataPropertiesValidator.ResourceType;

class MetadataPropertiesValidatorTest {

    private static final String SCHEMA =
            '{' +
            "  \"type\": \"object\"," +
            "  \"properties\": {" +
            "    \"serviceId\": { \"type\": \"string\", \"pattern\": \"^[a-z][a-z0-9-]*$\" }," +
            "    \"replicas\": { \"type\": \"integer\" }" +
            "  }," +
            "  \"required\": [ \"serviceId\" ]" +
            '}';

    @Test
    void ignoresEverythingWithoutConfig() throws Exception {
        final MetadataPropertiesValidator validator = new MetadataPropertiesValidator(null);
        assertThat(validator.validate(ResourceType.PROJECT, null)).isNull();
        assertThat(validator.validate(ResourceType.PROJECT, Jackson.readTree("{\"foo\":\"bar\"}"))).isNull();
    }

    @Test
    void ignoresResourceTypeWithoutSchema() throws Exception {
        final MetadataPropertiesValidator validator = newValidator();
        assertThat(validator.validate(ResourceType.REPO, Jackson.readTree("{\"foo\":\"bar\"}"))).isNull();
        // Even a non-object value is ignored when no schema is declared for the resource type.
        assertThat(validator.validate(ResourceType.REPO, Jackson.readTree("[]"))).isNull();
    }

    @Test
    void treatsExplicitNullAsAbsent() throws Exception {
        assertThat(new MetadataPropertiesValidator(null)
                           .validate(ResourceType.PROJECT, Jackson.readTree("null"))).isNull();
        assertThatThrownBy(() -> newValidator().validate(ResourceType.PROJECT, Jackson.readTree("null")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
    }

    @Test
    void validatesObjectAsIsWhenSchemaDeclaresNoTopLevelProperties() throws Exception {
        final MetadataPropertiesValidator validator = new MetadataPropertiesValidator(
                new MetadataPropertiesConfig(
                        Jackson.readTree("{\"type\":\"object\",\"required\":[\"serviceId\"]}"), null, null));
        final JsonNode validated = validator.validate(
                ResourceType.PROJECT, Jackson.readTree("{\"serviceId\":\"foo\",\"extra\":1}"));
        assertThat(validated).isEqualTo(Jackson.readTree("{\"serviceId\":\"foo\",\"extra\":1}"));
        assertThatThrownBy(() -> validator.validate(ResourceType.PROJECT, Jackson.readTree("{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
    }

    @Test
    void dropsUndeclaredProperties() throws Exception {
        final MetadataPropertiesValidator validator = newValidator();
        final JsonNode validated = validator.validate(
                ResourceType.PROJECT, Jackson.readTree("{\"serviceId\":\"foo\",\"undeclared\":\"x\"}"));
        assertThat(validated).isEqualTo(Jackson.readTree("{\"serviceId\":\"foo\"}"));
    }

    @Test
    void keepsDeclaredTypedValues() throws Exception {
        final MetadataPropertiesValidator validator = newValidator();
        final JsonNode validated = validator.validate(
                ResourceType.PROJECT, Jackson.readTree("{\"serviceId\":\"foo\",\"replicas\":3}"));
        assertThat(validated).isEqualTo(Jackson.readTree("{\"serviceId\":\"foo\",\"replicas\":3}"));
    }

    @Test
    void rejectsMissingRequiredProperty() {
        final MetadataPropertiesValidator validator = newValidator();
        assertThatThrownBy(() -> validator.validate(ResourceType.PROJECT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
        assertThatThrownBy(() -> validator.validate(ResourceType.PROJECT, Jackson.readTree("{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
        // A request whose properties are all undeclared is equivalent to an empty one.
        assertThatThrownBy(
                () -> validator.validate(ResourceType.PROJECT, Jackson.readTree("{\"undeclared\":\"x\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
    }

    @Test
    void rejectsTypeMismatch() {
        final MetadataPropertiesValidator validator = newValidator();
        assertThatThrownBy(() -> validator.validate(
                ResourceType.PROJECT, Jackson.readTree("{\"serviceId\":\"foo\",\"replicas\":\"many\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replicas");
    }

    @Test
    void rejectsPatternViolation() {
        final MetadataPropertiesValidator validator = newValidator();
        assertThatThrownBy(
                () -> validator.validate(ResourceType.PROJECT, Jackson.readTree("{\"serviceId\":\"FOO\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
    }

    @Test
    void rejectsNonObjectProperties() {
        final MetadataPropertiesValidator validator = newValidator();
        assertThatThrownBy(() -> validator.validate(ResourceType.PROJECT, Jackson.readTree("[]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a JSON object");
    }

    @Test
    void failsFastOnInvalidSchema() {
        assertThatThrownBy(() -> new MetadataPropertiesValidator(new MetadataPropertiesConfig(
                Jackson.readTree("{\"type\":\"object\",\"properties\":{\"a\":{\"pattern\":\"[\"}}}"),
                null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metadataProperties.project");
    }

    private static MetadataPropertiesValidator newValidator() {
        try {
            return new MetadataPropertiesValidator(
                    new MetadataPropertiesConfig(Jackson.readTree(SCHEMA), null, null));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
