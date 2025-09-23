/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.client.ReplicationLagTolerantCentralDogma.RepoId;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

final class ReplicationLagTolerantCentralDogmaRevisionCacheTest {

    @RegisterExtension
    private static final CentralDogmaExtension extension = new CentralDogmaExtension();

    @Test
    void revisionCacheIsRemovedWhenProjectOrRepositoryRemoved() throws Exception {
        final CentralDogma dogma = extension.client();
        assertThat(dogma).isInstanceOf(ReplicationLagTolerantCentralDogma.class);
        final ReplicationLagTolerantCentralDogma lagTolerantCentralDogma =
                (ReplicationLagTolerantCentralDogma) dogma;

        dogma.createProject("foo").join();
        dogma.createRepository("foo", "bar").join();
        dogma.createRepository("foo", "baz").join();

        dogma.normalizeRevision("foo", "bar", Revision.HEAD).join();
        dogma.forRepo("foo", "bar")
             .commit("Summary", Change.ofTextUpsert("/a.txt", "Hello, World!"))
             .push()
             .join();
        dogma.forRepo("foo", "baz")
             .commit("Summary", Change.ofTextUpsert("/a.txt", "Hello, World!"))
             .push()
             .join();

        assertThat(lagTolerantCentralDogma.latestKnownRevisions.get(new RepoId("foo", "bar")))
                .isEqualTo(new Revision(2));
        assertThat(lagTolerantCentralDogma.latestKnownRevisions.get(new RepoId("foo", "baz")))
                .isEqualTo(new Revision(2));

        dogma.removeRepository("foo", "baz").join();
        assertThat(lagTolerantCentralDogma.latestKnownRevisions.get(new RepoId("foo", "bar")))
                .isEqualTo(new Revision(2));
        assertThat(lagTolerantCentralDogma.latestKnownRevisions.get(new RepoId("foo", "baz")))
                .isNull();

        dogma.removeProject("foo").join();
        assertThat(lagTolerantCentralDogma.latestKnownRevisions).isEmpty();

        // Purge the project to create it again.
        dogma.purgeProject("foo").join();

        dogma.createProject("foo").join();
        dogma.createRepository("foo", "bar").join();
        assertThat(lagTolerantCentralDogma.latestKnownRevisions.get(new RepoId("foo", "bar")))
                .isNull();

        dogma.normalizeRevision("foo", "bar", Revision.HEAD).join();
        assertThat(lagTolerantCentralDogma.latestKnownRevisions.get(new RepoId("foo", "bar")))
                .isEqualTo(new Revision(1));
    }
}
