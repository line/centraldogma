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

import static com.linecorp.centraldogma.internal.Util.validateDirPath;
import static com.linecorp.centraldogma.internal.Util.validateFilePath;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.jsonpatch.ReplaceMode;
import com.linecorp.centraldogma.internal.jsonpatch.diff.JsonDiff;

import difflib.DiffUtils;
import difflib.Patch;

@JsonDeserialize(as = DefaultChange.class)
public interface Change<T> {

    static Change<String> ofTextUpsert(String path, String text) {
        requireNonNull(text, "text");
        return new DefaultChange<>(path, ChangeType.UPSERT_TEXT, text);
    }

    static Change<JsonNode> ofJsonUpsert(String path, String jsonText) {
        requireNonNull(jsonText, "jsonText");

        final JsonNode jsonNode;
        try {
            jsonNode = Jackson.readTree(jsonText);
        } catch (IOException e) {
            throw new ChangeFormatException("failed to read a value as a JSON tree", e);
        }

        return new DefaultChange<>(path, ChangeType.UPSERT_JSON, jsonNode);
    }

    static Change<JsonNode> ofJsonUpsert(String path, JsonNode jsonNode) {
        requireNonNull(jsonNode, "jsonNode");
        return new DefaultChange<>(path, ChangeType.UPSERT_JSON, jsonNode);
    }

    static Change<Void> ofRemoval(String path) {
        return new DefaultChange<>(path, ChangeType.REMOVE, null);
    }

    static Change<String> ofRename(String oldPath, String newPath) {
        validateFilePath(oldPath, "oldPath");
        validateFilePath(newPath, "newPath");
        return new DefaultChange<>(oldPath, ChangeType.RENAME, newPath);
    }

    static Change<String> ofTextPatch(String path, String oldText, String newText) {
        validateFilePath(path, "path");
        requireNonNull(newText, "newText");

        final List<String> oldLineList = oldText == null ? Collections.emptyList()
                                                         : Util.stringToLines(oldText);
        final List<String> newLineList = Util.stringToLines(newText);

        final Patch<String> patch = DiffUtils.diff(oldLineList, newLineList);
        final List<String> unifiedDiff = DiffUtils.generateUnifiedDiff(path, path, oldLineList, patch, 3);

        return new DefaultChange<>(path, ChangeType.APPLY_TEXT_PATCH, String.join("\n", unifiedDiff));
    }

    static Change<String> ofTextPatch(String path, String textPatch) {
        validateFilePath(path, "path");
        requireNonNull(textPatch, "textPatch");

        return new DefaultChange<>(path, ChangeType.APPLY_TEXT_PATCH, textPatch);
    }

    static Change<JsonNode> ofJsonPatch(String path, String oldJsonText, String newJsonText) {

        validateFilePath(path, "path");
        requireNonNull(newJsonText, "newJsonText");

        JsonNode oldJsonNode;
        JsonNode newJsonNode;
        try {
            oldJsonNode = oldJsonText == null ? Jackson.nullNode
                                              : Jackson.readTree(oldJsonText);
            newJsonNode = Jackson.readTree(newJsonText);
        } catch (IOException e) {
            throw new ChangeFormatException("failed to read a value as a JSON tree", e);
        }

        return new DefaultChange<>(path, ChangeType.APPLY_JSON_PATCH, JsonDiff.asJson(oldJsonNode, newJsonNode,
                                                                                      ReplaceMode.SAFE));
    }

    static Change<JsonNode> ofJsonPatch(String path, JsonNode oldJsonNode, JsonNode newJsonNode) {
        validateFilePath(path, "path");
        requireNonNull(newJsonNode, "newJsonNode");

        if (oldJsonNode == null) {
            oldJsonNode = Jackson.nullNode;
        }

        return new DefaultChange<>(path, ChangeType.APPLY_JSON_PATCH, JsonDiff.asJson(oldJsonNode, newJsonNode,
                                                                                      ReplaceMode.SAFE));
    }

    static Change<JsonNode> ofJsonPatch(String path, String jsonPatchText) {
        requireNonNull(jsonPatchText, "jsonPatchText");

        final JsonNode jsonPatchNode;
        try {
            jsonPatchNode = Jackson.readTree(jsonPatchText);
        } catch (IOException e) {
            throw new ChangeFormatException("failed to read a value as a JSON tree", e);
        }

        return ofJsonPatch(path, jsonPatchNode);
    }

    static Change<JsonNode> ofJsonPatch(String path, JsonNode jsonPatchNode) {
        validateFilePath(path, "path");
        requireNonNull(jsonPatchNode, "jsonPatchNode");

        return new DefaultChange<>(path, ChangeType.APPLY_JSON_PATCH, jsonPatchNode);
    }

    /**
     * Creates a {@link List} of upsert {@link Change}s from all files under the specified directory
     * recursively.
     *
     * @param sourcePath the path to the import directory
     * @param targetPath the target directory path of the imported {@link Change}s
     *
     * @throws IOError if I/O error occurs
     */
    static List<Change<?>> fromDirectory(Path sourcePath, String targetPath) {
        requireNonNull(sourcePath, "sourcePath");
        validateDirPath(targetPath, "targetPath");

        if (!Files.isDirectory(sourcePath)) {
            throw new IllegalArgumentException("sourcePath: " + sourcePath + " (must be a directory)");
        }

        final String finalTargetPath;
        if (!targetPath.endsWith("/")) {
            finalTargetPath = targetPath + '/';
        } else {
            finalTargetPath = targetPath;
        }

        try (Stream<Path> s = Files.find(sourcePath, Integer.MAX_VALUE, (p, a) -> a.isRegularFile())) {
            final int baseLength = sourcePath.toString().length() + 1;
            return s.map(sourceFilePath -> {
                final String targetFilePath =
                        finalTargetPath +
                        sourceFilePath.toString().substring(baseLength).replace(File.separatorChar, '/');

                return fromFile(sourceFilePath, targetFilePath);
            }).collect(Collectors.toList());
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    /**
     * Creates a new {@link Change} from the file at the specified location.
     *
     * @param sourcePath the path to the regular file to import
     * @param targetPath the target path of the imported {@link Change}
     */
    static Change<?> fromFile(Path sourcePath, String targetPath) {
        requireNonNull(sourcePath, "sourcePath");
        validateFilePath(targetPath, "targetPath");

        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("sourcePath: " + sourcePath + " (must be a regular file)");
        }

        if (targetPath.endsWith("/")) {
            throw new IllegalArgumentException("targetPath: " + targetPath + " (must be a regular file path)");
        }

        final EntryType entryType = EntryType.guessFromPath(targetPath);
        final String content;
        try {
            content = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOError(e);
        }

        switch (entryType) {
        case JSON:
            return ofJsonUpsert(targetPath, content);
        case TEXT:
            return ofTextUpsert(targetPath, content);
        default:
            throw new Error("unexpected entry type: " + entryType);
        }
    }

    @JsonProperty
    ChangeType type();

    @JsonProperty
    String path();

    @JsonProperty
    T content();

    String contentAsText();
}
