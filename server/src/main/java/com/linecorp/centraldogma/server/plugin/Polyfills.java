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

package com.linecorp.centraldogma.server.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import jdk.nashorn.api.scripting.ScriptObjectMirror;

/**
 * Internal use only. Must be public so that Nashorn can access this class.
 */
public final class Polyfills {

    private static final Pattern FIRST_LINE = Pattern.compile("^[^\\r\\n]*");
    private static final Pattern SUBSTITUTIONS = Pattern.compile("%[%sdifjo]");
    private static final String[] INT2STR = new String[256];

    static {
        for (int i = 0; i < INT2STR.length; i++) {
            INT2STR[i] = String.valueOf(i);
        }
    }

    public static Throwable exception(Object err) {
        if (err instanceof ScriptObjectMirror) {
            final ScriptObjectMirror mirror = (ScriptObjectMirror) err;
            Object exception = mirror.get("nashornException");
            if (exception instanceof Throwable) {
                return new PluginException((Throwable) exception);
            }
        }

        return new PluginException(String.valueOf(err));
    }

    public static String loadResource(String path) throws IOException {
        try (InputStream in = Polyfills.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }

            final byte[] buf = new byte[8192];
            final ByteArrayOutputStream out = new ByteArrayOutputStream(buf.length);
            for (;;) {
                final int readBytes = in.read(buf);
                if (readBytes < 0) {
                    break;
                }
                out.write(buf, 0, readBytes);
            }

            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    public static Future<?> setImmediate(EventExecutor executor, Runnable task) {
        return executor.submit(task);
    }

    public static Future<?> setTimeout(EventExecutor executor, Runnable task, long delay) {
        if (delay <= 0) {
            return executor.submit(task);
        }

        return executor.schedule(task, delay, TimeUnit.MILLISECONDS);
    }

    public static void consoleTrace(Logger logger, String trace) {
        if (logger.isDebugEnabled()) {
            logger.debug(FIRST_LINE.matcher(trace).replaceAll("Stack trace:"));
        }
    }

    public static void consoleDebug(Logger logger, ScriptObjectMirror args) {
        if (logger.isDebugEnabled()) {
            logger.debug(logMessage(args));
        }
    }

    public static void consoleInfo(Logger logger, ScriptObjectMirror args) {
        if (logger.isInfoEnabled()) {
            logger.info(logMessage(args));
        }
    }

    public static void consoleWarn(Logger logger, ScriptObjectMirror args) {
        if (logger.isWarnEnabled()) {
            logger.warn(logMessage(args));
        }
    }

    public static void consoleError(Logger logger, ScriptObjectMirror args) {
        if (logger.isErrorEnabled()) {
            logger.error(logMessage(args));
        }
    }

    private static String logMessage(Object value) {
        final int length = length(value);
        if (length < 0) {
            return String.valueOf(value);
        }

        if (length == 1) {
            @SuppressWarnings("unchecked")
            final Object first = ((Map<String, Object>) value).get("0");
            if (first instanceof ScriptObjectMirror) {
                final ScriptObjectMirror firstMirror = (ScriptObjectMirror) first;
                if (firstMirror.isArray()) {
                    return logMessage(firstMirror, length(firstMirror));
                }
            }

            return String.valueOf(first);
        } else {
            return logMessage((ScriptObjectMirror) value, length);
        }
    }

    private static String logMessage(ScriptObjectMirror mirror, int length) {
        final Object first = mirror.get("0");
        if (first instanceof String) {
            final String format = (String) first;
            if (SUBSTITUTIONS.matcher(format).find()) {
                // The first element is a string and it contains substitution(s).
                return logMessageFormat(format, mirror, length);
            }
        }

        // The first element does not contain substitution(s).
        return logMessageSimple(new StringBuilder(64), mirror, 0, length);
    }

    private static String logMessageFormat(String format, ScriptObjectMirror values, int length) {
        final StringBuilder buf = new StringBuilder(format.length() << 1);
        boolean wasPercent = false; // if the previous character was '%';
        int argIdx = 1;
        for (int i = 0; i < format.length(); i++) {
            final char ch = format.charAt(i);
            if (wasPercent) {
                switch (ch) {
                case '%':
                    buf.append('%');
                    break;
                case 'd':
                case 'f':
                case 'i':
                case 'j':
                case 'o':
                case 's':
                    buf.append(values.get(int2str(argIdx++)));
                    break;
                default:
                    buf.append('%');
                    buf.append(ch);
                }

                wasPercent = false;
            } else if (ch == '%') {
                wasPercent = true;
            } else {
                buf.append(ch);
            }
        }

        return logMessageSimple(buf, values, argIdx, length);
    }

    private static String logMessageSimple(StringBuilder out, ScriptObjectMirror values, int start, int end) {
        for (int i = start; i < end; i++) {
            if (out.length() != 0) {
                out.append(' ');
            }
            out.append(values.get(int2str(i)));
        }

        return out.toString();
    }

    private static int length(Object value) {
        if (!(value instanceof ScriptObjectMirror)) {
            return -1;
        }

        final ScriptObjectMirror mirror = (ScriptObjectMirror) value;
        final Number length = (Number) mirror.get("length");
        if (length == null) {
            return -1;
        }

        return length.intValue();
    }

    private static String int2str(int value) {
        if ((value & 0xFFFFFF00) == 0) {
            return INT2STR[value];
        } else {
            return Integer.toString(value);
        }
    }

    private Polyfills() {}
}
