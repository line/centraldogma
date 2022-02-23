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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Revision;

class FileHistoryAndDiffTest {

    @RegisterExtension
    static final CentralDogmaExtensionWithScaffolding dogma = new CentralDogmaExtensionWithScaffolding();

    // getDiff

    @Test
    void history() {
        final CentralDogma client = ClientType.DEFAULT.client(dogma);
        for (int i = 0; i < 10; i++) {
            final String path = i % 2 == 0 ? "/even_json_file.json" : "/odd_json_file.json";
            final Change<JsonNode> change = Change.ofJsonUpsert(path, String.format("{ \"key\" : \"%d\"}", i));
            client.forRepo(dogma.project(), dogma.repo1())
                  .commit(String.valueOf(i), change)
                  .push().join();
        }

        List<Commit> commits = client.forRepo(dogma.project(), dogma.repo1())
                                     .history(PathPattern.of("/even_json_file.json"))
                                     .get(Revision.HEAD, Revision.INIT)
                                     .join();
        List<Integer> summaries = extractSummaryAsInt(commits);
        assertThat(summaries).containsExactly(8, 6, 4, 2, 0);

        commits = client.forRepo(dogma.project(), dogma.repo1())
                        .history(PathPattern.of("/even_json_file.json"))
                        .maxCommits(3)
                        .get(Revision.HEAD, Revision.INIT)
                        .join();
        summaries = extractSummaryAsInt(commits);
        assertThat(summaries).containsExactly(8, 6, 4);

        commits = client.forRepo(dogma.project(), dogma.repo1())
                        .history(PathPattern.of("/odd_json_file.json"))
                        .get(Revision.HEAD, Revision.INIT)
                        .join();
        summaries = extractSummaryAsInt(commits);
        assertThat(summaries).containsExactly(9, 7, 5, 3, 1);
    }

    private static ImmutableList<Integer> extractSummaryAsInt(List<Commit> commits) {
        return commits.stream()
                      .map(Commit::summary)
                      .map(Integer::valueOf)
                      .collect(toImmutableList());
    }
}
