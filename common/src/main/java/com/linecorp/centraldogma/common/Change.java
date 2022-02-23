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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.jackson.Jackson;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.internal.jsonpatch.ReplaceMode;

import difflib.DiffUtils;
import difflib.Patch;

/**
 * A modification of an individual {@link Entry}.
 */
@JsonDeserialize(as = DefaultChange.class)
public interface Change<T> {

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#UPSERT_TEXT}.
     *
     * <p>Note that you should use {@link #ofJsonUpsert(String, String)} if the specified {@code path} ends with
     * {@code ".json"}. The {@link #ofJsonUpsert(String, String)} will check that the given {@code text} is a
     * valid JSON.
     *
     * @param path the path of the file
     * @param text the content of the file
     * @throws ChangeFormatException if the path ends with {@code ".json"}
     */
    static Change<String> ofTextUpsert(String path, String text) {
        requireNonNull(text, "text");
        validateFilePath(path, "path");
        if (EntryType.guessFromPath(path) == EntryType.JSON) {
            throw new ChangeFormatException("invalid file type: " + path +
                                            " (expected: a non-JSON file). Use Change.ofJsonUpsert() instead");
        }
        return new DefaultChange<>(path, ChangeType.UPSERT_TEXT, text);
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#UPSERT_JSON}.
     *
     * @param path the path of the file
     * @param jsonText the content of the file
     *
     * @throws ChangeFormatException if the specified {@code jsonText} is not a valid JSON
     */
    static Change<JsonNode> ofJsonUpsert(String path, String jsonText) {
        requireNonNull(jsonText, "jsonText");

        final JsonNode jsonNode;
        try {
            jsonNode = Jackson.ofJson().readTree(jsonText);
        } catch (IOException e) {
            throw new ChangeFormatException("failed to read a value as a JSON tree", e);
        }

        return new DefaultChange<>(path, ChangeType.UPSERT_JSON, jsonNode);
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#UPSERT_JSON}.
     *
     * @param path the path of the file
     * @param jsonNode the content of the file
     */
    static Change<JsonNode> ofJsonUpsert(String path, JsonNode jsonNode) {
        requireNonNull(jsonNode, "jsonNode");
        return new DefaultChange<>(path, ChangeType.UPSERT_JSON, jsonNode);
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#UPSERT_YAML}.
     *
     * @param path the path of the file
     * @param yamlText the content of the file
     */
    static Change<JsonNode> ofYamlUpsert(String path, String yamlText) {
        requireNonNull(yamlText, "yamlText");

        final JsonNode yamlNode;
        try {
            yamlNode = Jackson.ofYaml().readTree(yamlText);
        } catch (IOException e) {
            throw new ChangeFormatException("failed to read a value as a YAML tree", e);
        }

        return new DefaultChange<>(path, ChangeType.UPSERT_YAML, yamlNode);
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#UPSERT_YAML}.
     *
     * @param path the path of the file
     * @param yamlNode the content of the file
     */
    static Change<JsonNode> ofYamlUpsert(String path, JsonNode yamlNode) {
        requireNonNull(yamlNode, "yamlNode");
        return new DefaultChange<>(path, ChangeType.UPSERT_YAML, yamlNode);
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#REMOVE}.
     *
     * @param path the path of the file to remove
     */
    static Change<Void> ofRemoval(String path) {
        return new DefaultChange<>(path, ChangeType.REMOVE, null);
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#RENAME}.
     *
     * @param oldPath the old path of the file
     * @param newPath the new path of the file
     */
    static Change<String> ofRename(String oldPath, String newPath) {
        validateFilePath(oldPath, "oldPath");
        validateFilePath(newPath, "newPath");
        return new DefaultChange<>(oldPath, ChangeType.RENAME, newPath);
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#APPLY_TEXT_PATCH}.
     *
     * <p>Note that you should use {@link #ofJsonPatch(String, String, String)} if the specified {@code path}
     * ends with {@code ".json"}. The {@link #ofJsonUpsert(String, String)} will check that
     * the given {@code oldText} and {@code newText} are valid JSONs.
     *
     * @param path the path of the file
     * @param oldText the old content of the file
     * @param newText the new content of the file
     * @throws ChangeFormatException if the path ends with {@code ".json"}
     */
    static Change<String> ofTextPatch(String path, @Nullable String oldText, String newText) {
        validateFilePath(path, "path");
        requireNonNull(newText, "newText");
        if (EntryType.guessFromPath(path) == EntryType.JSON) {
            throw new ChangeFormatException("invalid file type: " + path +
                                            " (expected: a non-JSON file). Use Change.ofJsonPatch() instead");
        }

        final List<String> oldLineList = oldText == null ? Collections.emptyList()
                                                         : Util.stringToLines(oldText);
        final List<String> newLineList = Util.stringToLines(newText);

        final Patch<String> patch = DiffUtils.diff(oldLineList, newLineList);
        final List<String> unifiedDiff = DiffUtils.generateUnifiedDiff(path, path, oldLineList, patch, 3);

        return new DefaultChange<>(path, ChangeType.APPLY_TEXT_PATCH, String.join("\n", unifiedDiff));
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#APPLY_TEXT_PATCH}.
     *
     * <p>Note that you should use {@link #ofJsonPatch(String, String)} if the specified {@code path}
     * ends with {@code ".json"}. The {@link #ofJsonUpsert(String, String)} will check that
     * the given {@code textPatch} is a valid JSON.
     *
     * @param path the path of the file
     * @param textPatch the patch in
     *                  <a href="https://en.wikipedia.org/wiki/Diff_utility#Unified_format">unified format</a>
     * @throws ChangeFormatException if the path ends with {@code ".json"}
     */
    static Change<String> ofTextPatch(String path, String textPatch) {
        validateFilePath(path, "path");
        requireNonNull(textPatch, "textPatch");
        if (EntryType.guessFromPath(path) == EntryType.JSON) {
            throw new ChangeFormatException("invalid file type: " + path +
                                            " (expected: a non-JSON file). Use Change.ofJsonPatch() instead");
        }

        return new DefaultChange<>(path, ChangeType.APPLY_TEXT_PATCH, textPatch);
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#APPLY_JSON_PATCH}.
     *
     * @param path the path of the file
     * @param oldJsonText the old content of the file
     * @param newJsonText the new content of the file
     *
     * @throws ChangeFormatException if the specified {@code oldJsonText} or {@code newJsonText} is
     *                               not a valid JSON
     */
    static Change<JsonNode> ofJsonPatch(String path, @Nullable String oldJsonText, String newJsonText) {
        requireNonNull(newJsonText, "newJsonText");

        final JsonNode oldJsonNode;
        final JsonNode newJsonNode;
        try {
            oldJsonNode = Jackson.ofJson().readTree(oldJsonText);
            newJsonNode = Jackson.ofJson().readTree(newJsonText);
        } catch (IOException e) {
            throw new ChangeFormatException("failed to read a value as a JSON tree", e);
        }

        return new DefaultChange<>(path, ChangeType.APPLY_JSON_PATCH,
                                   JsonPatch.generate(oldJsonNode, newJsonNode, ReplaceMode.SAFE).toJson());
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#APPLY_JSON_PATCH}.
     *
     * @param path the path of the file
     * @param oldJsonNode the old content of the file
     * @param newJsonNode the new content of the file
     */
    static Change<JsonNode> ofJsonPatch(String path, @Nullable JsonNode oldJsonNode, JsonNode newJsonNode) {
        requireNonNull(newJsonNode, "newJsonNode");

        if (oldJsonNode == null) {
            oldJsonNode = Jackson.nullNode;
        }

        return new DefaultChange<>(path, ChangeType.APPLY_JSON_PATCH,
                                   JsonPatch.generate(oldJsonNode, newJsonNode, ReplaceMode.SAFE).toJson());
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#APPLY_JSON_PATCH}.
     *
     * @param path the path of the file
     * @param jsonPatchText the patch in <a href="https://tools.ietf.org/html/rfc6902">JSON patch format</a>
     *
     * @throws ChangeFormatException if the specified {@code jsonPatchText} is not a valid JSON
     */
    static Change<JsonNode> ofJsonPatch(String path, String jsonPatchText) {
        requireNonNull(jsonPatchText, "jsonPatchText");

        final JsonNode jsonPatchNode;
        try {
            jsonPatchNode = Jackson.ofJson().readTree(jsonPatchText);
        } catch (IOException e) {
            throw new ChangeFormatException("failed to read a value as a JSON tree", e);
        }

        return ofJsonPatch(path, jsonPatchNode);
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#APPLY_JSON_PATCH}.
     *
     * @param path the path of the file
     * @param jsonPatchNode the patch in <a href="https://tools.ietf.org/html/rfc6902">JSON patch format</a>
     */
    static Change<JsonNode> ofJsonPatch(String path, JsonNode jsonPatchNode) {
        requireNonNull(jsonPatchNode, "jsonPatchNode");

        return new DefaultChange<>(path, ChangeType.APPLY_JSON_PATCH, jsonPatchNode);
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#APPLY_YAML_PATCH}.
     *
     * @param path the path of the file
     * @param oldYamlText the old content of the file
     * @param newYamlText the new content of the file
     *
     * @throws ChangeFormatException if the specified {@code oldYamlText} or {@code newYamlText} is
     *                               not a valid YAML
     */
    static Change<JsonNode> ofYamlPatch(String path, @Nullable String oldYamlText, String newYamlText) {
        requireNonNull(newYamlText, "newYamlText");

        final JsonNode oldYamlNode;
        final JsonNode newYamlNode;
        try {
            oldYamlNode = Jackson.ofYaml().readTree(oldYamlText);
            newYamlNode = Jackson.ofYaml().readTree(newYamlText);
        } catch (IOException e) {
            throw new ChangeFormatException("failed to read a value as a YAML tree", e);
        }

        return new DefaultChange<>(path, ChangeType.APPLY_YAML_PATCH,
                                   JsonPatch.generate(oldYamlNode, newYamlNode, ReplaceMode.SAFE).toJson());
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#APPLY_YAML_PATCH}.
     *
     * @param path the path of the file
     * @param oldYamlNode the old content of the file
     * @param newYamlNode the new content of the file
     */
    static Change<JsonNode> ofYamlPatch(String path, @Nullable JsonNode oldYamlNode, JsonNode newYamlNode) {
        requireNonNull(newYamlNode, "newYamlNode");

        if (oldYamlNode == null) {
            oldYamlNode = Jackson.nullNode;
        }

        return new DefaultChange<>(path, ChangeType.APPLY_YAML_PATCH,
                                   JsonPatch.generate(oldYamlNode, newYamlNode, ReplaceMode.SAFE).toJson());
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#APPLY_YAML_PATCH}.
     *
     * @param path the path of the file
     * @param yamlPatchText the patch in <a href="https://tools.ietf.org/html/rfc6902">JSON patch format</a>
     *
     * @throws ChangeFormatException if the specified {@code yamlPatchText} is not a valid JSON
     */
    static Change<JsonNode> ofYamlPatch(String path, String yamlPatchText) {
        requireNonNull(yamlPatchText, "yamlPatchText");

        final JsonNode yamlPatchNode;
        try {
            // YAML patch is in JSON format
            yamlPatchNode = Jackson.ofJson().readTree(yamlPatchText);
        } catch (IOException e) {
            throw new ChangeFormatException("failed to read a value as a JSON tree", e);
        }

        return ofYamlPatch(path, yamlPatchNode);
    }

    /**
     * Returns a newly-created {@link Change} whose type is {@link ChangeType#APPLY_YAML_PATCH}.
     *
     * @param path the path of the file
     * @param yamlPatchNode the patch in <a href="https://tools.ietf.org/html/rfc6902">JSON patch format</a>
     */
    static Change<JsonNode> ofYamlPatch(String path, JsonNode yamlPatchNode) {
        requireNonNull(yamlPatchNode, "yamlPatchNode");

        return new DefaultChange<>(path, ChangeType.APPLY_YAML_PATCH, yamlPatchNode);
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
            case YAML:
                return ofYamlUpsert(targetPath, content);
            case TEXT:
                return ofTextUpsert(targetPath, content);
            default:
                throw new Error("unexpected entry type: " + entryType);
        }
    }

    /**
     * Returns the type of the {@link Change}.
     */
    @JsonProperty
    ChangeType type();

    /**
     * Returns the path of the {@link Change}.
     */
    @JsonProperty
    String path();

    /**
     * Returns the content of the {@link Change}, which depends on the {@link #type()}.
     */
    @Nullable
    @JsonProperty
    T content();

    /**
     * Returns the textual representation of {@link #content()}.
     */
    @Nullable
    String contentAsText();
}
