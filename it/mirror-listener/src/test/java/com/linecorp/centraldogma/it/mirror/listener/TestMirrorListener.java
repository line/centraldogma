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

package com.linecorp.centraldogma.it.mirror.listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorListener;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorTask;

public final class TestMirrorListener implements MirrorListener {

    static final Map<Mirror, Integer> startCount = new ConcurrentHashMap<>();
    static final Map<Mirror, List<MirrorResult>> completions = new ConcurrentHashMap<>();
    static final Map<Mirror, List<Throwable>> errors = new ConcurrentHashMap<>();

    static void reset() {
        startCount.clear();
        completions.clear();
        errors.clear();
    }

    @Override
    public void onStart(MirrorTask mirror) {
        startCount.merge(mirror.mirror(), 1, Integer::sum);
    }

    @Override
    public void onComplete(MirrorTask mirror, MirrorResult result) {
        final List<MirrorResult> results = new ArrayList<>();
        results.add(result);
        completions.merge(mirror.mirror(), results, (oldValue, newValue) -> {
            oldValue.addAll(newValue);
            return oldValue;
        });
    }

    @Override
    public void onError(MirrorTask mirror, Throwable cause) {
        final List<Throwable> exceptions = new ArrayList<>();
        exceptions.add(cause);
        errors.merge(mirror.mirror(), exceptions, (oldValue, newValue) -> {
            oldValue.addAll(newValue);
            return oldValue;
        });
    }
}
