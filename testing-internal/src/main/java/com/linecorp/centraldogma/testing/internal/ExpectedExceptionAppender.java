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

package com.linecorp.centraldogma.testing.internal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.slf4j.Marker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

/**
 * A <a href="https://logback.qos.ch/">Logback</a> {@link Appender} that adds {@code "(expected exception)"}
 * to the log messages of expected exceptions and lower their logging level to {@link Level#DEBUG}
 * if it's higher than that.
 */
public class ExpectedExceptionAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
        implements AppenderAttachable<ILoggingEvent> {

    private static final String EXPECTED_EXCEPTION = "(expected exception)";

    private static final ConcurrentMap<String, String> exceptionAndMessage = new ConcurrentHashMap<>();

    static {
        if (InternalLoggerFactory.getDefaultFactory() == null) {
            // Can happen due to initialization order.
            InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
        }
    }

    private final AppenderAttachableImpl<ILoggingEvent> aai = new AppenderAttachableImpl<>();

    /**
     * Adds {@code "(expected exception)"} to the log message and lower its logging level to
     * {@link Level#DEBUG} if the exception occurred from the {@code shouldRaiseThrowable} matches
     * {@code throwable} and contains {@code message}.
     */
    public static AbstractThrowableAssert<?, ? extends Throwable> assertThatThrownByWithExpectedException(
            Class<?> throwable, String message, ThrowingCallable shouldRaiseThrowable) throws Exception {
        requireNonNull(throwable, "throwable");
        requireNonNull(message, "message");
        requireNonNull(shouldRaiseThrowable, "shouldRaiseThrowable");
        exceptionAndMessage.put(throwable.getName(), message);
        try {
            return assertThatThrownBy(shouldRaiseThrowable);
        } finally {
            exceptionAndMessage.remove(throwable.getName());
        }
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        final IThrowableProxy exception = getException(eventObject);
        for (String exceptionName : exceptionAndMessage.keySet()) {
            if (exception != null && exception.getClassName().endsWith(exceptionName) &&
                containsMessage(exception, exceptionName)) {
                aai.appendLoopOnAppenders(new ExpectedExceptionEventWrapper(eventObject));
                return;
            }
        }
        aai.appendLoopOnAppenders(eventObject);
    }

    private IThrowableProxy getException(ILoggingEvent event) {
        final IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            final IThrowableProxy cause = throwableProxy.getCause();
            if (cause != null) {
                return cause;
            }
        }
        return null;
    }

    private boolean containsMessage(IThrowableProxy exception, String exceptionName) {
        final String message = exception.getMessage();
        if (message != null && message.contains(exceptionAndMessage.get(exceptionName))) {
            return true;
        }
        return false;
    }

    @Override
    public void start() {
        if (!aai.iteratorForAppenders().hasNext()) {
            addWarn("No appender was attached to " + getClass().getSimpleName() + '.');
        }
        super.start();
    }

    @Override
    public void stop() {
        try {
            aai.detachAndStopAllAppenders();
        } finally {
            super.stop();
        }
    }

    @Override
    public void addAppender(Appender<ILoggingEvent> newAppender) {
        aai.addAppender(newAppender);
    }

    @Override
    public Iterator<Appender<ILoggingEvent>> iteratorForAppenders() {
        return aai.iteratorForAppenders();
    }

    @Override
    public Appender<ILoggingEvent> getAppender(String name) {
        return aai.getAppender(name);
    }

    @Override
    public boolean isAttached(Appender<ILoggingEvent> appender) {
        return aai.isAttached(appender);
    }

    @Override
    public void detachAndStopAllAppenders() {
        aai.detachAndStopAllAppenders();
    }

    @Override
    public boolean detachAppender(Appender<ILoggingEvent> appender) {
        return aai.detachAppender(appender);
    }

    @Override
    public boolean detachAppender(String name) {
        return aai.detachAppender(name);
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class ExpectedExceptionEventWrapper implements ILoggingEvent {
        private final ILoggingEvent event;

        ExpectedExceptionEventWrapper(ILoggingEvent event) {
            this.event = event;
        }

        @Override
        public Object[] getArgumentArray() {
            return event.getArgumentArray();
        }

        @Override
        public Level getLevel() {
            if (event.getLevel().levelInt <= Level.DEBUG_INT) {
                return event.getLevel();
            }
            return Level.DEBUG;
        }

        @Override
        public String getLoggerName() {
            return event.getLoggerName();
        }

        @Override
        public String getThreadName() {
            return event.getThreadName();
        }

        @Override
        public IThrowableProxy getThrowableProxy() {
            return event.getThrowableProxy();
        }

        @Override
        public void prepareForDeferredProcessing() {
            event.prepareForDeferredProcessing();
        }

        @Override
        public LoggerContextVO getLoggerContextVO() {
            return event.getLoggerContextVO();
        }

        @Override
        public String getMessage() {
            return EXPECTED_EXCEPTION + event.getMessage();
        }

        @Override
        public long getTimeStamp() {
            return event.getTimeStamp();
        }

        @Override
        public StackTraceElement[] getCallerData() {
            return event.getCallerData();
        }

        @Override
        public boolean hasCallerData() {
            return event.hasCallerData();
        }

        @Override
        public Marker getMarker() {
            return event.getMarker();
        }

        @Override
        public String getFormattedMessage() {
            return EXPECTED_EXCEPTION + event.getFormattedMessage();
        }

        @Override
        public Map<String, String> getMDCPropertyMap() {
            return event.getMDCPropertyMap();
        }

        @Override
        public Map<String, String> getMdc() {
            return event.getMdc();
        }

        @Override
        public String toString() {
            return event.toString();
        }
    }
}
