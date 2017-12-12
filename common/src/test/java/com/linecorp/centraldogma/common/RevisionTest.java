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

package com.linecorp.centraldogma.common;

import static com.linecorp.centraldogma.internal.Jackson.readValue;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.Test;

public class RevisionTest {

    @Test
    public void deserialization() throws Exception {
        assertThat(toRevision("2")).isEqualTo(new Revision(2));
        assertThat(toRevision("\"3\"")).isEqualTo(new Revision(3));
        assertThat(toRevision("\"4.0\"")).isEqualTo(new Revision(4));
        assertThat(toRevision("{ \"major\": 6 }")).isEqualTo(new Revision(6));
        assertThat(toRevision("{ \"major\": 7, \"minor\": 0 }")).isEqualTo(new Revision(7));

        // Special revisions:
        assertThat(toRevision("1")).isEqualTo(Revision.INIT);
        assertThat(toRevision("\"1\"")).isEqualTo(Revision.INIT);
        assertThat(toRevision("\"1.0\"")).isEqualTo(Revision.INIT);
        assertThat(toRevision("{ \"major\": 1 }")).isEqualTo(Revision.INIT);
        assertThat(toRevision("{ \"major\": 1, \"minor\": 0 }")).isEqualTo(Revision.INIT);
        assertThat(toRevision("-1")).isEqualTo(Revision.HEAD);
        assertThat(toRevision("\"-1\"")).isEqualTo(Revision.HEAD);
        assertThat(toRevision("\"-1.0\"")).isEqualTo(Revision.HEAD);
        assertThat(toRevision("{ \"major\": -1 }")).isEqualTo(Revision.HEAD);
        assertThat(toRevision("{ \"major\": -1, \"minor\": 0 }")).isEqualTo(Revision.HEAD);

        assertThat(toRevision("\"head\"")).isEqualTo(Revision.HEAD);
        assertThat(toRevision("\"HEAD\"")).isEqualTo(Revision.HEAD);
    }

    @Test
    public void serialization() throws Exception {
        assertThatJson(new Revision(9)).isEqualTo("9");
        assertThatJson(Revision.HEAD).isEqualTo("-1");
    }

    private static Revision toRevision(String text) throws IOException {
        return readValue(text, Revision.class);
    }
}
