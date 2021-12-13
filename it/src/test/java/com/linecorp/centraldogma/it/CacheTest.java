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
package com.linecorp.centraldogma.it;

import static com.linecorp.centraldogma.common.Revision.HEAD;
import static com.linecorp.centraldogma.common.Revision.INIT;
import static com.linecorp.centraldogma.testing.internal.TestUtil.normalizedDisplayName;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class CacheTest {

    private static final String REPO_FOO = "foo";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    private static final Supplier<Map<String, Double>> metersSupplier =
            () -> MoreMeters.measureAll(dogma.dogma().meterRegistry().get());

    @ParameterizedTest(name = "getFile [{index}: {0}]")
    @EnumSource(ClientType.class)
    void getFile(ClientType clientType, TestInfo testInfo) {
        final String project = normalizedDisplayName(testInfo);
        final CentralDogma client = clientType.client(dogma);
        client.createProject(project).join();
        client.createRepository(project, REPO_FOO).join();

        final Map<String, Double> meters1 = metersSupplier.get();
        final PushResult res = client.forRepo(project, REPO_FOO)
                                     .commit("Add a file", Change.ofTextUpsert("/foo.txt", "bar"))
                                     .push(HEAD)
                                     .join();

        final Map<String, Double> meters2 = metersSupplier.get();
        // Metadata needs to access to check a write quota (one cache miss).
        if (clientType == ClientType.LEGACY) {
            // NB: A push operation involves a history() operation to retrieve the last commit.
            //     Therefore we should observe one cache miss. (Thrift only)
            assertThat(missCount(meters2)).isEqualTo(missCount(meters1) + 2);
        } else {
            assertThat(missCount(meters2)).isEqualTo(missCount(meters1) + 1);
        }

        // First getFile() should miss.
        final Query<String> query = Query.ofText("/foo.txt");
        final Entry<?> entry = client.getFile(project, REPO_FOO, HEAD, query).join();
        final Map<String, Double> meters3 = metersSupplier.get();

        assertThat(missCount(meters3)).isEqualTo(missCount(meters2) + 1);

        // Subsequent getFile() should never miss.
        for (int i = 0; i < 3; i++) {
            final Map<String, Double> meters4 = metersSupplier.get();

            // Use the relative revision as well as the absolute revision.
            final Entry<?> cachedEntry1 = client.getFile(project, REPO_FOO, res.revision(), query).join();
            final Entry<?> cachedEntry2 = client.getFile(project, REPO_FOO, HEAD, query).join();

            // They should return the same result.
            assertThat(cachedEntry1).isEqualTo(entry);
            assertThat(cachedEntry1).isEqualTo(cachedEntry2);

            // .. and should hit the cache.
            final Map<String, Double> meters5 = metersSupplier.get();
            assertThat(hitCount(meters5)).isEqualTo(hitCount(meters4) + 2);
            assertThat(missCount(meters5)).isEqualTo(missCount(meters4));
        }
    }

    @ParameterizedTest(name = "history [{index}: {0}]")
    @EnumSource(ClientType.class)
    void history(ClientType clientType, TestInfo testInfo) {
        final String project = normalizedDisplayName(testInfo);
        final CentralDogma client = clientType.client(dogma);
        client.createProject(project).join();
        client.createRepository(project, REPO_FOO).join();

        final PushResult res1 = client.forRepo(project, REPO_FOO)
                                      .commit("Add a file", Change.ofTextUpsert("/foo.txt", "bar"))
                                      .push(HEAD).join();

        final Map<String, Double> meters1 = metersSupplier.get();

        // Get the history in various combination of from/to revisions.
        final List<Commit> history1 =
                client.getHistory(project, REPO_FOO, HEAD, new Revision(-2), "/**").join();
        final List<Commit> history2 =
                client.getHistory(project, REPO_FOO, HEAD, INIT, "/**").join();
        final List<Commit> history3 =
                client.getHistory(project, REPO_FOO, res1.revision(), new Revision(-2), "/**").join();
        final List<Commit> history4 =
                client.getHistory(project, REPO_FOO, res1.revision(), INIT, "/**").join();

        // and they should all same.
        assertThat(history1).isEqualTo(history2);
        assertThat(history1).isEqualTo(history3);
        assertThat(history1).isEqualTo(history4);

        final Map<String, Double> meters2 = metersSupplier.get();

        // Should miss once and hit 3 times.
        assertThat(missCount(meters2)).isEqualTo(missCount(meters1) + 1);
        assertThat(hitCount(meters2)).isEqualTo(hitCount(meters1) + 3);
    }

    @ParameterizedTest(name = "getDiffs [{index}: {0}]")
    @EnumSource(ClientType.class)
    void getDiffs(ClientType clientType, TestInfo testInfo) {
        final String project = normalizedDisplayName(testInfo);
        final CentralDogma client = clientType.client(dogma);
        client.createProject(project).join();
        client.createRepository(project, REPO_FOO).join();

        final PushResult res1 = client.forRepo(project, REPO_FOO)
                                      .commit("Add a file", Change.ofTextUpsert("/foo.txt", "bar"))
                                      .push(HEAD).join();

        final Map<String, Double> meters1 = metersSupplier.get();

        // Get the diffs in various combination of from/to revisions.
        final List<Change<?>> diff1 =
                client.getDiff(project, REPO_FOO, HEAD, new Revision(-2), PathPattern.all()).join();
        final List<Change<?>> diff2 =
                client.getDiff(project, REPO_FOO, HEAD, INIT, PathPattern.all()).join();
        final List<Change<?>> diff3 =
                client.getDiff(project, REPO_FOO, res1.revision(), new Revision(-2), PathPattern.all()).join();
        final List<Change<?>> diff4 =
                client.getDiff(project, REPO_FOO, res1.revision(), INIT, PathPattern.all()).join();

        // and they should all same.
        assertThat(diff1).isEqualTo(diff2);
        assertThat(diff1).isEqualTo(diff3);
        assertThat(diff1).isEqualTo(diff4);

        final Map<String, Double> meters2 = metersSupplier.get();

        // Should miss once and hit 3 times.
        assertThat(missCount(meters2)).isEqualTo(missCount(meters1) + 1);
        assertThat(hitCount(meters2)).isEqualTo(hitCount(meters1) + 3);
    }

    private static Double hitCount(Map<String, Double> meters) {
        return meters.get("cache.gets#count{cache=repository,result=hit}");
    }

    private static Double missCount(Map<String, Double> meters) {
        return meters.get("cache.gets#count{cache=repository,result=miss}");
    }
}
