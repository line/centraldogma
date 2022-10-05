/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.centraldogma.server.mirror;

import static java.util.Objects.requireNonNull;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * A utility class for creating a mirroring task.
 */
public final class MirrorUtil {

    static final Pattern DOGMA_PATH_PATTERN = Pattern.compile("^/([^/]+)/([^/]+)\\.dogma$");

    /**
     * Normalizes the specified {@code path}. A path which starts and ends with {@code /} would be returned.
     * Also, it would not have consecutive {@code /}.
     */
    public static String normalizePath(String path) {
        requireNonNull(path, "path");
        if (path.isEmpty()) {
            return "/";
        }

        if (!path.startsWith("/")) {
            path = '/' + path;
        }

        if (!path.endsWith("/")) {
            path += '/';
        }

        return path.replaceAll("//+", "/");
    }

    /**
     * Splits the specified 'remoteUri' into:
     * - the actual remote repository URI
     * - the path in the remote repository
     * - the branch name.
     *
     * <p>e.g. git+ssh://foo.com/bar.git/some-path#master is split into:
     * - remoteRepoUri: git+ssh://foo.com/bar.git
     * - remotePath:    /some-path/
     * - remoteBranch:  master
     *
     * <p>e.g. dogma://foo.com/bar/qux.dogma is split into:
     * - remoteRepoUri: dogma://foo.com/bar/qux.dogma
     * - remotePath:    / (default)
     * - remoteBranch:  {@code defaultBranch}
     */
    static String[] split(URI remoteUri, String suffix, @Nullable String defaultBranch) {
        final String host = remoteUri.getHost();
        if (host == null && !remoteUri.getScheme().endsWith("+file")) {
            throw new IllegalArgumentException("no host in remoteUri: " + remoteUri);
        }

        final String path = remoteUri.getRawPath();
        if (path == null) {
            throw new IllegalArgumentException("no path in remoteUri: " + remoteUri);
        }

        final Matcher matcher = Pattern.compile("^(.*?\\." + suffix + ")(?:$|/)").matcher(path);
        if (!matcher.find()) {
            throw new IllegalArgumentException("no '." + suffix + "' in remoteUri path: " + remoteUri);
        }

        final String newRemoteUri;
        final int port = remoteUri.getPort();
        if (host != null) {
            if (port > 0) {
                newRemoteUri = remoteUri.getScheme() + "://" + host + ':' + port +
                               matcher.group(1);
            } else {
                newRemoteUri = remoteUri.getScheme() + "://" + host + matcher.group(1);
            }
        } else {
            newRemoteUri = remoteUri.getScheme() + "://" + matcher.group(1);
        }

        final String remotePath;
        try {
            String decoded = URLDecoder.decode(path.substring(matcher.group(1).length()), "UTF-8");
            decoded = normalizePath(decoded);

            remotePath = decoded;
        } catch (UnsupportedEncodingException e) {
            throw new Error(e);
        }

        final String fragment = remoteUri.getFragment();
        final String remoteBranch = fragment != null ? fragment : defaultBranch;

        return new String[] { newRemoteUri, remotePath, remoteBranch };
    }

    private MirrorUtil() {}
}
