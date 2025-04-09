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

import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
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
        return EmptyGitConfig.INSTANCE;
    }

    @Override
    public FileBasedConfig openSystemConfig(Config parent, FS fs) {
        return EmptyGitConfig.INSTANCE;
    }

    @Override
    public FileBasedConfig openJGitConfig(Config parent, FS fs) {
        return EmptyGitConfig.INSTANCE;
    }

    @Override
    public long getCurrentTime() {
        return System.currentTimeMillis();
    }

    @Override
    public int getTimezone(long when) {
        return getTimeZone().getOffset(when) / (60 * 1000);
    }
}
