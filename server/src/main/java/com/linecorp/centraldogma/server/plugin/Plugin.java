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

import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.project.Project;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import jdk.nashorn.api.scripting.JSObject;
import jdk.nashorn.api.scripting.ScriptObjectMirror;

final class Plugin {

    // TODO(trustin): Use more than one (thread + engine) pair if this turns out to be a bottleneck.
    private static final EventExecutor jsEventLoop =
            new DefaultEventExecutor(new DefaultThreadFactory("plugin-event-loop", true));

    private static final String SCRIPT_UNSAFE = readScriptResource("unsafe.js");
    private static final String SCRIPT_POLYFILL_CONSOLE = readScriptResource("polyfill-console.js");
    private static final String SCRIPT_POLYFILL_TIMEOUT = readScriptResource("polyfill-timeout.js");
    private static final String SCRIPT_REQUIRE = readScriptResource("require-2.1.22.js");
    private static final String SCRIPT_REQUIRE_OVERRIDES = readScriptResource("require-overrides.js");
    private static final String SCRIPT_STARTUP = readScriptResource("startup.js");

    private static final String LOGGER_NAME_PREFIX = "scripts.";

    private static String readScriptResource(String filename) {
        try (InputStream in = Plugin.class.getResourceAsStream(filename)) {
            return readScriptResource(filename, in);
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    private static String readScriptResource(String filename, InputStream in) throws IOException {
        final byte[] buf = new byte[8192];
        final ByteArrayOutputStream out = new ByteArrayOutputStream(buf.length);
        for (;;) {
            final int readBytes = in.read(buf);
            if (readBytes < 0) {
                break;
            }
            out.write(buf, 0, readBytes);
        }

        return "load({" +
               "  name: '" + filename + "'," +
               "  script: \"" + escapeJavaScript(out.toString(StandardCharsets.UTF_8.name())) + '\"' +
               "});";
    }

    /**
     * Adopted from StringEscapeUtils in Apache Commons Lang 2.6.
     */
    private static String escapeJavaScript(String script) {
        final StringBuilder buf = new StringBuilder(script.length() * 3 / 2);
        final int length = script.length();
        for (int i = 0; i < length; i++) {
            char ch = script.charAt(i);

            // handle unicode
            if (ch > 0xfff) {
                buf.append("\\u");
                buf.append(Integer.toHexString(ch));
            } else if (ch > 0xff) {
                buf.append("\\u0");
                buf.append(Integer.toHexString(ch));
            } else if (ch > 0x7f) {
                buf.append("\\u00");
                buf.append(Integer.toHexString(ch));
            } else if (ch < 32) {
                switch (ch) {
                case '\b':
                    buf.append('\\');
                    buf.append('b');
                    break;
                case '\n':
                    buf.append('\\');
                    buf.append('n');
                    break;
                case '\t':
                    buf.append('\\');
                    buf.append('t');
                    break;
                case '\f':
                    buf.append('\\');
                    buf.append('f');
                    break;
                case '\r':
                    buf.append('\\');
                    buf.append('r');
                    break;
                default:
                    if (ch > 0xf) {
                        buf.append("\\u00");
                        buf.append(Integer.toHexString(ch));
                    } else {
                        buf.append("\\u000");
                        buf.append(Integer.toHexString(ch));
                    }
                    break;
                }
            } else {
                switch (ch) {
                case '\'':
                    buf.append('\\');
                    buf.append('\'');
                    break;
                case '"':
                    buf.append('\\');
                    buf.append('"');
                    break;
                case '\\':
                    buf.append('\\');
                    buf.append('\\');
                    break;
                case '/':
                    buf.append('\\');
                    buf.append('/');
                    break;
                default:
                    buf.append(ch);
                    break;
                }
            }
        }

        return buf.toString();
    }

    private final ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
    private final ScriptObjectMirror plugin;
    private final ScriptObjectMirror unsafe;

    Plugin(Project project, Revision revision, String path) throws Exception {
        requireNonNull(project, "project");
        requireNonNull(revision, "revision");
        requireNonNull(path, "path");

        // Initialize the __UNSAFE__.
        engine.eval(SCRIPT_UNSAFE);

        // Provide the properties required to the plugin via the __UNSAFE__.
        unsafe = (ScriptObjectMirror) engine.get("__UNSAFE__");
        unsafe.put("pluginRepository", project.metaRepo());
        unsafe.put("pluginRevision", revision);
        unsafe.put("pluginPath", path);
        unsafe.put("eventLoop", jsEventLoop);
        final Logger pluginLogger = LoggerFactory.getLogger(loggerName(path));
        unsafe.put("logger", pluginLogger);

        // Provide the promise to fulfill when plugin has been loaded completely.
        final String pluginInitPromiseKey = "pluginInitPromise";
        final Promise<ScriptObjectMirror> pluginInitPromise = new DefaultPromise<>(jsEventLoop);
        unsafe.put(pluginInitPromiseKey, pluginInitPromise);

        // Run everything in the event loop from now on.
        jsEventLoop.submit(() -> {
            pluginLogger.info("Loading plugin: {} (revision: {})", path, revision.text());

            // Polyfills for Nashorn
            engine.eval(SCRIPT_POLYFILL_CONSOLE);
            engine.eval(SCRIPT_POLYFILL_TIMEOUT);

            // Load require.js
            engine.eval(SCRIPT_REQUIRE);
            engine.eval(SCRIPT_REQUIRE_OVERRIDES);

            // Start the plugin up.
            engine.eval(SCRIPT_STARTUP);

            return null;
        }).syncUninterruptibly();

        // Wait until all require()s are loaded.
        plugin = pluginInitPromise.syncUninterruptibly().getNow();
    }

    private static String loggerName(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return LOGGER_NAME_PREFIX + "__UNKNOWN__";
        }

        final StringBuilder buf = new StringBuilder(filePath.length() + 8).append(LOGGER_NAME_PREFIX);

        // Handle the first character
        char ch = filePath.charAt(0);
        if (Character.isJavaIdentifierStart(ch)) {
            buf.append(ch);
        } else if (ch != '/') {
            buf.append('_');
        }

        // and the remaining characters.
        for (int i = 1; i < filePath.length(); i++) {
            ch = filePath.charAt(i);
            if (Character.isJavaIdentifierPart(ch)) {
                buf.append(ch);
            } else if (ch == '/') {
                buf.append('.');
            } else {
                buf.append('_');
            }
        }

        return buf.toString();
    }

    boolean hasFunction(String funcName) {
        Object member = plugin.get(funcName);
        if (!(member instanceof JSObject)) {
            return false;
        }

        return ((JSObject) member).isFunction();
    }

    Object invoke(String funcName, Object... args) throws InterruptedException {
        final Object[] invokeArgs = new Object[args.length + 2];
        invokeArgs[0] = plugin;
        invokeArgs[1] = funcName;
        for (int i = 2, j = 0; j < args.length; i++, j++) {
            invokeArgs[i] = args[j];
        }

        final Object result;
        try {
            result = jsEventLoop.submit(() -> unsafe.callMember("invoke", invokeArgs)).get();
        } catch (ExecutionException e) {
            throw new PluginException(e.getCause());
        }

        if (!(result instanceof Future)) {
            return result;
        }

        @SuppressWarnings("unchecked")
        final Future<Object> future = (Future<Object>) result;
        return future.sync().getNow();
    }
}
