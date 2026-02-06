/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.xds.internal;

<<<<<<< introduce-jspecify
import org.jspecify.annotations.Nullable;
=======
import javax.annotation.Nullable;
>>>>>>> main

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

final class ControlPlaneMetrics {

    private static final String VERSION_METRIC_NAME = "xds.control.plane.snapshot";

    // 0 = not initialized, 1 = initialized, -1 = stopped
    private volatile int clustersInit;
    private volatile int endpointsInit;
    private volatile int listenersInit;
    private volatile int routesInit;
    private volatile int secretsInit;

    @Nullable
    private String clustersVersion;
    @Nullable
    private String endpointsVersion;
    @Nullable
    private String listenersVersion;
    @Nullable
    private String routesVersion;
    @Nullable
    private String secretsVersion;

    private final Counter clustersUpdateCounter;
    private final Counter endpointsUpdateCounter;
    private final Counter listenersUpdateCounter;
    private final Counter routesUpdateCounter;
    private final Counter secretsUpdateCounter;

    ControlPlaneMetrics(MeterRegistry meterRegistry) {
        Gauge.builder(VERSION_METRIC_NAME + ".initialized", () -> clustersInit)
             .tag("resource", "cluster")
             .register(meterRegistry);
        Gauge.builder(VERSION_METRIC_NAME + ".initialized", () -> endpointsInit)
             .tag("resource", "endpoint")
             .register(meterRegistry);
        Gauge.builder(VERSION_METRIC_NAME + ".initialized", () -> listenersInit)
             .tag("resource", "listener")
             .register(meterRegistry);
        Gauge.builder(VERSION_METRIC_NAME + ".initialized", () -> routesInit)
             .tag("resource", "route")
             .register(meterRegistry);
        Gauge.builder(VERSION_METRIC_NAME + ".initialized", () -> secretsInit)
             .tag("resource", "secret")
             .register(meterRegistry);

        clustersUpdateCounter = Counter.builder(VERSION_METRIC_NAME + ".revision")
                                       .tag("resource", "cluster")
                                       .register(meterRegistry);
        endpointsUpdateCounter = Counter.builder(VERSION_METRIC_NAME + ".revision")
                                        .tag("resource", "endpoint")
                                        .register(meterRegistry);
        listenersUpdateCounter = Counter.builder(VERSION_METRIC_NAME + ".revision")
                                        .tag("resource", "listener")
                                        .register(meterRegistry);
        routesUpdateCounter = Counter.builder(VERSION_METRIC_NAME + ".revision")
                                     .tag("resource", "route")
                                     .register(meterRegistry);
        secretsUpdateCounter = Counter.builder(VERSION_METRIC_NAME + ".revision")
                                      .tag("resource", "secret")
                                      .register(meterRegistry);
    }

    void onSnapshotUpdate(CentralDogmaSnapshot snapshot) {
        final String clustersVersion = snapshot.clustersVersion();
        if (!clustersVersion.equals(this.clustersVersion)) {
            this.clustersVersion = clustersVersion;
            if ("empty_resources".equals(clustersVersion)) {
                clustersInit = 0;
            } else {
                clustersInit = 1;
            }
            clustersUpdateCounter.increment();
        }

        final String endpointsVersion = snapshot.endpointsVersion();
        if (!endpointsVersion.equals(this.endpointsVersion)) {
            this.endpointsVersion = endpointsVersion;
            if ("empty_resources".equals(endpointsVersion)) {
                endpointsInit = 0;
            } else {
                endpointsInit = 1;
            }
            endpointsUpdateCounter.increment();
        }

        final String listenersVersion = snapshot.listenersVersion();
        if (!listenersVersion.equals(this.listenersVersion)) {
            this.listenersVersion = listenersVersion;
            if ("empty_resources".equals(listenersVersion)) {
                listenersInit = 0;
            } else {
                listenersInit = 1;
            }
            listenersUpdateCounter.increment();
        }

        final String routesVersion = snapshot.routesVersion();
        if (!routesVersion.equals(this.routesVersion)) {
            this.routesVersion = routesVersion;
            if ("empty_resources".equals(routesVersion)) {
                routesInit = 0;
            } else {
                routesInit = 1;
            }
            routesUpdateCounter.increment();
        }

        final String secretsVersion = snapshot.secretsVersion();
        if (!secretsVersion.equals(this.secretsVersion)) {
            this.secretsVersion = secretsVersion;
            if ("empty_resources".equals(secretsVersion)) {
                secretsInit = 0;
            } else {
                secretsInit = 1;
            }
            secretsUpdateCounter.increment();
        }
    }

    void onStopped() {
        clustersInit = -1;
        endpointsInit = -1;
        listenersInit = -1;
        routesInit = -1;
        secretsInit = -1;
    }
}
