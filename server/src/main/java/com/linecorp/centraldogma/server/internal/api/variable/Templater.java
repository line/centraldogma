/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.centraldogma.server.internal.api.variable;

import static com.linecorp.centraldogma.server.internal.api.variable.VariableServiceV1.crudContext;

import java.io.StringWriter;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.TemplateProcessingException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.repository.git.CrudOperation;
import com.linecorp.centraldogma.server.internal.storage.repository.git.DefaultCrudOperation;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.HasRevision;
import com.linecorp.centraldogma.server.storage.repository.Repository;

import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;

public final class Templater {

    // The first found variable file will be used from the list.
    private static final List<String> VARIABLE_FILES =
            ImmutableList.of("/.variables.json", "/.variables.json5", "/.variables.yaml", "/.variables.yml");
    private static final String VARIABLE_FILES_PATTERN = Joiner.on(",").join(VARIABLE_FILES);

    private final CrudOperation<Variable> crudRepo;
    private final LoadingCache<Entry<?>, Template> cache;

    public Templater(CommandExecutor executor, ProjectManager pm) {
        crudRepo = new DefaultCrudOperation<>(Variable.class, executor, pm);
        final Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setDefaultEncoding("UTF-8");
        cfg.setLogTemplateExceptions(false);
        cfg.setWrapUncheckedExceptions(true);
        cfg.setFallbackOnNullLoopVariable(false);
        cfg.setClassicCompatible(false);
        cfg.setNumberFormat("computer");
        cfg.setBooleanFormat("c");
        cfg.setAPIBuiltinEnabled(false);
        cfg.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);

