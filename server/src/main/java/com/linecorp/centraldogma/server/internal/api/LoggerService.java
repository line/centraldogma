/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.api;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresSystemAdministrator;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

@RequiresSystemAdministrator
@ProducesJson
public class LoggerService {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(LoggerService.class);

    private static final List<String> LEVELS = ImmutableList.of(
            "ALL", "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    @Put("/loggers/{logger}")
    @ConsumesJson
    public HttpResponse setLogLevel(@Param("logger") String loggerName, JsonNode jsonNode) {
        final Logger logger = loggerContext.exists(loggerName);
        if (logger == null) {
            return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.PLAIN_TEXT_UTF_8,
                                   "Logger not found: " + loggerName);
        }
        final JsonNode levelNode = jsonNode.get("level");
        if (levelNode == null) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                   "Missing 'level' field in request body.");
        }

        if (levelNode.isNull()) {
            logger.setLevel(null);
            LoggerService.logger.info("Set log level of '{}' to null. effectiveLevel='{}'",
                                      loggerName, logger.getEffectiveLevel());
            return HttpResponse.ofJson(LoggerInfo.of(logger));
        }

        if (!levelNode.isTextual()) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                   "'level' field must be a string. Found: " + levelNode.getNodeType());
        }

        final String level = levelNode.asText();
        final String upperCase = level.toUpperCase();
        if (!LEVELS.contains(upperCase)) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                   "Invalid log level: " + level + ". Valid levels are: " + LEVELS);
        }
        logger.setLevel(Level.toLevel(upperCase));
        LoggerService.logger.info("Set log level of '{}' to '{}'.", loggerName, upperCase);
        return HttpResponse.ofJson(LoggerInfo.of(logger));
    }

    @Get("/loggers/{logger}")
    public HttpResponse getLogLevel(@Param("logger") String loggerName) {
        final Logger logger = loggerContext.exists(loggerName);
        if (logger == null) {
            return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.PLAIN_TEXT_UTF_8,
                                   "Logger not found: " + loggerName);
        }
        return HttpResponse.ofJson(LoggerInfo.of(logger));
    }

    @Get("/loggers")
    @ProducesJson
    public HttpResponse getLogLevels() {
        final Builder<LoggerInfo> builder = ImmutableList.builder();
        for (Logger logger : loggerContext.getLoggerList()) {
            builder.add(LoggerInfo.of(logger));
        }
        return HttpResponse.ofJson(builder.build());
    }

    private static final class LoggerInfo {

        static LoggerInfo of(Logger logger) {
            return new LoggerInfo(
                    logger.getName(),
                    logger.getLevel() != null ? logger.getLevel().toString() : null,
                    logger.getEffectiveLevel().toString());
        }

        private final String name;
        @Nullable
        private final String level;
        private final String effectiveLevel;

        LoggerInfo(String name, @Nullable String level, String effectiveLevel) {
            this.name = name;
            this.level = level;
            this.effectiveLevel = effectiveLevel;
        }

        @JsonProperty("name")
        String name() {
            return name;
        }

        @Nullable
        @JsonProperty("level")
        String level() {
            return level;
        }

        @JsonProperty("effectiveLevel")
        String effectiveLevel() {
            return effectiveLevel;
        }
    }
}
