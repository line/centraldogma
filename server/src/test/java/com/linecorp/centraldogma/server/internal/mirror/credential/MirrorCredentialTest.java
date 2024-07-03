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

package com.linecorp.centraldogma.server.internal.mirror.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableSet;

import com.linecorp.centraldogma.server.mirror.MirrorCredential;

class MirrorCredentialTest {

    static final Iterable<Pattern> HOSTNAME_PATTERNS = ImmutableSet.of(Pattern.compile("^foo\\.com$"));

    private static final Iterable<Pattern> INVALID_PATTERNS = Arrays.asList(
            Pattern.compile("^foo\\.com$"),
            null,
            Pattern.compile("^bar\\.com$"));

    @Test
    void testConstruction() {
        // null hostnamePatterns.
        assertThat(new MirrorCredentialImpl("foo", null).hostnamePatterns()).isEmpty();

        // hostnamePatterns that contain null.
        assertThatThrownBy(() -> new MirrorCredentialImpl("foo", INVALID_PATTERNS))
                .isInstanceOf(NullPointerException.class);

        // An empty hostnamePatterns.
        assertThat(new MirrorCredentialImpl("foo", ImmutableSet.of()).hostnamePatterns()).isEmpty();

        // With ID and non-empty hostnamePatterns.
        final MirrorCredential c = new MirrorCredentialImpl("foo", HOSTNAME_PATTERNS);
        assertThat(c.id()).contains("foo");
        assertThat(c.hostnamePatterns().stream().map(Pattern::pattern)
                    .collect(Collectors.toSet())).containsExactly("^foo\\.com$");
    }

    private static final class MirrorCredentialImpl extends AbstractMirrorCredential {
        MirrorCredentialImpl(String id, @Nullable Iterable<Pattern> hostnamePatterns) {
            super(id, true, "custom", hostnamePatterns);
        }

        @Override
        void addProperties(ToStringHelper helper) {}
    }
}
