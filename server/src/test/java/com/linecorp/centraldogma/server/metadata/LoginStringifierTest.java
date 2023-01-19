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
package com.linecorp.centraldogma.server.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.google.common.collect.Maps;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.logback.RequestContextExportingAppender;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class LoginStringifierTest {

    private static final Logger rootLogger =
            (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private static final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    @BeforeEach
    void beforeEach() throws Exception {
        rootLogger.getLoggerContext().getStatusManager().clear();
        MDC.clear();

        final JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        context.reset();
        configurator.doConfigure(getClass().getResource("testLoginStringifier.xml"));
    }

    @AfterEach
    void afterEach() throws Exception {
        final JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        context.reset();

        configurator.doConfigure(getClass().getResource("/logback-test.xml"));
    }

    @Test
    void requestContextExportingAppender() {
        final Logger logger = (Logger) LoggerFactory.getLogger("RCEA");
        final RequestContextExportingAppender rcea =
                (RequestContextExportingAppender) logger.getAppender("RCEA");

        final ListAppender<ILoggingEvent> la = new ListAppender<>();
        rcea.addAppender(la);
        rcea.start();
        la.start();
        final ServiceRequestContext ctx = contextWithToken(sb -> {});
        try (SafeCloseable ignored = ctx.push()) {
            logger.trace("log");
            assertThat(la.list.remove(0).getMDCPropertyMap()).containsExactly(
                    Maps.immutableEntry("attrs.app_id", "foo"));
        }
        la.stop();
        rcea.stop();
    }

    private static ServiceRequestContext contextWithToken(Consumer<? super ServerBuilder> serverConfigurator) {
        final ServiceRequestContext ctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                                               .serverConfigurator(serverConfigurator)
                                                               .build();
        final UserWithToken token =
                new UserWithToken("foo", new Token("bar", "secret", false,
                                                   UserAndTimestamp.of(Author.DEFAULT)));
        AuthUtil.setCurrentUser(ctx, token);
        return ctx;
    }

    @Test
    void accessLog() {
        final AccessLogWriter writer = AccessLogWriter.custom(
                "%{com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil#CURRENT_USER" +
                ":com.linecorp.centraldogma.server.metadata.LoginStringifier}j");

        final org.slf4j.Logger logger = mock(org.slf4j.Logger.class);
        when(logger.isInfoEnabled()).thenReturn(true);
        final ServiceRequestContext ctx = contextWithToken(sb -> sb.accessLogger(logger));
        writer.log(ctx.log().partial());

        verify(logger).info("foo");
    }
}
