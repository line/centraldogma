/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.storage.repository;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.centraldogma.server.mirror.MirrorUtil.normalizePath;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.MoreObjects;

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

        final String remoteBranch = remoteUri.getFragment();

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

    public URI uri() {
        return uri;
    }

    public String path() {
        return path;
    }

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
        return MoreObjects.toStringHelper(this)
                          .add("uri", uri)
                          .add("path", path)
                          .add("branch", branch)
                          .toString();
    }
}
