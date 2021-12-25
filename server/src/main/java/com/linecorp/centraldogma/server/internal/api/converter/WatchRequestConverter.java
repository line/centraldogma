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

package com.linecorp.centraldogma.server.internal.api.converter;

import static com.google.common.base.Ascii.toLowerCase;
import static com.google.common.base.Strings.isNullOrEmpty;

import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.centraldogma.common.Revision;

/**
 * A request converter that converts to {@link WatchRequest} when the request contains
 * {@link HttpHeaderNames#IF_NONE_MATCH}.
 */
public final class WatchRequestConverter implements RequestConverterFunction {

    private static final long DEFAULT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(120);

    private static final Splitter preferenceSplitter = Splitter.on(',').omitEmptyStrings().trimResults();

    private static final Splitter tokenSplitter = Splitter.on('=').omitEmptyStrings().trimResults();

    private static final String NOTIFY_ENTRY_NOT_FOUND = "notify-entry-not-found";

    /**
     * Converts the specified {@code request} to a {@link WatchRequest} when the request has
     * {@link HttpHeaderNames#IF_NONE_MATCH}. {@code null} otherwise.
     */
    @Override
    @Nullable
    public WatchRequest convertRequest(
            ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
            @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

        final String ifNoneMatch = request.headers().get(HttpHeaderNames.IF_NONE_MATCH);
        if (isNullOrEmpty(ifNoneMatch)) {
            return null;
        }

        final Revision lastKnownRevision = new Revision(extractRevision(ifNoneMatch));
        final String prefer = request.headers().get(HttpHeaderNames.PREFER);
        final long timeoutMillis;
        final boolean notifyEntryNotFound;
        if (!isNullOrEmpty(prefer)) {
            final Map<String, String> tokens = extract(prefer);
            timeoutMillis = timeoutMillis(tokens, prefer);
            notifyEntryNotFound = notifyEntryNotFound(tokens);
        } else {
            timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
            notifyEntryNotFound = false;
        }

        return new WatchRequest(lastKnownRevision, timeoutMillis, notifyEntryNotFound);
    }

    @VisibleForTesting
    String extractRevision(String ifNoneMatch) {
        final int length = ifNoneMatch.length();

        // Three below cases are valid. See https://github.com/line/centraldogma/issues/415
        // - <revision> (for backward compatibility)
        // - "<revision>"
        // - W/"<revision>"
        if (length > 2 && ifNoneMatch.charAt(0) == '"' &&
            ifNoneMatch.charAt(length - 1) == '"') {
            return ifNoneMatch.substring(1, length - 1);
        }

        if (length > 4 && ifNoneMatch.startsWith("W/\"") &&
            ifNoneMatch.charAt(length - 1) == '"') {
            return ifNoneMatch.substring(3, length - 1);
        }

        return ifNoneMatch;
    }

    // TODO(minwoox) Use https://github.com/line/armeria/issues/1835
    private static Map<String, String> extract(String preferHeader) {
        final Iterable<String> preferences = preferenceSplitter.split(preferHeader);
        final HashMap<String, String> tokens = new HashMap<>();
        for (String preference : preferences) {
            final Iterable<String> split = tokenSplitter.split(preference);
            final Iterator<String> iterator = split.iterator();
            if (iterator.hasNext()) {
                final String token = iterator.next();
                if (iterator.hasNext()) {
                    final String value = iterator.next();
                    tokens.put(toLowerCase(token), value);
                }
            }
        }
        return tokens;
    }

    private static long timeoutMillis(Map<String, String> tokens, String preferHeader) {
        final String wait = tokens.get("wait");
        if (wait == null) {
            return rejectPreferHeader(preferHeader, "wait=seconds");
        }

        final long timeoutSeconds;
        try {
            timeoutSeconds = Long.parseLong(wait);
        } catch (NumberFormatException e) {
            return rejectPreferHeader(preferHeader, "wait=seconds");
        }

        if (timeoutSeconds <= 0) {
            return rejectPreferHeader(preferHeader, "seconds > 0");
        }
        return TimeUnit.SECONDS.toMillis(timeoutSeconds);
    }

    private static long rejectPreferHeader(String preferHeader, String expected) {
        throw new IllegalArgumentException("invalid prefer header: " + preferHeader +
                                           " (expected: " + expected + ')');
    }

    private static boolean notifyEntryNotFound(Map<String, String> tokens) {
        final String notifyEntryNotFound = tokens.get(NOTIFY_ENTRY_NOT_FOUND);
        if ("true".equalsIgnoreCase(notifyEntryNotFound)) {
            return true;
        }
        // Default value is false.
        return false;
    }

    public static class WatchRequest {
        private final Revision lastKnownRevision;
        private final long timeoutMillis;
        private final boolean notifyEntryNotFound;

        WatchRequest(Revision lastKnownRevision, long timeoutMillis, boolean notifyEntryNotFound) {
            this.lastKnownRevision = lastKnownRevision;
            this.timeoutMillis = timeoutMillis;
            this.notifyEntryNotFound = notifyEntryNotFound;
        }

        public Revision lastKnownRevision() {
            return lastKnownRevision;
        }

        public long timeoutMillis() {
            return timeoutMillis;
        }

        public boolean notifyEntryNotFound() {
            return notifyEntryNotFound;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("lastKnownRevision", lastKnownRevision)
                              .add("timeoutMillis", timeoutMillis)
                              .toString();
        }
    }
}
