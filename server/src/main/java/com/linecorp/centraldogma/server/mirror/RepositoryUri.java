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

package com.linecorp.centraldogma.server.mirror;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.centraldogma.server.mirror.MirrorUtil.normalizePath;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed representation of a repository URI used for mirroring.
 */
public final class RepositoryUri {

    /**
     * Parses the specified 'remoteUri' into:
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
    public static RepositoryUri parse(URI remoteUri, String suffix) {
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

        final String remoteBranch;
        if ("dogma".equals(suffix)) {
            // Central Dogma has no notion of branch.
            remoteBranch = "";
        } else {
            remoteBranch = remoteUri.getFragment();
        }

        return new RepositoryUri(URI.create(newRemoteUri), remotePath, firstNonNull(remoteBranch, ""));
    }

    private final URI uri;
    private final String path;
    private final String branch;

    private RepositoryUri(URI uri, String path, String branch) {
        this.uri = uri;
        this.path = path;
        this.branch = branch;
    }

    /**
     * Returns the URI of the repository.
     */
    public URI uri() {
        return uri;
    }

    /**
     * Returns the path in the repository.
     */
    public String path() {
        return path;
    }

    /**
     * Returns the branch name in the repository.
     */
    public String branch() {
        return branch;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RepositoryUri)) {
            return false;
        }

        final RepositoryUri that = (RepositoryUri) o;
        return uri.equals(that.uri) &&
               path.equals(that.path) &&
               branch.equals(that.branch);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, path, branch);
    }

    @Override
    public String toString() {
        return uri + path + '#' + branch;
    }
}