        cache = Caffeine.newBuilder()
                        .expireAfterAccess(Duration.ofHours(1))
                        .maximumSize(4096)
                        .build(key -> {
                            final String content;
                            if (key.rawContent() != null) {
                                content = key.rawContent();
                            } else {
                                // The entry processed by JSON-Path does not have a raw content.
                                content = key.contentAsPrettyText();
                            }
                            return new Template(key.path(), content, cfg);
                        });
    }

    public <T> CompletableFuture<Entry<T>> render(Repository repo, Entry<T> entry,
                                                  @Nullable String variableFile,
                                                  @Nullable Revision variableRevision) {
        if (!entry.hasContent()) {
            return UnmodifiableFuture.completedFuture(entry);
        }
        if (variableRevision == null) {
            variableRevision = Revision.HEAD;
        }

        final String projectName = repo.parent().name();
        // TODO(ikhoon): Optimize by caching the rendering result for the same set of variables and template.
        return mergeVariables(crudRepo.findAll(crudContext(projectName, variableRevision)),
                              crudRepo.findAll(crudContext(projectName, repo.name(), variableRevision)),
                              findVariableFile(repo, entry, variableFile))
                .thenApply(variables -> process(entry, variables))
                .toCompletableFuture();
    }

    private static CompletableFuture<Map<String, Object>> findVariableFile(
            Repository repo, Entry<?> entry, @Nullable String variableFile) {
        final String entryPath = entry.path();
        final Revision revision = entry.revision();

        if (Strings.isNullOrEmpty(variableFile)) {
            // If no specific variable file is provided, look for the default ones.
            final int lastSlash = entryPath.lastIndexOf('/');
            final String directory = entryPath.substring(0, lastSlash);
            final String filePattern = VARIABLE_FILES.stream()
                                                     .map(file -> directory + file)
                                                     .collect(Collectors.joining(","));
            // First, try to find variable files in the same directory as the template.
            return repo.find(revision, filePattern).thenCompose(entries -> {
                if (!entries.isEmpty()) {
                    return UnmodifiableFuture.completedFuture(entries);
                }
                // If not found, try to find variable files in the root directory.
                return repo.find(revision, VARIABLE_FILES_PATTERN);
            }).thenApply(entries -> {
                final Entry<?> chosen = chooseVariableFile(entries);
                if (chosen == null) {
                    return ImmutableMap.of();
                }
                return parseVariableFile(chosen);
            });
        }

        // If a specific variable file is provided, use it instead of the default ones.
        return repo.getOrNull(revision, variableFile).thenApply(entry0 -> {
            if (entry0 == null) {
                return ImmutableMap.of();
            }

            if (entry0.type().type() != JsonNode.class) {
                throw new TemplateProcessingException(
                        "The variable file must be a JSON or YAML type: " + variableFile);
            }
            return parseVariableFile(entry0);
        });
    }

    @Nullable
    private static Entry<?> chooseVariableFile(Map<String, Entry<?>> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        if (entries.size() == 1) {
            return entries.values().iterator().next();
        }

        final Collection<Entry<?>> values = entries.values();
        for (String variableFile : VARIABLE_FILES) {
            for (Entry<?> entry : values) {
                if (entry.path().endsWith(variableFile)) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> parseVariableFile(Entry<?> entry) {
        try {
            final JsonNode jsonNode = entry.contentAsJson();
            if (!jsonNode.isObject()) {
                throw new TemplateProcessingException(
                        "The variable file must contain a JSON object: " + entry.path());
            }
            final ObjectNode node = (ObjectNode) jsonNode;
            //noinspection unchecked
            return Jackson.treeToValue(node, Map.class);
        } catch (JsonProcessingException e) {
            throw new TemplateProcessingException(
                    "Failed to parse the variable file: " + entry.path() + ".\n" + e.getMessage(), e);
        }
    }

    private <T> Entry<T> process(Entry<T> entry, Map<String, Object> variables) {
        final StringWriter out = new StringWriter();
        final Template template = cache.get(entry);
        final int revision = (int) variables.remove("revision");
        try {
            template.process(variables, out);
            //noinspection unchecked
            Entry<T> newEntry = (Entry<T>) newEntryWithContent(entry, out.toString());
            if (revision != Integer.MAX_VALUE) {
                newEntry = newEntry.withVariableRevision(new Revision(revision));
            }
            return newEntry;
        } catch (Exception e) {
            throw new TemplateProcessingException(
                    "Failed to process the template for " + entry.path() + ".\n" + e.getMessage(), e);
        }
    }

    private static <T> Entry<?> newEntryWithContent(Entry<?> entry, String content) {
        switch (entry.type()) {
            case TEXT:
                return Entry.ofText(entry.revision(), entry.path(), content);
            case JSON:
                try {
                    return Entry.ofJson(entry.revision(), entry.path(), content);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            case YAML:
                try {
                    return Entry.ofYaml(entry.revision(), entry.path(), content);
                } catch (JsonParseException e) {
                    throw new IllegalStateException(e);
                }
            case DIRECTORY:
            default:
                // Should not reach here.
                throw new Error();
        }
    }

    private static CompletionStage<Map<String, Object>> mergeVariables(
            CompletableFuture<List<HasRevision<Variable>>> projFuture,
            CompletableFuture<List<HasRevision<Variable>>> repoFuture,
            CompletableFuture<Map<String, Object>> fileFuture) {
        return CompletableFutures.combine(
                projFuture, repoFuture, fileFuture,
                (projVars, repoVars, fileVars) -> {
                    final ImmutableMap.Builder<String, Object> builder =
                            ImmutableMap.builderWithExpectedSize(
                                    projVars.size() + repoVars.size() + fileVars.size());

                    int revision = Integer.MAX_VALUE;
                    for (HasRevision<Variable> it : projVars) {
                        final Variable variable = it.object();
                        revision = Math.min(it.revision().major(), revision);
                        builder.put(variable.id(), parseValue(variable));
                    }
                    for (HasRevision<Variable> it : repoVars) {
                        final Variable variable = it.object();
                        revision = Math.min(it.revision().major(), revision);
                        // Repo-level variables override project-level ones.
                        builder.put(variable.id(), parseValue(variable));
                    }
                    // File-level variables has the highest precedence.
                    builder.putAll(fileVars);

                    final Map<String, Object> variables = builder.buildKeepingLast();
                    // Prefix variables map with "vars" key.
                    // This allows using "vars.varName" in the template.
                    // TODO(ikhoon): Support secret variables that will be prefixed with "secrets" key.
                    final Map<String, Object> vars = new HashMap<>();
                    vars.put("vars", variables);
                    // Revision is needed to set variableRevision in the rendered entry.
                    vars.put("revision", revision);
                    return vars;
                });
    }

    private static Object parseValue(Variable variable) {
        switch (variable.type()) {
            case JSON:
                try {
                    return Jackson.readValue(variable.value(), Object.class);
                } catch (JsonProcessingException e) {
                    // Should not reach here as the value has been validated before storing.
                    throw new IllegalStateException(e);
                }
            case STRING:
                return variable.value();
            default:
                // Should not reach here.
                throw new Error();
        }
    }
}
