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

package com.linecorp.centraldogma.server.internal.mirror.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.junit.Test;

import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableSet;

public class MirrorCredentialTest {

    static final Iterable<Pattern> HOSTNAME_PATTERNS = ImmutableSet.of(Pattern.compile("^foo\\.com$"));

    private static final Iterable<Pattern> INVALID_PATTERNS = Arrays.asList(
            Pattern.compile("^foo\\.com$"),
            null,
            Pattern.compile("^bar\\.com$"));

    @Test
    public void testConstruction() {
        // Without ID and hostnamePatterns, i.e. effectively disabled.
        assertThat(new MirrorCredentialImpl(null, null).id()).isEmpty();
        assertThat(new MirrorCredentialImpl(null, null).hostnamePatterns()).isEmpty();

        // Without ID and with hostnamePatterns that contain null.
        assertThatThrownBy(() -> new MirrorCredentialImpl(null, INVALID_PATTERNS))
                .isInstanceOf(NullPointerException.class);

        // Without ID and with an empty hostnamePatterns.
        assertThat(new MirrorCredentialImpl(null, ImmutableSet.of()).hostnamePatterns()).isEmpty();

        // With ID and non-empty hostnamePatterns.
        final MirrorCredential c = new MirrorCredentialImpl("foo", HOSTNAME_PATTERNS);
        assertThat(c.id()).contains("foo");
        assertThat(c.hostnamePatterns().stream().map(Pattern::pattern)
                    .collect(Collectors.toSet())).containsExactly("^foo\\.com$");
    }

    private static final class MirrorCredentialImpl extends AbstractMirrorCredential {
        MirrorCredentialImpl(@Nullable String id, @Nullable Iterable<Pattern> hostnamePatterns) {
            super(id, hostnamePatterns);
        }

        @Override
        void addProperties(ToStringHelper helper) {}
    }
}
