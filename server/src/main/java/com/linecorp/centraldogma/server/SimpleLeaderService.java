/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.centraldogma.server;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.server.pluggable.LeaderService;

public class SimpleLeaderService implements LeaderService {

    private static final Logger logger = LoggerFactory.getLogger(SimpleLeaderService.class);

    @Override
    public CompletionStage<Void> onTakeLeadership() {
        logger.info("Invoked onTakeLeadership");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> onReleaseLeadership() {
        logger.info("Invoked onReleaseLeadership");
        return CompletableFuture.completedFuture(null);
    }
}
