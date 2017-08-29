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

package com.linecorp.centraldogma.server.mirror.credential;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.Test;

import com.google.common.collect.ImmutableSet;

import com.linecorp.centraldogma.common.Jackson;

public class NoneMirrorCredentialTest {
    @Test
    public void testDeserialization() throws Exception {
        assertThat(Jackson.readValue('{' +
                                     "  \"type\": \"none\"," +
                                     "  \"hostnamePatterns\": [" +
                                     "    \"^foo\\\\.com$\"" +
                                     "  ]" +
                                     '}', MirrorCredential.class))
                .isEqualTo(new NoneMirrorCredential(ImmutableSet.of(Pattern.compile("^foo\\.com$"))));
    }
}
