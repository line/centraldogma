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
package com.linecorp.centraldogma.server;

import java.io.File;
import java.io.IOException;

import org.apache.commons.daemon.Daemon;
import org.apache.commons.daemon.DaemonContext;
import org.apache.commons.daemon.DaemonController;
import org.apache.shiro.config.Ini;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.converters.FileConverter;

/**
 * Entry point of a standalone server. Use {@link CentralDogmaBuilder} to embed a server.
 */
public final class Main implements Daemon {

    enum State {
        NONE,
        INITIALIZED,
        STARTED,
        STOPPED,
        DESTROYED
    }

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final File DEFAULT_DATA_DIR =
            new File(System.getProperty("user.dir", ".") + File.separatorChar + "data");

    private static final File DEFAULT_CONFIG_FILE =
            new File(System.getProperty("user.dir", ".") +
                     File.separatorChar + "conf" +
                     File.separatorChar + "dogma.json");

    private static final File DEFAULT_SECURITY_CONFIG_FILE =
            new File(System.getProperty("user.dir", ".") +
                     File.separatorChar + "conf" +
                     File.separatorChar + "shiro.ini");

    @Parameter(names = "-config", description = "The path to the config file", converter = FileConverter.class)
    private File configFile;

    @Parameter(names = "-securityConfig", description = "The path to the security config file",
            converter = FileConverter.class)
    private File securityConfigFile;

    /**
     * Note that {@link Boolean} was used in lieu of {@code boolean} so that JCommander does not print the
     * default value of this option.
     */
    @Parameter(names = { "-help", "-h" }, description = "Prints the usage", help = true)
    private Boolean help;

    private State state = State.NONE;
    private CentralDogma dogma;

    @Override
    public synchronized void init(DaemonContext context) {
        if (state != State.NONE) {
            throw new IllegalStateException("initialized already");
        }

        final JCommander commander = new JCommander(this);
        commander.setProgramName(getClass().getName());
        commander.parse(context.getArguments());

        if (help != null && help) {
            commander.usage();
            final DaemonController controller = context.getController();
            if (controller != null) {
                controller.fail();
            }
            return;
        }

        state = State.INITIALIZED;
    }

    @Override
    public synchronized void start() throws IOException {
        switch (state) {
        case NONE:
            throw new IllegalStateException("not initialized");
        case STARTED:
            throw new IllegalStateException("started already");
        case DESTROYED:
            throw new IllegalStateException("can't start after destruction");
        default:
            break;
        }

        final File configFile = findConfigFile(this.configFile, DEFAULT_CONFIG_FILE);
        final File securityConfigFile = findConfigFile(this.securityConfigFile, DEFAULT_SECURITY_CONFIG_FILE);
        final Ini securityConfig =
                securityConfigFile != null ? Ini.fromResourcePath(securityConfigFile.getPath()) : null;

        final CentralDogma dogma;
        if (configFile == null) {
            dogma = new CentralDogmaBuilder(DEFAULT_DATA_DIR).build();
        } else {
            dogma = CentralDogma.forConfig(configFile, securityConfig);
        }

        dogma.start();

        this.dogma = dogma;
        state = State.STARTED;
    }

    private static File findConfigFile(File file, File defaultFile) {
        if (file != null && file.isFile() && file.canRead()) {
            return file;
        }
        // Try to use the default config file if not specified.
        if (defaultFile.isFile() && defaultFile.canRead()) {
            return defaultFile;
        }
        return null;
    }

    @Override
    public synchronized void stop() {
        switch (state) {
        case NONE:
        case INITIALIZED:
        case STOPPED:
            return;
        case DESTROYED:
            throw new IllegalStateException("can't stop after destruction");
        }

        final CentralDogma dogma = this.dogma;

        this.dogma = null;
        dogma.stop();

        state = State.STOPPED;
    }

    @Override
    public void destroy() {
        switch (state) {
        case NONE:
            return;
        case STARTED:
            throw new IllegalStateException("can't destroy while running");
        case DESTROYED:
            return;
        }

        // Nothing to do at the moment.

        state = State.DESTROYED;
    }

    /**
     * Starts a new Central Dogma server.
     */
    public static void main(String[] args) throws Exception {
        final Main main = new Main();

        // Register the shutdown hook.
        Runtime.getRuntime().addShutdownHook(new Thread("Central Dogma shutdown hook") {
            @Override
            public void run() {
                try {
                    main.stop();
                } catch (Exception e) {
                    logger.warn("Failed to stop the Central Dogma:", e);
                }

                try {
                    main.destroy();
                } catch (Exception e) {
                    logger.warn("Failed to destroy the Central Dogma:", e);
                }
            }
        });

        // Initialize the main with a dummy context.
        main.init(new DaemonContextImpl(args));

        // Exit if initialization failed.
        if (main.state != State.INITIALIZED) {
            System.exit(1);
            return;
        }

        main.start();
    }

    private static final class DaemonContextImpl implements DaemonContext {

        private final String[] args;

        DaemonContextImpl(String[] args) {
            this.args = args;
        }

        @Override
        public DaemonController getController() {
            return null;
        }

        @Override
        public String[] getArguments() {
            return args;
        }
    }
}
