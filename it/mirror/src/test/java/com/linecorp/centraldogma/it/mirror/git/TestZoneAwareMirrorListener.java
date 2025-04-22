/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.centraldogma.it.mirror.git;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorAccessController;
import com.linecorp.centraldogma.server.mirror.MirrorListener;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorTask;

public class TestZoneAwareMirrorListener implements MirrorListener {

    private static final Logger logger = LoggerFactory.getLogger(TestZoneAwareMirrorListener.class);

    static final Map<String, Integer> startCount = new ConcurrentHashMap<>();
    static final Map<String, List<MirrorResult>> completions = new ConcurrentHashMap<>();
    static final Map<String, List<Throwable>> errors = new ConcurrentHashMap<>();

    static void reset() {
        startCount.clear();
        completions.clear();
        errors.clear();
    }

    private static String key(MirrorTask task) {
        return firstNonNull(task.currentZone(), "default");
    }

    @Override
    public void onCreate(Mirror mirror, User creator, MirrorAccessController accessController) {}

    @Override
    public void onUpdate(Mirror mirror, User updater, MirrorAccessController accessController) {}

    @Override
    public void onDisallowed(Mirror mirror) {}

    @Override
    public void onStart(MirrorTask mirror) {
        logger.debug("onStart: {}", mirror);
        startCount.merge(key(mirror), 1, Integer::sum);
    }

    @Override
    public void onComplete(MirrorTask mirror, MirrorResult result) {
        logger.debug("onComplete: {} -> {}", mirror, result);
        final List<MirrorResult> results = new CopyOnWriteArrayList<>();
        results.add(result);
        completions.merge(key(mirror), results, (oldValue, newValue) -> {
            oldValue.addAll(newValue);
            return oldValue;
        });
    }

    @Override
    public void onError(MirrorTask mirror, Throwable cause) {
        logger.debug("onError: {}", mirror, cause);
        final List<Throwable> exceptions = new CopyOnWriteArrayList<>();
        exceptions.add(cause);
        errors.merge(key(mirror), exceptions, (oldValue, newValue) -> {
            oldValue.addAll(newValue);
            return oldValue;
        });
    }
}
