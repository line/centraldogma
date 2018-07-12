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

import static com.linecorp.centraldogma.internal.Util.requireNonNullElements;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableSet;

abstract class AbstractMirrorCredential implements MirrorCredential {

    @Nullable
    private final String id;
    private final Set<Pattern> hostnamePatterns;
    private final Set<String> hostnamePatternStrings;

    AbstractMirrorCredential(@Nullable String id, @Nullable Iterable<Pattern> hostnamePatterns) {
        this.id = id;
        this.hostnamePatterns = validateHostnamePatterns(hostnamePatterns);
        hostnamePatternStrings = this.hostnamePatterns.stream().map(Pattern::pattern)
                                                      .collect(Collectors.toSet());
    }

    private static Set<Pattern> validateHostnamePatterns(@Nullable Iterable<Pattern> hostnamePatterns) {
        if (hostnamePatterns == null) {
            return ImmutableSet.of();
        }

        return ImmutableSet.copyOf(
                requireNonNullElements(hostnamePatterns, "hostnamePatterns"));
    }

    @Override
    public final Optional<String> id() {
        return Optional.ofNullable(id);
    }

    @Override
    public final Set<Pattern> hostnamePatterns() {
        return hostnamePatterns;
    }

    @Override
    public final boolean matches(URI uri) {
        requireNonNull(uri, "uri");

        final String host = uri.getHost();
        return host != null && hostnamePatterns.stream().anyMatch(p -> p.matcher(host).matches());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final AbstractMirrorCredential that = (AbstractMirrorCredential) o;
        return hostnamePatternStrings.equals(that.hostnamePatternStrings);
    }

    @Override
    public int hashCode() {
        return hostnamePatternStrings.hashCode();
    }

    @Override
    public final String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper(this);
        if (id != null) {
            helper.add("id", id);
        }
        if (!hostnamePatterns.isEmpty()) {
            helper.add("hostnamePatterns", hostnamePatterns);
        }

        addProperties(helper);
        return helper.toString();
    }

    abstract void addProperties(ToStringHelper helper);
}
