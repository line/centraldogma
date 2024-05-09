/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.api;

import static com.linecorp.centraldogma.server.internal.api.GitHttpService.pktLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jgit.transport.GitProtocolConstants.VERSION_2_REQUEST;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ServerCacheControl;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class GitHttpServiceTest {

    @TempDir
    static File temporaryFolder;

    @TempDir
    static File temporaryFolder2;

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
            client.forRepo("foo", "bar").commit("push", Change.ofTextUpsert("/foo.txt", "hello foo!")).push()
                  .join();
        }
    };

    @Test
    void advertiseCapability() {
        advertiseCapability("bar.git");
        advertiseCapability("bar");
    }

    void advertiseCapability(String repoName) {
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.GET, "/foo/" + repoName + "/info/refs?service=git-upload-pack",
                                  "git-protocol", VERSION_2_REQUEST,
                                  HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        final AggregatedHttpResponse res = dogma.httpClient().execute(headers).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().get("content-type")).isEqualTo("application/x-git-upload-pack-advertisement");
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL))
                .isEqualTo(ServerCacheControl.REVALIDATED.asHeaderValue());
        assertThat(res.contentUtf8()).isEqualTo("001e# service=git-upload-pack\n0000" +
                                                "000eversion 2\n" +
                                                "000cls-refs\n" +
                                                "0020fetch=wait-for-done shallow\n" +
                                                "0017object-format=sha1\n" +
                                                "0000");
    }

    @Test
    void invalidRequestAdvertiseCapability() {
        RequestHeaders headers =
                RequestHeaders.of(HttpMethod.GET, "/foo/non-exist-repo/info/refs?service=git-upload-pack",
                                  "git-protocol", VERSION_2_REQUEST,
                                  HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        assertThat(dogma.httpClient().execute(headers).aggregate().join().status())
                .isSameAs(HttpStatus.NOT_FOUND);

        headers = RequestHeaders.of(HttpMethod.GET, "/foo/bar.git/info/refs?service=no-such-service",
                                    HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        assertThat(dogma.httpClient().execute(headers).aggregate().join().status())
                .isSameAs(HttpStatus.FORBIDDEN);

        headers = RequestHeaders.of(HttpMethod.GET, "/foo/bar.git/info/refs?service=git-upload-pack",
                                    "git-protocol", "invalid-version",
                                    HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        assertThat(dogma.httpClient().execute(headers).aggregate().join().status())
                .isSameAs(HttpStatus.BAD_REQUEST);
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void gitUploadPackRequest(boolean shallow) {
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/foo/bar.git/git-upload-pack",
                                  HttpHeaderNames.CONTENT_TYPE, "application/x-git-upload-pack-request",
                                  "git-protocol", VERSION_2_REQUEST,
                                  HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        AggregatedHttpResponse res = dogma.httpClient().execute(headers, lsRefsCommand())
                                          .aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().get("content-type")).isEqualTo("application/x-git-upload-pack-result");
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL))
                .isEqualTo(ServerCacheControl.REVALIDATED.asHeaderValue());
        final List<String> lines = Splitter.on('\n').trimResults().splitToList(res.contentUtf8());
        assertThat(lines.size()).isEqualTo(3);
        final Splitter splitter = Splitter.on(' ').trimResults();
        final List<String> headLineSplit = splitter.splitToList(lines.get(0));
        assertThat(headLineSplit.size()).isEqualTo(3);
        assertThat(headLineSplit.get(1)).isEqualTo("HEAD");
        assertThat(headLineSplit.get(2)).isEqualTo("symref-target:refs/heads/master");

        final List<String> masterLineSplit = splitter.splitToList(lines.get(1));
        assertThat(masterLineSplit.size()).isEqualTo(2);
        assertThat(masterLineSplit.get(1)).isEqualTo("refs/heads/master");

        // same oid
        assertThat(headLineSplit.get(0).substring(4)).isEqualTo(masterLineSplit.get(0).substring(4));

        res = dogma.httpClient().execute(headers, fetchCommand(headLineSplit.get(0).substring(4), shallow))
                   .aggregate().join();
        assertThat(res.headers().get("content-type")).isEqualTo("application/x-git-upload-pack-result");
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL))
                .isEqualTo(ServerCacheControl.REVALIDATED.asHeaderValue());
        final String[] contents = res.contentUtf8().split("\n");
        if (shallow) {
            assertThat(contents[0].trim()).isEqualTo("0011shallow-info");
            // in the format of "0035shallow f748c234dd9515d354daa9d8a8171a67e1a419ee"
            assertThat(contents[1].trim()).startsWith("0035shallow ");
            assertThat(contents[2].trim()).isEqualTo("0001000dpackfile");
        } else {
            // We will verify the contents under the gitClone test so just check the packfile.
            assertThat(contents[0].trim()).isEqualTo("000dpackfile");
        }
    }

    @Test
    void invalidGitUploadPackRequest() {
        RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/foo/non-exist-repo/git-upload-pack",
                                  HttpHeaderNames.CONTENT_TYPE, "application/x-git-upload-pack-request",
                                  "git-protocol", VERSION_2_REQUEST,
                                  HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        assertThat(dogma.httpClient().execute(headers).aggregate().join().status())
                .isSameAs(HttpStatus.NOT_FOUND);

        headers = RequestHeaders.of(HttpMethod.POST, "/foo/bar.git/git-upload-pack",
                                    HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8,
                                    HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        assertThat(dogma.httpClient().execute(headers).aggregate().join().status())
                .isSameAs(HttpStatus.BAD_REQUEST);

        headers = RequestHeaders.of(HttpMethod.POST, "/foo/bar.git/git-upload-pack",
                                    HttpHeaderNames.CONTENT_TYPE, "application/x-git-upload-pack-request",
                                    "git-protocol", "invalid-version",
                                    HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        assertThat(dogma.httpClient().execute(headers).aggregate().join().status())
                .isSameAs(HttpStatus.BAD_REQUEST);
    }

    @Test
    void gitClone() throws Exception {
        // shallow clone by jGit is added after 6.5.0 so we just test the normal clone.
        // https://projects.eclipse.org/projects/technology.jgit/releases/6.5.0/
        final Git git = clone(temporaryFolder);
        final Ref ref = assertRefs(git);

        byte[] fileContent = getFileContent(git, ref.getObjectId(), "/foo.txt");
        assertThat(new String(fileContent).trim()).isEqualTo("hello foo!");
        fileContent = getFileContent(git, ref.getObjectId(), "/bar.txt");
        assertThat(fileContent).isNull();
        git.close();

        // Commit another file.
        dogma.client().forRepo("foo", "bar")
             .commit("push", Change.ofTextUpsert("/bar.txt", "hello bar!"))
             .push()
             .join();

        // Clone again.
        final Git git1 = clone(temporaryFolder2);
        final Ref ref1 = assertRefs(git1);
        assertThat(ref).isNotEqualTo(ref1);

        fileContent = getFileContent(git1, ref1.getObjectId(), "/foo.txt");
        assertThat(new String(fileContent).trim()).isEqualTo("hello foo!");
        fileContent = getFileContent(git1, ref1.getObjectId(), "/bar.txt");
        assertThat(new String(fileContent).trim()).isEqualTo("hello bar!");

        // Update the previous file and fetch.
        dogma.client().forRepo("foo", "bar")
             .commit("push", Change.ofTextUpsert("/foo.txt", "hello foo second!"))
             .push()
             .join();
        final FetchCommand fetch = git1.fetch();
        addAuthHeader(fetch);
        final FetchResult call = fetch.call();
        assertThat(call.getAdvertisedRefs()).hasSize(1);
        final Ref ref2 = Iterables.getFirst(call.getAdvertisedRefs(), null);
        fileContent = getFileContent(git1, ref2.getObjectId(), "/foo.txt");
        assertThat(new String(fileContent).trim()).isEqualTo("hello foo second!");
        fileContent = getFileContent(git1, ref2.getObjectId(), "/bar.txt");
        assertThat(new String(fileContent).trim()).isEqualTo("hello bar!");

        git1.close();
    }

    private static Git clone(File directory) throws GitAPIException {
        final CloneCommand cloneCommand = Git.cloneRepository().setURI("http://127.0.0.1:" +
                                                                       dogma.serverAddress().getPort()
                                                                       + "/foo/bar.git")
                                             .setDirectory(directory);
        addAuthHeader(cloneCommand);
        return cloneCommand.call();
    }

    private static void addAuthHeader(TransportCommand<?, ?> command) {
        command.setTransportConfigCallback(transport -> {
            if (transport instanceof TransportHttp) {
                ((TransportHttp) transport).setAdditionalHeaders(
                        ImmutableMap.of(HttpHeaderNames.AUTHORIZATION.toString(),
                                        "Bearer anonymous"));
            }
        });
    }

    private static Ref assertRefs(Git git) throws IOException {
        final List<Ref> refs = git.getRepository().getRefDatabase().getRefs();
        assertThat(refs.size()).isEqualTo(3);
        final Ref headRef = refs.get(0);
        assertThat(headRef.isSymbolic()).isTrue();
        assertThat(headRef.getName()).isEqualTo("HEAD");

        final Ref masterRef = refs.get(1);
        assertThat(masterRef.getName()).isEqualTo("refs/heads/master");
        assertThat(headRef.getTarget()).isEqualTo(masterRef);

        final Ref originMasterRef = refs.get(2);
        assertThat(originMasterRef.getName()).isEqualTo("refs/remotes/origin/master");
        assertThat(masterRef.getObjectId()).isEqualTo(masterRef.getObjectId());
        assertThat(masterRef.getObjectId()).isEqualTo(originMasterRef.getObjectId());
        return masterRef;
    }

    private static String lsRefsCommand() {
        final StringBuilder sb = new StringBuilder();
        pktLine(sb, "command=ls-refs");
        pktLine(sb, "object-format=sha1");
        sb.append("0001"); // delim-pkt
        pktLine(sb, "peel");
        pktLine(sb, "symrefs");
        pktLine(sb, "ref-prefix HEAD");
        pktLine(sb, "ref-prefix refs/heads/");
        pktLine(sb, "ref-prefix refs/tags/");
        sb.append("0000");
        return sb.toString();
    }

    private static String fetchCommand(String oid, boolean shallow) {
        final StringBuilder sb = new StringBuilder();
        pktLine(sb, "command=fetch");
        pktLine(sb, "object-format=sha1");
        sb.append("0001"); // delim-pkt
        pktLine(sb, "thin-pack");
        pktLine(sb, "ofs-delta");
        if (shallow) {
            pktLine(sb, "deepen 1");
        }
        pktLine(sb, "want " + oid);
        pktLine(sb, "done");
        sb.append("0000");
        return sb.toString();
    }

    @Nullable
    private static byte[] getFileContent(Git git, ObjectId commitId, String fileName) throws IOException {
        try (ObjectReader reader = git.getRepository().newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = new RevWalk(reader)) {
            treeWalk.addTree(revWalk.parseTree(commitId).getId());

            while (treeWalk.next()) {
                if (treeWalk.getFileMode() == FileMode.TREE) {
                    treeWalk.enterSubtree();
                    continue;
                }
                if (fileName.equals('/' + treeWalk.getPathString())) {
                    final ObjectId objectId = treeWalk.getObjectId(0);
                    return reader.open(objectId).getBytes();
                }
            }
        }
        return null;
    }
}
