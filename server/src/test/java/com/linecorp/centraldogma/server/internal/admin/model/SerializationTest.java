/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.admin.model;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.metadata.Member;
import com.linecorp.centraldogma.server.internal.metadata.PerRolePermissions;
import com.linecorp.centraldogma.server.internal.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.internal.metadata.ProjectRole;
import com.linecorp.centraldogma.server.internal.metadata.RepositoryMetadata;
import com.linecorp.centraldogma.server.internal.metadata.Token;
import com.linecorp.centraldogma.server.internal.metadata.TokenRegistration;
import com.linecorp.centraldogma.server.internal.metadata.UserAndTimestamp;

public class SerializationTest {

    @Test
    public void testTimeSerialization() throws IOException {
        final Member member =
                new Member("armeria@dogma.org", ProjectRole.MEMBER, newCreationTag());
        assertThatJson(member).isEqualTo("{\n" +
                                         "  \"login\" : \"armeria@dogma.org\",\n" +
                                         "  \"role\" : \"MEMBER\",\n" +
                                         "  \"creation\" : {\n" +
                                         "    \"user\" : \"editor@dogma.org\",\n" +
                                         "    \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                         "  }\n" +
                                         '}');
        final Member obj = Jackson.readValue(Jackson.writeValueAsString(member),
                                             Member.class);
        assertThat(obj.login()).isEqualTo("armeria@dogma.org");
        assertThat(obj.role()).isEqualTo(ProjectRole.MEMBER);
        assertThat(obj.creation()).isNotNull();
        assertThat(obj.creation().user()).isEqualTo("editor@dogma.org");
        assertThat(obj.creation().timestamp()).isEqualTo("2017-01-01T00:00:00Z");
    }

    @Test
    public void testValidProject() throws IOException {
        final String userLogin = "armeria@dogma.org";
        final Member member = new Member(userLogin, ProjectRole.MEMBER, newCreationTag());
        final RepositoryMetadata repositoryMetadata = new RepositoryMetadata("sample", newCreationTag(),
                                                                             PerRolePermissions.DEFAULT);
        final Token token = new Token("testApp", "testSecret", false, newCreationTag(), null);
        final ProjectMetadata metadata =
                new ProjectMetadata("test",
                                    ImmutableMap.of(repositoryMetadata.name(), repositoryMetadata),
                                    ImmutableMap.of(member.id(), member),
                                    ImmutableMap.of(token.id(),
                                                    new TokenRegistration(token.id(),
                                                                          ProjectRole.MEMBER,
                                                                          newCreationTag())),
                                    newCreationTag(),
                                    null);
        assertThatJson(metadata).isEqualTo("{\n" +
                                           "  \"name\" : \"test\",\n" +
                                           "  \"repos\" : {\n" +
                                           "    \"sample\" : {\n" +
                                           "      \"name\" : \"sample\",\n" +
                                           "      \"perRolePermissions\" : {\n" +
                                           "        \"owner\" : [ \"READ\", \"WRITE\" ],\n" +
                                           "        \"member\" : [ \"READ\", \"WRITE\" ],\n" +
                                           "        \"guest\" : [ \"READ\", \"WRITE\" ]\n" +
                                           "      },\n" +
                                           "      \"perUserPermissions\" : { },\n" +
                                           "      \"perTokenPermissions\" : { },\n" +
                                           "      \"creation\" : {\n" +
                                           "        \"user\" : \"editor@dogma.org\",\n" +
                                           "        \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                           "      }\n" +
                                           "    }\n" +
                                           "  },\n" +
                                           "  \"members\" : {\n" +
                                           "    \"armeria@dogma.org\" : {\n" +
                                           "      \"login\" : \"armeria@dogma.org\",\n" +
                                           "      \"role\" : \"MEMBER\",\n" +
                                           "      \"creation\" : {\n" +
                                           "        \"user\" : \"editor@dogma.org\",\n" +
                                           "        \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                           "      }\n" +
                                           "    }\n" +
                                           "  },\n" +
                                           "  \"tokens\" : {\n" +
                                           "    \"testApp\" : {\n" +
                                           "      \"appId\" : \"testApp\",\n" +
                                           "      \"role\" : \"MEMBER\",\n" +
                                           "      \"creation\" : {\n" +
                                           "        \"user\" : \"editor@dogma.org\",\n" +
                                           "        \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                           "      }\n" +
                                           "    }\n" +
                                           "  },\n" +
                                           "  \"creation\" : {\n" +
                                           "    \"user\" : \"editor@dogma.org\",\n" +
                                           "    \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                           "  }\n" +
                                           '}');

        final ProjectMetadata obj = Jackson.readValue(Jackson.writeValueAsString(metadata),
                                                      ProjectMetadata.class);
        assertThat(obj.name()).isEqualTo("test");
        assertThat(obj.repos().size()).isOne();
        assertThat(obj.members().size()).isOne();
        assertThat(obj.members().get(userLogin).role()).isEqualTo(ProjectRole.MEMBER);
        assertThat(obj.tokens().size()).isOne();
        assertThat(obj.creation()).isNotNull();
        assertThat(obj.creation().user()).isEqualTo("editor@dogma.org");
        assertThat(obj.creation().timestamp()).isEqualTo("2017-01-01T00:00:00Z");
        assertThat(obj.removal()).isNull();
    }

    @Test
    public void testRemovedProject() throws IOException {
        final Member member = new Member("armeria@dogma.org", ProjectRole.MEMBER,
                                         newCreationTag());
        final RepositoryMetadata repositoryMetadata = new RepositoryMetadata("sample", newCreationTag(),
                                                                             PerRolePermissions.DEFAULT);
        final Token token = new Token("testApp", "testSecret", false, newCreationTag(), null);
        final ProjectMetadata metadata =
                new ProjectMetadata("test",
                                    ImmutableMap.of(repositoryMetadata.name(), repositoryMetadata),
                                    ImmutableMap.of(member.id(), member),
                                    ImmutableMap.of(token.id(),
                                                    new TokenRegistration(token.id(),
                                                                          ProjectRole.MEMBER,
                                                                          newCreationTag())),
                                    newCreationTag(),
                                    newRemovalTag());

        assertThatJson(metadata).isEqualTo("{\n" +
                                           "  \"name\" : \"test\",\n" +
                                           "  \"repos\" : {\n" +
                                           "    \"sample\" : {\n" +
                                           "      \"name\" : \"sample\",\n" +
                                           "      \"perRolePermissions\" : {\n" +
                                           "        \"owner\" : [ \"READ\", \"WRITE\" ],\n" +
                                           "        \"member\" : [ \"READ\", \"WRITE\" ],\n" +
                                           "        \"guest\" : [ \"READ\", \"WRITE\" ]\n" +
                                           "      },\n" +
                                           "      \"perUserPermissions\" : { },\n" +
                                           "      \"perTokenPermissions\" : { },\n" +
                                           "      \"creation\" : {\n" +
                                           "        \"user\" : \"editor@dogma.org\",\n" +
                                           "        \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                           "      }\n" +
                                           "    }\n" +
                                           "  },\n" +
                                           "  \"members\" : {\n" +
                                           "    \"armeria@dogma.org\" : {\n" +
                                           "      \"login\" : \"armeria@dogma.org\",\n" +
                                           "      \"role\" : \"MEMBER\",\n" +
                                           "      \"creation\" : {\n" +
                                           "        \"user\" : \"editor@dogma.org\",\n" +
                                           "        \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                           "      }\n" +
                                           "    }\n" +
                                           "  },\n" +
                                           "  \"tokens\" : {\n" +
                                           "    \"testApp\" : {\n" +
                                           "      \"appId\" : \"testApp\",\n" +
                                           "      \"role\" : \"MEMBER\",\n" +
                                           "      \"creation\" : {\n" +
                                           "        \"user\" : \"editor@dogma.org\",\n" +
                                           "        \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                           "      }\n" +
                                           "    }\n" +
                                           "  },\n" +
                                           "  \"creation\" : {\n" +
                                           "    \"user\" : \"editor@dogma.org\",\n" +
                                           "    \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                           "  },\n" +
                                           "  \"removal\" : {\n" +
                                           "    \"user\" : \"editor@dogma.org\",\n" +
                                           "    \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                           "  }\n" +
                                           '}');

        final ProjectMetadata obj = Jackson.readValue(Jackson.writeValueAsString(metadata),
                                                      ProjectMetadata.class);
        assertThat(obj.name()).isEqualTo("test");
        assertThat(obj.repos().size()).isOne();
        assertThat(obj.members().size()).isOne();
        assertThatJson(Jackson.writeValueAsString(obj.members().get("armeria@dogma.org")))
                .isEqualTo(Jackson.writeValueAsString(member));
        assertThat(obj.tokens().size()).isOne();
        assertThat(obj.creation()).isNotNull();
        assertThat(obj.creation().user()).isEqualTo("editor@dogma.org");
        assertThat(obj.creation().timestamp()).isEqualTo("2017-01-01T00:00:00Z");
    }

    private static UserAndTimestamp newCreationTag() {
        return new UserAndTimestamp("editor@dogma.org",
                                    Instant.from(ZonedDateTime.of(2017, 1, 1,
                                                                  0, 0, 0, 0,
                                                                  ZoneId.of("GMT"))));
    }

    private static UserAndTimestamp newRemovalTag() {
        return new UserAndTimestamp("editor@dogma.org",
                                    Instant.from(ZonedDateTime.of(2017, 1, 1,
                                                                  0, 0, 0, 0,
                                                                  ZoneId.of("GMT"))));
    }
}
