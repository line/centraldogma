/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.centraldogma.server.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

import com.linecorp.armeria.common.util.SystemInfo;

/**
 * A JGit {@link SystemReader} that prevents from reading system-wide or user-specific configurations that might
 * interfere with Central Dogma.
 */
public final class IsolatedSystemReader extends SystemReader {

    private static final Pattern allowedSystemPropertyNamePattern = Pattern.compile(
            "^(?:java|os|file|path|line|user|native|jdk)\\.");

    private static final SystemReader INSTANCE = new IsolatedSystemReader();
    private static final FileBasedConfig EMPTY_CONFIG = new EmptyConfig();
    private static final String[] EMPTY_STRING_ARRAY = {};

    public static void install() {
        SystemReader.setInstance(INSTANCE);
    }

    private IsolatedSystemReader() {
    }

    @Override
    public String getHostname() {
        return SystemInfo.hostname();
    }

    @Nullable
    @Override
    public String getenv(String variable) {
        return null;
    }

    @Nullable
    @Override
    public String getProperty(String key) {
        if (allowedSystemPropertyNamePattern.matcher(key).find()) {
            return System.getProperty(key);
        }

        // Don't read the system properties those specified in System.getProperties().
        return null;
    }

    @Override
    public FileBasedConfig openUserConfig(Config parent, FS fs) {
        return EMPTY_CONFIG;
    }

    @Override
    public FileBasedConfig openSystemConfig(Config parent, FS fs) {
        return EMPTY_CONFIG;
    }

    @Override
    public FileBasedConfig openJGitConfig(Config parent, FS fs) {
        return EMPTY_CONFIG;
    }

    @Override
    public long getCurrentTime() {
        return System.currentTimeMillis();
    }

    @Override
    public int getTimezone(long when) {
        return getTimeZone().getOffset(when) / (60 * 1000);
    }

    private static final class EmptyConfig extends FileBasedConfig {
        EmptyConfig() {
            super(null, null, null);
        }

        @Override
        public void load() {
            // Do nothing because we don't want to load anything from external sources.
        }

        @Override
        public void save() throws IOException {
            // Do nothing.
        }

        @Override
        public boolean isOutdated() {
            return false;
        }

        @Override
        public int getInt(String section, String name, int defaultValue) {
            return defaultValue;
        }

        @Override
        public int getInt(String section, String subsection, String name, int defaultValue) {
            return defaultValue;
        }

        @Override
        public long getLong(String section, String name, long defaultValue) {
            return defaultValue;
        }

        @Override
        public long getLong(String section, String subsection, String name, long defaultValue) {
            return defaultValue;
        }

        @Override
        public boolean getBoolean(String section, String name, boolean defaultValue) {
            return defaultValue;
        }

        @Override
        public boolean getBoolean(String section, String subsection, String name, boolean defaultValue) {
            return defaultValue;
        }

        @Override
        public <T extends Enum<?>> T getEnum(String section, String subsection, String name, T defaultValue) {
            return defaultValue;
        }

        @Override
        public <T extends Enum<?>> T getEnum(T[] all, String section, String subsection, String name,
                                             T defaultValue) {
            return defaultValue;
        }

        @Override
        @Nullable
        public String getString(String section, String subsection, String name) {
            return null;
        }

        @Override
        public String[] getStringList(String section, String subsection, String name) {
            return EMPTY_STRING_ARRAY;
        }

        @Override
        public long getTimeUnit(String section, String subsection, String name, long defaultValue,
                                TimeUnit wantUnit) {
            return defaultValue;
        }

        @Override
        public Path getPath(String section, String subsection, String name, FS fs, File resolveAgainst,
                            Path defaultValue) {
            return defaultValue;
        }

        @Override
        public List<RefSpec> getRefSpecs(String section, String subsection, String name) {
            // We return a mutable list to match the original behavior.
            return new ArrayList<>();
        }

        @Override
        public Set<String> getSubsections(String section) {
            return Collections.emptySet();
        }

        @Override
        public Set<String> getSections() {
            return Collections.emptySet();
        }

        @Override
        public Set<String> getNames(String section) {
            return Collections.emptySet();
        }

        @Override
        public Set<String> getNames(String section, String subsection) {
            return Collections.emptySet();
        }

        @Override
        public Set<String> getNames(String section, boolean recursive) {
            return Collections.emptySet();
        }

        @Override
        public Set<String> getNames(String section, String subsection, boolean recursive) {
            return Collections.emptySet();
        }

        @Override
        public <T> T get(SectionParser<T> parser) {
            return parser.parse(this);
        }

        @Override
        public String toText() {
            return "";
        }

        @Override
        public String toString() {
            // super.toString() triggers a NullPointerException because it assumes getFile() returns non-null,
            // which is not the case for us.
            return getClass().getSimpleName();
        }
    }
}
