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
package com.linecorp.centraldogma.server.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.util.FS;

public final class EmptyGitConfig extends FileBasedConfig {

    private static final String[] EMPTY_STRING_ARRAY = {};

    public static final FileBasedConfig INSTANCE = new EmptyGitConfig();

    private EmptyGitConfig() {
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
