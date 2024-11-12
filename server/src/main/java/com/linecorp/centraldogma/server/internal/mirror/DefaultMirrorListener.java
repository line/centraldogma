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

package com.linecorp.centraldogma.server.internal.mirror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.server.mirror.MirrorListener;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorTask;

enum DefaultMirrorListener implements MirrorListener {

    INSTANCE;

    private static final Logger logger = LoggerFactory.getLogger(DefaultMirrorListener.class);

    @Override
    public void onStart(MirrorTask mirrorTask) {
        if (mirrorTask.scheduled()) {
            logger.info("Mirroring: {}", mirrorTask);
        }
    }

    @Override
    public void onComplete(MirrorTask mirrorTask, MirrorResult result) {
        // Do nothing
    }

    @Override
    public void onError(MirrorTask mirrorTask, Throwable cause) {
        if (mirrorTask.scheduled()) {
            logger.warn("Unexpected exception while mirroring: {}", mirrorTask, cause);
        }
    }
}
