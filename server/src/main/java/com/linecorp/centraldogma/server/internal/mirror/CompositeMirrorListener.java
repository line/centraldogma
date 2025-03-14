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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorAccessController;
import com.linecorp.centraldogma.server.mirror.MirrorListener;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorTask;

final class CompositeMirrorListener implements MirrorListener {

    private static final Logger logger = LoggerFactory.getLogger(CompositeMirrorListener.class);

    private final List<MirrorListener> delegates;

    CompositeMirrorListener(List<MirrorListener> delegates) {
        this.delegates = delegates;
    }

    @Override
    public void onCreate(Mirror mirror, User creator, MirrorAccessController accessController) {
        for (MirrorListener delegate : delegates) {
            try {
                delegate.onCreate(mirror, creator, accessController);
            } catch (Exception e) {
                logger.warn("Failed to notify a listener of the mirror create event: {}", delegate, e);
            }
        }
    }

    @Override
    public void onUpdate(Mirror mirror, User updater, MirrorAccessController accessController) {
        for (MirrorListener delegate : delegates) {
            try {
                delegate.onUpdate(mirror, updater, accessController);
            } catch (Exception e) {
                logger.warn("Failed to notify a listener of the mirror update event: {}", delegate, e);
            }
        }
    }

    @Override
    public void onDisallowed(Mirror mirror) {
        for (MirrorListener delegate : delegates) {
            try {
                delegate.onDisallowed(mirror);
            } catch (Exception e) {
                logger.warn("Failed to notify a listener of the mirror disallowed event: {}", delegate, e);
            }
        }
    }

    @Override
    public void onStart(MirrorTask mirrorTask) {
        for (MirrorListener delegate : delegates) {
            try {
                delegate.onStart(mirrorTask);
            } catch (Exception e) {
                logger.warn("Failed to notify a listener of the mirror start event: {}", delegate, e);
            }
        }
    }

    @Override
    public void onComplete(MirrorTask mirrorTask, MirrorResult result) {
        for (MirrorListener delegate : delegates) {
            try {
                delegate.onComplete(mirrorTask, result);
            } catch (Exception e) {
                logger.warn("Failed to notify a listener of the mirror complete event: {}", delegate, e);
            }
        }
    }

    @Override
    public void onError(MirrorTask mirrorTask, Throwable cause) {
        for (MirrorListener delegate : delegates) {
            try {
                delegate.onError(mirrorTask, cause);
            } catch (Exception e) {
                logger.warn("Failed to notify a listener of the mirror error event: {}", delegate, e);
            }
        }
    }
}
