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

import static java.util.Objects.requireNonNull;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_COMMIT_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_CORE_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_DIFF_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_GC_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_ALGORITHM;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_AUTO;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_AUTOGC;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_AUTOPACKLIMIT;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_FILEMODE;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_GPGSIGN;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_HIDEDOTFILES;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_LOGALLREFUPDATES;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_PRECOMPOSEUNICODE;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_RENAMES;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_REPO_FORMAT_VERSION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_SYMLINKS;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_RECEIVE_SECTION;

import java.io.IOException;
import java.util.Objects;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.StoredConfig;

public final class JGitUtil {

    public static final int REPO_FORMAT_VERSION = 1;

    private static final String REPO_FORMAT_VERSION_STR = String.valueOf(REPO_FORMAT_VERSION);

    public static void applyDefaultsAndSave(StoredConfig config) throws IOException {
        if (applyDefaults(config)) {
            config.save();
        }
    }

    public static boolean applyDefaults(Config config) {
        boolean updated = false;
        // Update the repository settings to upgrade to format version 1 and reftree.
        updated |= set(config, CONFIG_CORE_SECTION, CONFIG_KEY_REPO_FORMAT_VERSION, REPO_FORMAT_VERSION_STR);

        // Disable hidden files, symlinks and file modes we do not use.
        updated |= set(config, CONFIG_CORE_SECTION, CONFIG_KEY_HIDEDOTFILES, "false");
        updated |= set(config, CONFIG_CORE_SECTION, CONFIG_KEY_SYMLINKS, "false");
        updated |= set(config, CONFIG_CORE_SECTION, CONFIG_KEY_FILEMODE, "false");

        // Don't log ref updates.
        updated |= set(config, CONFIG_CORE_SECTION, CONFIG_KEY_LOGALLREFUPDATES, "false");

        // Do not decompose file names in macOS.
        updated |= set(config, CONFIG_CORE_SECTION, CONFIG_KEY_PRECOMPOSEUNICODE, "true");

        // Disable GPG signing.
        updated |= set(config, CONFIG_COMMIT_SECTION, CONFIG_KEY_GPGSIGN, "false");

        // Set the diff algorithm.
        updated |= set(config, CONFIG_DIFF_SECTION, CONFIG_KEY_ALGORITHM, "histogram");

        // Disable rename detection which we do not use.
        updated |= set(config, CONFIG_DIFF_SECTION, CONFIG_KEY_RENAMES, "false");

        // Disable auto-GC by default because of the following reasons:
        // - GC can take long time affecting performance.
        // - jGit's GC task has memory leak which adds a JVM shutdown hook for each GC task.
        //   See https://github.com/eclipse-mirrors/org.eclipse.jgit/blob/2e3f12a0fc63deba80e725bfa985c0c5bd31de99/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/file/GC.java#L1769
        updated |= set(config, CONFIG_GC_SECTION, CONFIG_KEY_AUTO, "0");
        updated |= set(config, CONFIG_GC_SECTION, CONFIG_KEY_AUTOPACKLIMIT, "0");
        updated |= set(config, CONFIG_RECEIVE_SECTION, CONFIG_KEY_AUTOGC, "false");

        return updated;
    }

    private static boolean set(Config config, String section, String name, String value) {
        requireNonNull(section, "section");
        requireNonNull(name, "name");
        requireNonNull(value, "value");

        final String oldValue = config.getString(section, null, name);
        if (Objects.equals(oldValue, value)) {
            return false;
        }

        config.setString(section, null, name, value);
        return true;
    }

    private JGitUtil() {}
}
