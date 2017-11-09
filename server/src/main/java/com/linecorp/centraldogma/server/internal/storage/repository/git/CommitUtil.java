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

package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.StringWriter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.storage.StorageException;

final class CommitUtil {

    private static final String FIELD_NAME_SUMMARY = "summary";
    private static final String FIELD_NAME_DETAIL = "detail";
    private static final String FIELD_NAME_MARKUP = "markup";
    private static final String FIELD_NAME_REVISION = "revision";

    static String toJsonString(String summary, String detail, Markup markup, Revision nextRevision) {
        try {
            StringWriter stringWriter = new StringWriter();
            JsonGenerator jsonGenerator = Jackson.createPrettyGenerator(stringWriter);
            jsonGenerator.writeStartObject();
            jsonGenerator.writeStringField(FIELD_NAME_SUMMARY, summary);
            jsonGenerator.writeStringField(FIELD_NAME_DETAIL, detail);
            jsonGenerator.writeStringField(FIELD_NAME_MARKUP, markup.nameLowercased());
            jsonGenerator.writeStringField(FIELD_NAME_REVISION, nextRevision.text());
            jsonGenerator.writeEndObject();
            jsonGenerator.close();
            return stringWriter.toString();
        } catch (IOException e) {
            throw new StorageException("failed to generate a JSON string", e);
        }
    }

    static Revision extractRevision(String jsonString) {
        try {
            JsonNode jsonNode = Jackson.readTree(jsonString);
            return new Revision(Jackson.textValue(jsonNode.get(FIELD_NAME_REVISION), ""));
        } catch (Exception e) {
            throw new StorageException("failed to extract revision from " + jsonString, e);
        }
    }

    static Commit newCommit(Author author, long when, String jsonString) {
        requireNonNull(author, "author");
        when = when / 1000L * 1000L; // Drop the milliseconds
        try {
            JsonNode jsonNode = Jackson.readTree(jsonString);

            final String summary = Jackson.textValue(jsonNode.get(FIELD_NAME_SUMMARY), "");
            final String detail = Jackson.textValue(jsonNode.get(FIELD_NAME_DETAIL), "");

            final Markup markup;
            switch (Jackson.textValue(jsonNode.get(FIELD_NAME_MARKUP), "")) {
            case "plaintext":
                markup = Markup.PLAINTEXT;
                break;
            case "markdown":
                markup = Markup.MARKDOWN;
                break;
            default:
                markup = Markup.UNKNOWN;
            }

            final Revision revision = new Revision(Jackson.textValue(jsonNode.get(FIELD_NAME_REVISION), ""));

            return new Commit(revision, author, when, summary, detail, markup);
        } catch (Exception e) {
            throw new StorageException("failed to create a Commit", e);
        }
    }

    private CommitUtil() {}
}
