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

package com.linecorp.centraldogma.server.internal.plugin;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

public final class PluginManager {

    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);

    private static final String PLUGINS_JSON = "/plugins.json";
    private static final String PLUGINS_FIELD = "plugins";

    private static final Plugin[] NO_PLUGINS = new Plugin[0];

    private final Project project;
    private volatile Plugin[] plugins;

    public PluginManager(Project project) {
        this.project = requireNonNull(project, "project");
        reload();
    }

    public Revision reload() {
        final Repository metaRepo = project.metaRepo();
        final Revision revision = metaRepo.normalizeNow(Revision.HEAD);
        plugins = loadPlugins(metaRepo, revision);
        return revision;
    }

    private Plugin[] loadPlugins(Repository metaRepo, Revision revision) {
        logger.info("Loading plugins for project: {} (revision: {})", project.name(), revision.text());

        @SuppressWarnings("unchecked")
        final Entry<JsonNode> entry = (Entry<JsonNode>) metaRepo.getOrElse(revision, PLUGINS_JSON, null);
        if (entry == null) {
            logger.info("Project '{}' contains no plugins.", project.name());
            return NO_PLUGINS;
        }

        if (entry.type() != EntryType.JSON) {
            logger.warn("{} is not a JSON object ({}); ignoring.", PLUGINS_JSON, entry.type());
            return NO_PLUGINS;
        }

        final JsonNode plugins = entry.content().findValue(PLUGINS_FIELD);
        if (plugins == null) {
            logger.warn("{} does not have the '{}' field; ignoring.", PLUGINS_JSON, PLUGINS_FIELD);
            return NO_PLUGINS;
        }

        if (plugins.getNodeType() != JsonNodeType.ARRAY) {
            logger.warn("{} has the '{}' field, but it is not an array ({}); ignoring.",
                        PLUGINS_JSON, PLUGINS_FIELD, plugins.getNodeType());
            return NO_PLUGINS;
        }

        if (!toStream(plugins).allMatch(JsonNode::isTextual)) {
            logger.warn("{} has the '{}' field, but it contains a non-textual element; ignoring.",
                        PLUGINS_JSON, PLUGINS_FIELD);
            return NO_PLUGINS;
        }

        final List<Plugin> loadedPlugins =
                toStream(plugins).map(PluginManager::toPluginPath)
                                 .filter(path -> isPlugin(metaRepo, revision, path))
                                 .map(path -> loadPlugin(revision, path)).collect(Collectors.toList());

        logger.info("Loaded {} plugin(s) for project: {}", loadedPlugins.size(), project.name());

        return loadedPlugins.toArray(new Plugin[loadedPlugins.size()]);
    }

    private static Stream<JsonNode> toStream(JsonNode plugins) {
        return StreamSupport.stream(((Iterable<JsonNode>) plugins::elements).spliterator(), false);
    }

    private static String toPluginPath(JsonNode pluginEntry) {
        String path = pluginEntry.textValue();
        // Convert to an absolute path if necessary.
        // It should be enough prepending '/' because the 'plugins.json' is at the root directory.
        if (path.charAt(0) != '/') {
            return '/' + path;
        } else {
            return path;
        }
    }

    private static boolean isPlugin(Repository metaRepo, Revision revision, String path) {
        if (!path.endsWith(".js")) {
            logger.warn("{} does not have '.js' extension; ignoring.", path);
            return false;
        }
        final Entry<?> e = metaRepo.getOrElse(revision, path, null).join();
        if (e == null) {
            logger.warn("{} does not exist; ignoring.", path);
            return false;
        }
        if (e.type() != EntryType.TEXT) {
            logger.warn("{} is not a text file; ignoring.", path);
            return false;
        }

        return true;
    }

    private Plugin loadPlugin(Revision revision, String path) {
        try {
            return new Plugin(project, revision, path);
        } catch (Exception e) {
            throw new PluginException(
                    "failed to load plugin '" + path + "' (revision: " + revision.text() + "): " + e, e);
        }
    }

    public Object invoke(String funcName, Object... args) throws InterruptedException {
        requireNonNull(funcName, "funcName");
        for (Plugin p : plugins) {
            if (p.hasFunction(funcName)) {
                return p.invoke(funcName, args);
            }
        }

        throw new PluginException("unknown function: " + funcName);
    }
}
