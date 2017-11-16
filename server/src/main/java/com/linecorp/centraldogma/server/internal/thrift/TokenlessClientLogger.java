/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.thrift;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

public class TokenlessClientLogger extends SimpleDecoratingService<HttpRequest, HttpResponse> {

    private static final Logger logger = LoggerFactory.getLogger(TokenlessClientLogger.class);

    private static final Pattern PATTERN = Pattern.compile("\\s*[Bb][Ee][Aa][Rr][Ee][Rr]\\s+anonymous\\s*");

    private final Clock clock;
    private final ConcurrentMap<String, Instant> reportedAddresses = new ConcurrentHashMap<>();

    public TokenlessClientLogger(Service<HttpRequest, HttpResponse> delegate) {
        this(delegate, Clock.systemUTC());
    }

    @VisibleForTesting
    TokenlessClientLogger(Service<HttpRequest, HttpResponse> delegate, Clock clock) {
        super(delegate);
        this.clock = requireNonNull(clock, "clock");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final String authorization = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (authorization == null || !PATTERN.matcher(authorization).matches()) {
            final InetSocketAddress raddr = ctx.remoteAddress();
            final String ip = raddr.getAddress().getHostAddress();
            final Instant now = Instant.now(clock);
            final Instant lastReport = reportedAddresses.putIfAbsent(ip, now);
            final boolean report;
            if (lastReport == null) {
                report = true;
            } else if (ChronoUnit.DAYS.between(lastReport, now) >= 1) {
                report = reportedAddresses.replace(ip, lastReport, now);
            } else {
                report = false;
            }

            if (report) {
                report(raddr.getHostString(), ip);
            }
        }

        return delegate().serve(ctx, req);
    }

    @VisibleForTesting
    void report(String hostname, String ip) {
        logger.debug("Received a request without 'authorization' header from: {}/{}", hostname, ip);
    }
}
