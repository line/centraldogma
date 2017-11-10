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
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.internal.Jackson;

public class SerializationTest {

    @Test
    public void testTimeSerialization() throws IOException {
        final MemberInfo memberInfo =
                new MemberInfo("armeria@dogma.org", ProjectRole.MEMBER, newAdditionTag());
        assertThatJson(memberInfo).isEqualTo("{\n" +
                                             "  \"login\" : \"armeria@dogma.org\",\n" +
                                             "  \"role\" : \"MEMBER\",\n" +
                                             "  \"creation\" : {\n" +
                                             "    \"user\" : \"editor@dogma.org\",\n" +
                                             "    \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                             "  }\n" +
                                             '}');
        final MemberInfo obj = Jackson.readValue(Jackson.writeValueAsString(memberInfo),
                                                 MemberInfo.class);
        assertThat(obj.login()).isEqualTo("armeria@dogma.org");
        assertThat(obj.role()).isEqualTo(ProjectRole.MEMBER);
        assertThat(obj.creation()).isNotNull();
        assertThat(obj.creation().user()).isEqualTo("editor@dogma.org");
        assertThat(obj.creation().timestamp()).isEqualTo("2017-01-01T00:00:00Z");
    }

    @Test
    public void testValidProject() throws IOException {
        final MemberInfo memberInfo = new MemberInfo("armeria@dogma.org", ProjectRole.MEMBER,
                                                     newAdditionTag());
        final RepoInfo repoInfo = new RepoInfo("sample", newAdditionTag());
        final TokenInfo tokenInfo = new TokenInfo("testApp", "testSecret", ProjectRole.MEMBER,
                                                  newAdditionTag());
        final ProjectInfo projectInfo = new ProjectInfo("test",
                                                        ImmutableList.of(repoInfo),
                                                        ImmutableList.of(memberInfo),
                                                        ImmutableList.of(tokenInfo),
                                                        newAdditionTag(),
                                                        null);
        assertThatJson(projectInfo).isEqualTo("{\n" +
                                              "  \"name\" : \"test\",\n" +
                                              "  \"repos\" : [ {\n" +
                                              "    \"name\" : \"sample\",\n" +
                                              "    \"creation\" : {\n" +
                                              "      \"user\" : \"editor@dogma.org\",\n" +
                                              "      \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                              "    }\n" +
                                              "  } ],\n" +
                                              "  \"members\" : [ {\n" +
                                              "    \"login\" : \"armeria@dogma.org\",\n" +
                                              "    \"role\" : \"MEMBER\",\n" +
                                              "    \"creation\" : {\n" +
                                              "      \"user\" : \"editor@dogma.org\",\n" +
                                              "      \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                              "    }\n" +
                                              "  } ],\n" +
                                              "  \"tokens\" : [ {\n" +
                                              "    \"appId\" : \"testApp\",\n" +
                                              "    \"secret\" : \"testSecret\",\n" +
                                              "    \"role\" : \"MEMBER\",\n" +
                                              "    \"creation\" : {\n" +
                                              "      \"user\" : \"editor@dogma.org\",\n" +
                                              "      \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                              "    }\n" +
                                              "  } ],\n" +
                                              "  \"creation\" : {\n" +
                                              "    \"user\" : \"editor@dogma.org\",\n" +
                                              "    \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                              "  },\n" +
                                              "  \"removed\" : false\n" +
                                              '}');

        final ProjectInfo obj = Jackson.readValue(Jackson.writeValueAsString(projectInfo),
                                                  ProjectInfo.class);
        assertThat(obj.name()).isEqualTo("test");
        assertThat(obj.repos().size()).isOne();
        assertThat(obj.members().size()).isOne();
        assertThat(obj.members().get(0).role()).isEqualTo(ProjectRole.MEMBER);
        assertThat(obj.tokens().size()).isOne();
        assertThat(obj.creation()).isNotNull();
        assertThat(obj.creation().user()).isEqualTo("editor@dogma.org");
        assertThat(obj.creation().timestamp()).isEqualTo("2017-01-01T00:00:00Z");
        assertThat(obj.removal()).isNull();
        assertThat(obj.isRemoved()).isFalse();
    }

    @Test
    public void testRemovedProject() throws IOException {
        final MemberInfo memberInfo = new MemberInfo("armeria@dogma.org", ProjectRole.MEMBER,
                                                     newAdditionTag());
        final RepoInfo repoInfo = new RepoInfo("sample", newAdditionTag());
        final TokenInfo tokenInfo = new TokenInfo("testApp", "testSecret", ProjectRole.MEMBER,
                                                  newAdditionTag());
        final ProjectInfo projectInfo = new ProjectInfo("test",
                                                        ImmutableList.of(repoInfo),
                                                        ImmutableList.of(memberInfo),
                                                        ImmutableList.of(tokenInfo),
                                                        newAdditionTag(),
                                                        newRemovalTag());
        assertThatJson(projectInfo).isEqualTo("{\n" +
                                              "  \"name\" : \"test\",\n" +
                                              "  \"repos\" : [ {\n" +
                                              "    \"name\" : \"sample\",\n" +
                                              "    \"creation\" : {\n" +
                                              "      \"user\" : \"editor@dogma.org\",\n" +
                                              "      \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                              "    }\n" +
                                              "  } ],\n" +
                                              "  \"members\" : [ {\n" +
                                              "    \"login\" : \"armeria@dogma.org\",\n" +
                                              "    \"role\" : \"MEMBER\",\n" +
                                              "    \"creation\" : {\n" +
                                              "      \"user\" : \"editor@dogma.org\",\n" +
                                              "      \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                              "    }\n" +
                                              "  } ],\n" +
                                              "  \"tokens\" : [ {\n" +
                                              "    \"appId\" : \"testApp\",\n" +
                                              "    \"secret\" : \"testSecret\",\n" +
                                              "    \"role\" : \"MEMBER\",\n" +
                                              "    \"creation\" : {\n" +
                                              "      \"user\" : \"editor@dogma.org\",\n" +
                                              "      \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                              "    }\n" +
                                              "  } ],\n" +
                                              "  \"creation\" : {\n" +
                                              "    \"user\" : \"editor@dogma.org\",\n" +
                                              "    \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                              "  },\n" +
                                              "  \"removal\" : {\n" +
                                              "    \"user\" : \"editor@dogma.org\",\n" +
                                              "    \"timestamp\" : \"2017-01-01T00:00:00Z\"\n" +
                                              "  },\n" +
                                              "  \"removed\" : true\n" +
                                              '}');

        final ProjectInfo obj = Jackson.readValue(Jackson.writeValueAsString(projectInfo),
                                                  ProjectInfo.class);
        assertThat(obj.name()).isEqualTo("test");
        assertThat(obj.repos().size()).isOne();
        assertThat(obj.members().size()).isOne();
        assertThat(obj.members().get(0).role()).isEqualTo(ProjectRole.MEMBER);
        assertThat(obj.tokens().size()).isOne();
        assertThat(obj.creation()).isNotNull();
        assertThat(obj.creation().user()).isEqualTo("editor@dogma.org");
        assertThat(obj.creation().timestamp()).isEqualTo("2017-01-01T00:00:00Z");
        assertThat(obj.isRemoved()).isTrue();
    }

    private UserAndTimestamp newAdditionTag() {
        return new UserAndTimestamp("editor@dogma.org",
                                    ZonedDateTime.of(2017, 1, 1,
                                                     0, 0, 0, 0,
                                                     ZoneId.of("GMT")));
    }

    private UserAndTimestamp newRemovalTag() {
        return new UserAndTimestamp("editor@dogma.org",
                                    ZonedDateTime.of(2017, 1, 1,
                                                     0, 0, 0, 0,
                                                     ZoneId.of("GMT")));
    }
}
